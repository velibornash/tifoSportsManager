package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a league table from the matches that were actually played (owner, 2026-10-01).
 *
 * <p><b>This is a repair pass, not the writer.</b> {@code SimMatchService.persist} adds a match's points,
 * goals and result to {@code CompetitionEntry} the moment the match is saved, so the table is correct
 * immediately — a manager who watches his own match sees an up-to-date table, which is the whole point of
 * watching it. This runs the night after and fixes what that incremental path got wrong.
 *
 * <h2>Why a repair is needed at all</h2>
 *
 * <p>Because an increment has three ways to be wrong that a rebuild cannot have:
 *
 * <ul>
 *   <li><b>It is applied twice.</b> A replayed fixture, a restored backup or a re-run persist adds the
 *       same result again. There is no "already counted" flag, so there is nothing to notice.</li>
 *   <li><b>It is applied and then lost.</b> The write is inside the match's transaction, so that is the
 *       safe case — but a job or an admin action interrupted between matches leaves some applied and some
 *       not, and nothing reconciles it.</li>
 *   <li><b>It drifts.</b> Every write is an addition, and an addition cannot tell a correction from a
 *       mistake.</li>
 * </ul>
 *
 * <p>A rebuild from the match table is a pure function of that table, so it is idempotent by
 * construction, it converges from any starting state, and it gives the same answer on every machine —
 * the same three reasons {@code NationalRatingService} and {@code ClubRatingService} replay rather than
 * increment.
 *
 * <h2>Why it is not the only writer</h2>
 *
 * <p>Because a table that is only correct at 01:00 is wrong for the rest of the day, and the one moment a
 * manager checks it is immediately after his own match. So the fast path stays and this is the safety
 * net, which is why the job runs <b>the day after</b> the league matchday rather than instead of it.
 *
 * <p><b>Positions are not written here.</b> A table is only ordered when it is read, and the one
 * comparator in the codebase is {@code LeagueTableOrder}. The stored {@code position} column is written
 * by the matchday path and left alone here.
 */
@Service
public class LeagueTableReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(LeagueTableReconciliationService.class);

    /** What one run did, so the log can prove it moved something rather than saying it checked. */
    public record Result(int competitions, int tablesRebuilt, int entriesCorrected, int matchesRead) {
    }

    private final CompetitionRepository competitions;
    private final CompetitionEntryRepository entries;
    private final SeasonCompetitionRepository seasonCompetitions;
    private final MatchRepository matches;
    private final TransactionTemplate requiresNew;

    public LeagueTableReconciliationService(CompetitionRepository competitions,
                                             CompetitionEntryRepository entries,
                                             SeasonCompetitionRepository seasonCompetitions,
                                             MatchRepository matches,
                                             PlatformTransactionManager transactionManager) {
        this.competitions = competitions;
        this.entries = entries;
        this.seasonCompetitions = seasonCompetitions;
        this.matches = matches;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Every league's table in one season, in a transaction of its own.
     *
     * <p>Own transaction because this is called from a scheduled job and from an admin button, and a
     * failed table must not roll back the thirty that succeeded — nor leave the caller inside somebody
     * else's transaction, which is how a repair pass ends up not being written at all.
     */
    public Result reconcileAll(int seasonYear) {
        List<Competition> leagues = competitions.findAll().stream()
                .filter(c -> c.getType() == org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE)
                .toList();

        int rebuilt = 0;
        int corrected = 0;
        int read = 0;
        for (Competition league : leagues) {
            Result one = requiresNew.execute(status -> reconcile(league, seasonYear));
            if (one == null) {
                continue;
            }
            rebuilt += one.tablesRebuilt();
            corrected += one.entriesCorrected();
            read += one.matchesRead();
        }

        Result result = new Result(leagues.size(), rebuilt, corrected, read);
        log.info("League tables reconciled for season {}: {} competition(s), {} table(s) rebuilt, "
                        + "{} entr(y/ies) corrected from {} played match(es).",
                seasonYear, result.competitions(), result.tablesRebuilt(),
                result.entriesCorrected(), result.matchesRead());
        return result;
    }

    /**
     * One table, rebuilt from its played matches.
     *
     * <p>Entries the table does not have are <b>not</b> created. A club that has not played has nothing to
     * be corrected, and inventing a row for one is how a table grows members it should not have — the same
     * mistake {@code ensureEntriesForSeasonCompetition} used to make on the read path.
     */
    @Transactional
    public Result reconcile(Competition league, int seasonYear) {
        SeasonCompetition seasonCompetition = seasonCompetitions
                .findByCompetitionAndSeasonYear(league, seasonYear)
                .orElse(null);
        if (seasonCompetition == null) {
            return new Result(1, 0, 0, 0);
        }

        List<CompetitionEntry> table = entries.findBySeasonCompetition(seasonCompetition);
        if (table.isEmpty()) {
            return new Result(1, 0, 0, 0);
        }
        Map<Long, CompetitionEntry> byTeam = new HashMap<>();
        for (CompetitionEntry entry : table) {
            if (entry.getTeam() != null && entry.getTeam().getId() != null) {
                byTeam.put(entry.getTeam().getId(), entry);
            }
        }

        Map<Long, Tally> tallies = new HashMap<>();
        // Filtered by match TYPE, not merely by being played (P2-8). This pass rebuilds a table from
        // match rows, so it would happily count a practice match that happened to be recorded against
        // the competition — reintroducing exactly what the write path refused to add. The write path's
        // own guard cannot help here: a null competition skips it, and anything recorded *with* the
        // competition is counted here regardless.
        List<Match> played = matches.findByCompetitionIdAndSeasonYear(league.getId(), seasonYear).stream()
                .filter(Match::isPlayed)
                .filter(m -> m.getHomeTeam() != null && m.getAwayTeam() != null)
                .filter(m -> m.resolvedMatchType().countsForTable())
                .toList();

        for (Match match : played) {
            Long homeId = match.getHomeTeam().getId();
            Long awayId = match.getAwayTeam().getId();
            if (!byTeam.containsKey(homeId) || !byTeam.containsKey(awayId)) {
                // A team in a played match that is not in this table is a data problem. Counting it
                // would add goals to nobody's row and hide the problem rather than show it.
                log.warn("{} {} v {} is not in the table for {}; not counted.",
                        match.getId(), match.getHomeTeam().getName(),
                        match.getAwayTeam().getName(), league.getName());
                continue;
            }
            tallies.computeIfAbsent(homeId, key -> new Tally()).add(match.getHomeGoals(), match.getAwayGoals());
            tallies.computeIfAbsent(awayId, key -> new Tally()).add(match.getAwayGoals(), match.getHomeGoals());
        }

        int corrected = 0;
        List<CompetitionEntry> changed = new ArrayList<>();
        for (Map.Entry<Long, CompetitionEntry> row : byTeam.entrySet()) {
            Tally tally = tallies.getOrDefault(row.getKey(), Tally.EMPTY);
            CompetitionEntry entry = row.getValue();
            if (differs(entry, tally)) {
                tally.writeTo(entry);
                changed.add(entry);
                corrected++;
            }
        }
        if (!changed.isEmpty()) {
            entries.saveAll(changed);
        }

        // Positions are deliberately not written: the table is ordered when it is read.
        return new Result(1, 1, corrected, played.size());
    }

    private boolean differs(CompetitionEntry entry, Tally tally) {
        return !eq(entry.getPoints(), tally.points())
                || !eq(entry.getWins(), tally.wins)
                || !eq(entry.getDraws(), tally.draws)
                || !eq(entry.getLosses(), tally.losses)
                || !eq(entry.getGoalsScored(), tally.goalsFor)
                || !eq(entry.getGoalsConceded(), tally.goalsAgainst);
    }

    private boolean eq(Integer stored, int computed) {
        return stored != null && stored == computed;
    }

    /** One club's record, built from the results rather than added to them. */
    private static final class Tally {
        static final Tally EMPTY = new Tally();

        private int wins;
        private int draws;
        private int losses;
        private int goalsFor;
        private int goalsAgainst;

        void add(int scored, int conceded) {
            goalsFor += scored;
            goalsAgainst += conceded;
            if (scored > conceded) {
                wins++;
            } else if (scored == conceded) {
                draws++;
            } else {
                losses++;
            }
        }

        int points() {
            return wins * 3 + draws;
        }

        void writeTo(CompetitionEntry entry) {
            entry.setPoints(points());
            entry.setWins(wins);
            entry.setDraws(draws);
            entry.setLosses(losses);
            entry.setGoalsScored(goalsFor);
            entry.setGoalsConceded(goalsAgainst);
        }
    }
}