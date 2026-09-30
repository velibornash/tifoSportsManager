package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.LeagueTableOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three international club cups (owner, 2026-09-30).
 *
 * <p>The World page has listed Champions Cup, Masters Cup and Challenge Cup as "Not created yet" since
 * they were written into the markup. They are created here, and — the part that matters — so are the
 * clubs that qualify for each one.
 *
 * <h2>Who is in each, and why those positions</h2>
 *
 * <p>The owner specified it: <b>Champions for the winners, Masters for the second and third, Challenge
 * for the fourth.</b> It is a nice shape — one competition per finishing place band, so every division in
 * the world contributes to all three and a club's league position decides which continental competition
 * it is playing in. A second-tier champion in a fifth division reaches the Champions Cup, exactly as a
 * fifth-tier champion in a first division does, because the rule is about the place in <i>your</i>
 * division rather than the strength of the division.
 *
 * <h2>Which season's table</h2>
 *
 * <p><b>The season that has finished</b>, not the one in progress. A cup is entered on the strength of
 * last season, so a club that wins its division in week 12 cannot enter the same season's Champions Cup
 * by winning it in week 12 — the entry is decided by the table everyone has already played.
 *
 * <p>Positions come from {@link LeagueTableOrder}, the one comparator in the codebase, rather than from
 * the stored {@code position} column. A table is only ordered when it is read, and reading a
 * half-played table through a stale position column is how a table ends up claiming a club finished first
 * when it finished fourth.
 */
@Service
public class InternationalClubCups {

    private static final Logger log = LoggerFactory.getLogger(InternationalClubCups.class);

    public static final String CHAMPIONS = "Champions Cup";
    public static final String MASTERS = "Masters Cup";
    public static final String CHALLENGE = "Challenge Cup";

    /** One cup and the finishing places in a division that qualify for it. */
    public record Cup(String name, int tier, int placesFrom, int placesTo) {
    }

    /**
     * The three cups, in the order they are played.
     *
     * <p>Tier 1/2/3 on the competition, which is the only place this game's model can record the
     * difference between them — there is no format column and inventing one for three rows is a bigger
     * change than three rows are worth.
     */
    public static final List<Cup> CUPS = List.of(
            new Cup(CHAMPIONS, 1, 1, 1),
            new Cup(MASTERS, 2, 2, 3),
            new Cup(CHALLENGE, 3, 4, 4));

    private final CompetitionRepository competitions;
    private final CompetitionEntryRepository entries;
    private final SeasonCompetitionRepository seasonCompetitions;
    private final TeamRepository teams;
    private final TransactionTemplate requiresNew;

    public InternationalClubCups(CompetitionRepository competitions,
                                 CompetitionEntryRepository entries,
                                 SeasonCompetitionRepository seasonCompetitions,
                                 TeamRepository teams,
                                 PlatformTransactionManager transactionManager) {
        this.competitions = competitions;
        this.entries = entries;
        this.seasonCompetitions = seasonCompetitions;
        this.teams = teams;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One cup's record and its qualified clubs, for the World page and for admin. */
    public record CupSummary(String name, Long competitionId, int qualified, List<String> clubNames) {
    }

    /**
     * Creates the three competitions if they are not there.
     *
     * <p>Idempotent by name, and deliberately <b>not</b> touching a competition that already exists — a
     * cup that has been played must not have its record rewritten because the seeder ran again.
     */
    /**
     * Creates the three competitions and commits that on its own.
     *
     * <p>The boot listener is one long transaction, and three separate steps in this codebase have been
     * lost to it: the league-fixture day stamp, the bot league standards, and the national Elo replay.
     * Each of those writes now in {@code REQUIRES_NEW}, and this is the same. The cups were created
     * inside the boot transaction, the boot transaction did not survive, and the database was left with
     * none of them while the World page said "Not created yet" — which was true, but for a different
     * reason than it had been.
     */
    public List<Competition> ensureCompetitionsDurably() {
        return requiresNew.execute(status -> ensureCompetitions());
    }

    @Transactional
    public List<Competition> ensureCompetitions() {
        List<Competition> ensured = new ArrayList<>();
        for (Cup cup : CUPS) {
            Competition competition = findByName(cup.name()).orElse(null);
            if (competition == null) {
                competition = new Competition();
                competition.setName(cup.name());
                competition.setType(CompetitionType.CUP);
                // INTERNATIONAL is the whole difference from a national cup: the entrants come from many
                // countries, and this is the column that says so.
                competition.setScope(CompetitionScope.INTERNATIONAL);
                competition.setTeamType(CompetitionTeamType.CLUB);
                competition.setTier(cup.tier());
                competition = competitions.save(competition);
            }
            ensured.add(competition);
        }
        return ensured;
    }

    private Optional<Competition> findByName(String name) {
        return competitions.findAll().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst();
    }

    /**
     * The clubs that have qualified for a cup, from the finished season's tables.
     *
     * @param finishedSeason the season whose final tables decide entry
     */
    /**
     * What each cup holds, for the World page.
     *
     * <p><b>Read-only, and it does not create the competitions.</b> My first version called
     * {@link #ensureCompetitions()} from here, so a GET on the world page issued an INSERT — and the
     * endpoint is transactional read-only, so the whole thing failed with "cannot execute INSERT in a
     * read-only transaction" and the World page returned a 500. A read that writes is wrong twice over: it
     * breaks under a read-only transaction, and it means the page only works if it is the thing that
     * happens to run first. The competitions are created on boot instead, and a cup that is somehow
     * missing is reported as missing rather than conjured by a page view.
     */
    @Transactional(readOnly = true)
    public List<CupSummary> summarise(int finishedSeason) {
        List<CupSummary> summaries = new ArrayList<>();
        for (Cup cup : CUPS) {
            List<Team> qualified = qualifiedFor(cup, finishedSeason);
            summaries.add(new CupSummary(cup.name(),
                    findByName(cup.name()).map(Competition::getId).orElse(null),
                    qualified.size(),
                    qualified.stream().map(Team::getName).sorted().toList()));
        }
        return summaries;
    }

    /**
     * The clubs that finish in this cup's band, across every division in the world.
     *
     * <p>Every division, at every tier. That is the point of the rule: the fifth division's champion
     * qualifies for the same Champions Cup as the first division's, and a world where the Champions Cup
     * only ever contained the best ten clubs would be a ranking of strength rather than a competition.
     */
    @Transactional(readOnly = true)
    public List<Team> qualifiedFor(Cup cup, int finishedSeason) {
        Map<Long, Team> byId = new LinkedHashMap<>();
        for (Competition league : leagueDivisions()) {
            Optional<SeasonCompetition> seasonCompetition =
                    seasonCompetitions.findByCompetitionAndSeasonYear(league, finishedSeason);
            if (seasonCompetition.isEmpty()) {
                continue;
            }
            List<CompetitionEntry> table = LeagueTableOrder.sort(
                    entries.findBySeasonCompetition(seasonCompetition.get()));
            for (int index = cup.placesFrom() - 1; index < cup.placesTo(); index++) {
                if (index < 0 || index >= table.size()) {
                    continue;
                }
                Team team = table.get(index).getTeam();
                if (team != null && team.getId() != null) {
                    byId.putIfAbsent(team.getId(), team);
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /** Every club division in the world, in ladder order. */
    private List<Competition> leagueDivisions() {
        return competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.LEAGUE)
                .sorted(java.util.Comparator
                        .comparing((Competition c) -> c.getTier() == null ? Integer.MAX_VALUE : c.getTier())
                        .thenComparing(c -> c.getDivisionLevel() == null ? Integer.MAX_VALUE : c.getDivisionLevel())
                        .thenComparing(Competition::getId))
                .toList();
    }
}
