package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-14 — prize money is paid, and it is paid to the right clubs.
 *
 * <p>{@code WeeklyFinanceService.awardPrizeMoney} was implemented and had <b>zero callers</b>. The
 * weekly {@code prizeMoney} line beside it deliberately returns {@code null} — "only paid once the
 * season is finished" — so the two halves were designed to meet and the meeting never happened. No club
 * had ever been paid prize money in this game.
 *
 * <p><b>The trap, and why this class exists.</b> The dead method ranked clubs by
 * {@code CompetitionEntry.position}. <b>Nothing sets that field during a season</b> —
 * {@code PyramidBuilder} writes it once when the world is built and it is never updated. Wiring the
 * method exactly as written would have paid the champion's money to whichever club happened to be
 * seeded first, in every season, silently, with a correct-looking ledger line saying
 * "Finished P1 of 10".
 *
 * <p><b>What is guaranteed here:</b>
 *
 * <ul>
 *   <li><b>The champion is paid the champion's money</b> — ranked by points, then goal difference, then
 *       goals scored: the same {@code LeagueTableOrder} the manager's own table and the playoff draw
 *       already use.</li>
 *   <li><b>The seeded position is ignored entirely.</b> The fixture gives the champion the <em>worst</em>
 *       stored position on purpose, so an implementation that trusted the field would fail.</li>
 *   <li><b>A season nobody played pays nothing</b>, so a freshly seeded world rolled over before a
 *       single match does not mint a prize pool.</li>
 *   <li><b>It is written to the ledger and to the budget</b>, because a payout that is computed is not a
 *       payout.</li>
 *   <li><b>Once, not weekly.</b> The weekly line must still write nothing, or the season pays
 *       repeatedly.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class PrizeMoneyFollowsTheRealTableTest {

    private static final int SEASON = 3;

    /** What the fixture seeded each club's stored position as, so a test can name it. */
    private final java.util.Map<Team, Integer> seededPositions = new java.util.LinkedHashMap<>();

    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired SeasonCompetitionRepository seasonCompetitions;
    @Autowired CompetitionEntryRepository entries;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired WeeklyFinanceService finances;

    private Team aClub(String name, double budget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(budget);
        t.setReputation(60.0);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(20_000);
        s.setTicketPrice(20.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    /**
     * A finished league.
     *
     * <p>The stored {@code position} values are deliberately the <em>reverse</em> of the truth: the
     * champion is seeded 4th and the bottom club 1st. Anything that ranks on the field fails.
     */
    private SeasonCompetition aFinishedLeague(Team... clubs) {
        Competition league = new Competition();
        league.setName("Test League " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league.setTier(1);
        league.setTeamsPerCompetition(clubs.length);
        league = competitions.save(league);

        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(league);
        sc.setSeasonYear(SEASON);
        sc = seasonCompetitions.save(sc);

        int[] points = {30, 22, 16, 4};
        int[] scored = {40, 30, 20, 8};
        int[] conceded = {10, 14, 22, 30};
        int[] seededPosition = {4, 3, 2, 1};
        for (int i = 0; i < clubs.length; i++) {
            CompetitionEntry e = new CompetitionEntry();
            e.setSeasonCompetition(sc);
            e.setTeam(clubs[i]);
            e.setPoints(points[i]);
            e.setGoalsScored(scored[i]);
            e.setGoalsConceded(conceded[i]);
            e.setPosition(seededPosition[i]);
            entries.save(e);
            seededPositions.put(clubs[i], seededPosition[i]);
        }
        return sc;
    }

    private double prizePaidTo(Team club) {
        return ledger.findAll().stream()
                .filter(e -> e.getTeam() != null && club.getId().equals(e.getTeam().getId()))
                .filter(e -> e.getCategory() == FinanceCategory.PRIZE_MONEY)
                .mapToDouble(e -> Math.abs(e.getAmount()))
                .sum();
    }

    private long prizeLines(Team club) {
        return ledger.findAll().stream()
                .filter(e -> e.getTeam() != null && club.getId().equals(e.getTeam().getId()))
                .filter(e -> e.getCategory() == FinanceCategory.PRIZE_MONEY)
                .count();
    }

    /** The whole point: the money follows the table, and the table is not the seed order. */
    @Test
    @DisplayName("prize money is paid by finishing position, not by the seeded position")
    void prizeMoneyFollowsTheRealTable() {
        Team champion = aClub("Champion", 1_000_000);
        Team second = aClub("RunnerUp", 1_000_000);
        Team third = aClub("Third", 1_000_000);
        Team bottom = aClub("Bottom", 1_000_000);
        SeasonCompetition sc = aFinishedLeague(champion, second, third, bottom);

        assertEquals(4, finances.awardPrizeMoney(sc, SEASON));

        // Every club in the table is paid, not only the winner: a payout that skips the bottom of the
        // table is a champion's bonus, not a prize money system.
        for (Team club : List.of(champion, second, third, bottom)) {
            assertEquals(1, prizeLines(club),
                    "each club is paid exactly once for the season: " + club.getName());
            assertTrue(prizePaidTo(club) > 0, "and actually paid something");
        }
        assertTrue(prizePaidTo(champion) > prizePaidTo(second),
                "the champion takes the largest share: " + prizePaidTo(champion) + " against "
                        + prizePaidTo(second));
        assertTrue(prizePaidTo(second) > prizePaidTo(third));
        assertTrue(prizePaidTo(third) > prizePaidTo(bottom));

        // And the decisive one: the champion is seeded FOURTH, so a method that ranked on the stored
        // field would have paid him last. This is the assertion that would have caught it.
        assertEquals(4, seededPositionOf(champion),
                "precondition: the champion is seeded fourth, so the stored field cannot be trusted");
        assertTrue(prizePaidTo(champion) > prizePaidTo(bottom),
                "a club seeded fourth on points alone must still take the champion's money");
    }

    /** Read from the fixture, not re-read from the database: the value is the point, not the query. */
    private int seededPositionOf(Team club) {
        return seededPositions.getOrDefault(club, 0);
    }

    /** A world seeded and rolled over before a single match must not mint money. */
    @Test
    @DisplayName("a season nobody played pays nothing")
    void anUnplayedSeasonPaysNothing() {
        Team a = aClub("UnplayedA", 1_000_000);
        Team b = aClub("UnplayedB", 1_000_000);

        Competition league = new Competition();
        league.setName("Empty League " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league.setTier(1);
        league.setTeamsPerCompetition(2);
        league = competitions.save(league);
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(league);
        sc.setSeasonYear(SEASON + 40);
        sc = seasonCompetitions.save(sc);
        for (Team t : List.of(a, b)) {
            CompetitionEntry e = new CompetitionEntry();
            e.setSeasonCompetition(sc);
            e.setTeam(t);
            e.setPoints(0);
            e.setGoalsScored(0);
            e.setGoalsConceded(0);
            entries.save(e);
        }

        assertEquals(0, finances.awardPrizeMoney(sc, SEASON + 40),
                "a full prize pool for a season that never happened is how a fresh world gets rich");
        assertEquals(0, prizePaidTo(a));
        assertEquals(0, prizePaidTo(b));
    }

    /**
     * The two halves must not both pay.
     *
     * <p>Asserted through the public weekly entry point rather than by making the private
     * {@code prizeMoney} visible, because the guarantee is about what a club's week does — not about
     * the internals of one method.
     */
    @Test
    @DisplayName("a settled week still pays no prize money, so a season is paid once")
    void aSettledWeekPaysNoPrizeMoney() {
        Team club = aClub("WeeklyClub", 1_000_000);

        finances.applyWeeklyFinances(club, SEASON, 5);

        assertEquals(0, prizePaidTo(club),
                "the weekly hook must keep deferring to the season-end payout, or a club is paid "
                        + "every week as well as at the end of the season");
    }

    /** And the money is real: budget and ledger, not a number in a log. */
    @Test
    @DisplayName("the payout reaches the budget and the ledger")
    void thePayoutIsReal() {
        Team champion = aClub("PaidChampion", 1_000_000);
        Team second = aClub("PaidSecond", 1_000_000);
        SeasonCompetition sc = aFinishedLeague(champion, second);

        finances.awardPrizeMoney(sc, SEASON);

        double budget = teams.findById(champion.getId()).orElseThrow().getBudget();
        assertEquals(1_000_000 + prizePaidTo(champion), budget, 0.01,
                "the champion's budget rises by exactly what the ledger says he was paid");
        assertTrue(prizePaidTo(champion) > 0);
        assertTrue(ledger.findAll().stream()
                        .anyMatch(e -> e.getCategory() == FinanceCategory.PRIZE_MONEY
                                && e.getNote() != null && e.getNote().contains("Finished P1 of")),
                "and the ledger says which place it was, so a manager can audit the payout");
    }
}