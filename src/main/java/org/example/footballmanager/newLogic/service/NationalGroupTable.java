package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalGroupTieBreak;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.NationalGroupTieBreakRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One national group's table, computed from the matches that were played (owner, 2026-10-06).
 *
 * <h2>Why it is computed rather than stored</h2>
 *
 * <p>{@code CompetitionEntry} holds one row per team per season-competition, so it can express a
 * league but not eight groups of six inside one competition: the eight groups would share their
 * opponents' rows. The club cups get away with it because every entrant plays in exactly one group and
 * a knockout match does not write the table. A national tournament needs the same property but also
 * needs to read one group on its own, which is a question the stored table cannot answer.
 *
 * <p>So the standing is a <b>pure function of the played fixtures</b> — the same three reasons
 * {@code NationalRatingService} and {@code LeagueTableReconciliationService} replay instead of
 * increment. It cannot double-count a replayed match, it converges from any starting state, and it
 * gives the same answer on every machine.
 *
 * <h2>The tie-break chain</h2>
 *
 * <p>The owner: <b>points, goal difference, goals scored, zreb</b>. The first three are arithmetic. The
 * fourth is a coin, and a coin re-rolled on every read is a table that reorders itself while nobody is
 * looking, so the seed is written once per group to {@link NationalGroupTieBreak} and read back
 * afterwards. The final key is a per-team mix of that seed, so the order is stable and independent of
 * the order the repository happened to return teams in.
 */
@Service
public class NationalGroupTable {

    private static final Logger log = LoggerFactory.getLogger(NationalGroupTable.class);

    private final MatchRepository matches;
    private final MatchFixtureRepository fixtureRepository;
    private final NationalGroupTieBreakRepository tieBreaks;

    public NationalGroupTable(MatchRepository matches, MatchFixtureRepository fixtureRepository,
                              NationalGroupTieBreakRepository tieBreaks) {
        this.matches = matches;
        this.fixtureRepository = fixtureRepository;
        this.tieBreaks = tieBreaks;
    }

    /** One nation's record in a group. */
    public record Standing(Team team, int played, int wins, int draws, int losses,
                           int goalsScored, int goalsConceded) {

        public int points() {
            return wins * 3 + draws;
        }

        public int goalDifference() {
            return goalsScored - goalsConceded;
        }
    }

    /**
     * One group in the order the owner specified: points, goal difference, goals scored, then the
     * stored coin.
     *
     * <p>Teams with no played match are still returned, at the bottom in name order. Dropping them
     * would make a group of fewer than six look complete, and a manager looking at his own nation
     * would see it missing rather than on zero points.
     */
    @Transactional
    public List<Standing> standings(Competition competition, int seasonYear, String groupCode) {
        // Membership from the fixtures, so a nation that has not played yet is still on its group's
        // table on zero points; results from the played matches, so the numbers are the ones that
        // happened rather than an accumulation of them.
        List<MatchFixture> groupFixtures = fixtureRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(competition.getId(), seasonYear)
                .stream()
                .filter(f -> groupCode.equals(f.getGroupCode()))
                .toList();

        Map<Long, Tally> tallies = new LinkedHashMap<>();
        for (Match played : matches.findByCompetitionIdAndSeasonYear(competition.getId(), seasonYear)) {
            if (!groupCode.equals(played.getGroupCode())) {
                continue;
            }
            if (!played.isPlayed() || played.getHomeTeam() == null || played.getAwayTeam() == null) {
                continue;
            }
            tallies.computeIfAbsent(played.getHomeTeam().getId(), key -> new Tally()).add(
                    played.getHomeGoals(), played.getAwayGoals());
            tallies.computeIfAbsent(played.getAwayTeam().getId(), key -> new Tally()).add(
                    played.getAwayGoals(), played.getHomeGoals());
        }

        List<Standing> standings = new ArrayList<>();
        for (Team team : membersOf(groupFixtures)) {
            Tally tally = tallies.getOrDefault(team.getId(), Tally.EMPTY);
            standings.add(new Standing(team, tally.played, tally.wins, tally.draws, tally.losses,
                    tally.goalsFor, tally.against));
        }

        long seed = seedFor(competition, seasonYear, groupCode);
        standings.sort(orderOn(seed));
        return standings;
    }

    /** Who is in a group, read back off the fixtures it drew. The fixtures are the record. */
    private List<Team> membersOf(List<MatchFixture> groupFixtures) {
        Map<Long, Team> byId = new LinkedHashMap<>();
        for (MatchFixture fixture : groupFixtures) {
            if (fixture.getHomeTeam() != null) {
                byId.putIfAbsent(fixture.getHomeTeam().getId(), fixture.getHomeTeam());
            }
            if (fixture.getAwayTeam() != null) {
                byId.putIfAbsent(fixture.getAwayTeam().getId(), fixture.getAwayTeam());
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * The owner's chain, with the coin last.
     *
     * <p>Name before id before coin, so the coin is only ever reached by teams that are genuinely
     * level on all three of the owner's keys. Two teams level on points, goal difference <i>and</i>
     * goals scored are exactly as indistinguishable from each other as two teams level on points
     * alone, so naming one of them before the coin is the same arbitrary rule wearing a disguise.
     */
    private Comparator<Standing> orderOn(long seed) {
        Comparator<Standing> nameOrder = Comparator
                .comparing((Standing s) -> s.team().getName() == null ? "" : s.team().getName())
                .thenComparing(s -> s.team().getId() == null ? Long.MAX_VALUE : s.team().getId());
        Comparator<Standing> chain = Comparator
                .comparingInt(Standing::points).reversed()
                .thenComparing(Comparator.comparingInt(Standing::goalDifference).reversed())
                .thenComparing(Comparator.comparingInt(Standing::goalsScored).reversed());
        return chain.thenComparing(nameOrder)
                .thenComparingLong(s -> coin(s.team(), seed));
    }

    /**
     * The coin: a per-team mix of the group seed.
     *
     * <p>A shuffle would depend on the order the list arrived in, so two reads of the same group could
     * disagree. Mixing the seed with the team id gives every team its own stable draw from the one
     * stored number, which is what makes the coin replayable.
     */
    private long coin(Team team, long seed) {
        long id = team.getId() == null ? 0L : team.getId();
        long z = seed + 0x9E3779B97F4A7C15L * (id + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * The group seed, written the first time the group needs it.
     *
     * <p>Derived from the competition, the season and the group so a fresh install and a restored
     * backup draw the same coin, then stored so the answer is a fact rather than arithmetic.
     */
    @Transactional
    public long seedFor(Competition competition, int seasonYear, String groupCode) {
        return tieBreaks.findByCompetitionIdAndSeasonYearAndGroupCode(
                        competition.getId(), seasonYear, groupCode)
                .map(NationalGroupTieBreak::getSeed)
                .orElseGet(() -> storeSeed(competition, seasonYear, groupCode));
    }

    private long storeSeed(Competition competition, int seasonYear, String groupCode) {
        NationalGroupTieBreak row = new NationalGroupTieBreak();
        row.setCompetition(competition);
        row.setSeasonYear(seasonYear);
        row.setGroupCode(groupCode);
        row.setSeed(deriveSeed(competition.getId(), seasonYear, groupCode));
        row.setDrawnAt(Instant.now());
        NationalGroupTieBreak saved = tieBreaks.save(row);
        log.info("{} group {}: tie-break coin drawn ({}).", competition.getName(), groupCode, saved.getSeed());
        return saved.getSeed();
    }

    /** A seed that depends on nothing but the group's identity. */
    public static long deriveSeed(Long competitionId, int seasonYear, String groupCode) {
        long z = competitionId * 1_000_003L + seasonYear * 10_007L;
        for (int i = 0; i < groupCode.length(); i++) {
            z = z * 31L + groupCode.charAt(i);
        }
        return z == 0L ? 1L : Math.abs(z);
    }

    /** One nation's record, built from the results rather than added to them. */
    private static final class Tally {
        static final Tally EMPTY = new Tally();

        private int played;
        private int wins;
        private int draws;
        private int losses;
        private int goalsFor;
        private int against;

        void add(int scored, int conceded) {
            played++;
            goalsFor += scored;
            against += conceded;
            if (scored > conceded) {
                wins++;
            } else if (scored == conceded) {
                draws++;
            } else {
                losses++;
            }
        }
    }
}