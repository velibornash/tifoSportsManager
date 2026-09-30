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
import java.util.Comparator;
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

    /**
     * One cup at one tier, and the finishing places in a division that qualify for it.
     *
     * <p><b>Per tier, and that is the correction that matters</b> (owner, 2026-09-30). The cups are not
     * three competitions with every division on the planet in them: tier 1's Champions Cup is contested
     * by tier 1's divisions and meets nobody else. Tier 1 has one division per country while tier 5 has
     * sixteen, so a single global Champions Cup would have quietly thrown 32 fifth-tier clubs in with
     * two first-tier ones and called it a competition between equals.
     *
     * <p><b>Every division in the tier contributes</b>, because in the lower tiers there is more than one
     * division per country — they are mini-tables, and each is a separate door into the cups.
     *
     * <p>The bands are the owner's: <b>Champions for the winners, Masters for the second and third,
     * Challenge for the best of the fourth-placed clubs.</b> Every division in the tier sends a club to
     * each, which is what keeps the draw the same shape whichever tier is being played.
     */
    public record Cup(int tier, String name, int placesFrom, int placesTo) {

        /** "Champions Cup", "Tier 3 Champions Cup" - the tier is in the name because they are
         *  different competitions and a table of three would hide that. */
        public String fullName() {
            return tier <= 1 ? name : "Tier " + tier + " " + name;
        }
    }

    /** Five tiers, three cups each. The bands are the owner's. */
    public static List<Cup> cups() {
        List<Cup> cups = new ArrayList<>();
        for (int tier = 1; tier <= 5; tier++) {
            cups.add(new Cup(tier, CHAMPIONS, 1, 1));
            cups.add(new Cup(tier, MASTERS, 2, 3));
            cups.add(new Cup(tier, CHALLENGE, 4, 4));
        }
        return List.copyOf(cups);
    }

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
    public record CupSummary(String name, int tier, Long competitionId, int qualified, List<String> clubNames) {
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
        for (Cup cup : cups()) {
            Competition competition = findByName(cup.fullName()).orElse(null);
            if (competition == null) {
                competition = new Competition();
                competition.setName(cup.fullName());
                competition.setType(CompetitionType.CUP);
                // INTERNATIONAL is the whole difference from a national cup: the entrants come from
                // several countries, and this is the only column that says so.
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
        for (Cup cup : cups()) {
            List<Team> qualified = qualifiedFor(cup, finishedSeason);
            summaries.add(new CupSummary(cup.fullName(), cup.tier(),
                    findByName(cup.fullName()).map(Competition::getId).orElse(null),
                    qualified.size(),
                    qualified.stream().map(Team::getName).sorted().toList()));
        }
        return summaries;
    }

    /**
     * The clubs this tier's cup is contested by.
     *
     * <p><b>One entry per country per cup, which is what makes the numbers work</b> (owner, 2026-09-30).
     * Tier 1 has one division per country, so a country's representative is its division winner, and
     * 48 countries give the Champions Cup its 48 clubs. The Masters Cup takes the best <b>two</b> of each
     * country's second- and third-placed clubs, so 48 x 2 = 96. The Challenge Cup takes the best one of
     * each country's fourth-placed clubs, so 48 again — the same size as the Champions Cup, as specified.
     *
     * <h2>The mini-tables, and why a tier needs them</h2>
     *
     * <p>A country has more than one division in the lower tiers, and each of those is a mini-table with
     * its own champion, its own seconds and its own fourths. A country enters its tier's cup with the
     * best of them:
     *
     * <ul>
     *   <li><b>Champions</b> — the better of the divisions' winners. One club, not one per division, or a
     *       country with sixteen divisions would enter sixteen times.</li>
     *   <li><b>Masters</b> — the best two of the pool of every 2nd- and 3rd-placed club. In tier 1 that pool
     *       is exactly two clubs, so the rule degenerates to "the 2nd and the 3rd", which is the
     *       straightforward case the owner started from.</li>
     *   <li><b>Challenge</b> — the best one of the pool of every 4th-placed club. In tier 1 that pool is
     *       one club.</li>
     * </ul>
     *
     * <p>So the same three rules cover both cases, and tier 1 needs no special case at all — which is
     * what makes it safe to write once.
     */
    @Transactional(readOnly = true)
    public List<Team> qualifiedFor(Cup cup, int finishedSeason) {
        Map<Long, List<List<CompetitionEntry>>> byCountry = new LinkedHashMap<>();
        for (Competition league : divisionsInTier(cup.tier())) {
            Optional<SeasonCompetition> seasonCompetition =
                    seasonCompetitions.findByCompetitionAndSeasonYear(league, finishedSeason);
            if (seasonCompetition.isEmpty() || league.getCountry() == null) {
                continue;
            }
            List<CompetitionEntry> table = LeagueTableOrder.sort(
                    entries.findBySeasonCompetition(seasonCompetition.get()));
            byCountry.computeIfAbsent(league.getCountry().getId(), key -> new ArrayList<>()).add(table);
        }

        Map<Long, Team> chosen = new LinkedHashMap<>();
        for (List<List<CompetitionEntry>> divisions : byCountry.values()) {
            // Pool per finishing place, across all of the country's divisions in this tier.
            List<Team> winners = poolAt(divisions, 0);
            List<Team> secondsAndThirds = new ArrayList<>(poolAt(divisions, 1));
            secondsAndThirds.addAll(poolAt(divisions, 2));
            secondsAndThirds.sort(Comparator.comparingDouble(this::reputationOf).reversed());
            List<Team> fourths = poolAt(divisions, 3);

            take(winners, cup.placesFrom() == 1 && cup.placesTo() == 1 ? 1 : 0, chosen);
            if (cup.placesFrom() == 2) {
                take(secondsAndThirds, 2, chosen);
            } else if (cup.placesFrom() == 4) {
                take(fourths, 1, chosen);
            }
        }
        return List.copyOf(chosen.values());
    }

    /** Every club sitting at one finishing place across a country's divisions, best first. */
    private List<Team> poolAt(List<List<CompetitionEntry>> divisions, int index) {
        List<Team> pool = new ArrayList<>();
        for (List<CompetitionEntry> table : divisions) {
            if (index < table.size() && table.get(index).getTeam() != null) {
                pool.add(table.get(index).getTeam());
            }
        }
        pool.sort(Comparator.comparingDouble(this::reputationOf).reversed());
        return pool;
    }

    private void take(List<Team> poolBestFirst, int howMany, Map<Long, Team> chosen) {
        for (int i = 0; i < Math.min(howMany, poolBestFirst.size()); i++) {
            Team team = poolBestFirst.get(i);
            if (team != null && team.getId() != null) {
                chosen.putIfAbsent(team.getId(), team);
            }
        }
    }

    /**
     * How good a club is, for picking the better of two divisions' winners.
     *
     * <p>Club reputation, which is the 0-100 scale the economy uses — deliberately not a squad average,
     * which would mean a query per candidate for a question the game already stores the answer to.
     */
    private double reputationOf(Team team) {
        return team.getReputation() == null ? 0.0 : team.getReputation();
    }

    /** Every club division in one tier, in ladder order. */
    @Transactional(readOnly = true)
    public List<Competition> divisionsInTier(int tier) {
        return competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.LEAGUE)
                .filter(c -> c.getTier() != null && c.getTier() == tier)
                .sorted(java.util.Comparator
                        .comparing((Competition c) -> c.getDivisionLevel() == null ? Integer.MAX_VALUE : c.getDivisionLevel())
                        .thenComparing(Competition::getId))
                .toList();
    }

    /**
     * How many divisions a tier has, which is how many clubs each of its cups can draw from.
     *
     * <p>Derived, never assumed. The world's shape is not fixed: two active countries with full
     * pyramids give 2 divisions in tier 1 and 32 in tier 5, and activating a third changes both.
     */
    @Transactional(readOnly = true)
    public long divisionsInTierCount(int tier) {
        return divisionsInTier(tier).size();
    }

    /** Every club division in the world, in ladder order. */
    @Transactional(readOnly = true)
    public List<Competition> leagueDivisions() {
        return competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.LEAGUE)
                .sorted(java.util.Comparator
                        .comparing((Competition c) -> c.getTier() == null ? Integer.MAX_VALUE : c.getTier())
                        .thenComparing(c -> c.getDivisionLevel() == null ? Integer.MAX_VALUE : c.getDivisionLevel())
                        .thenComparing(Competition::getId))
                .toList();
    }
}
