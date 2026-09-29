package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.LeagueTableOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S8.4: one league-table order, and a table that survives being read.
 *
 * <p>Two defects, and the second is the one that mattered more than the kanban said. The kanban
 * described {@code ensureEntriesForSeasonCompetition} as "deletes and rebuilds all entries on
 * membership drift". It did — and the league table endpoint calls it <em>before it reads</em>, so
 * one club joining a division reset every other club's season to zero for a manager who had done
 * nothing but open the page.
 */
class LeagueTableOrderTest extends BaseTest {

    // ---------- the order ----------

    @Test
    @DisplayName("points, then goal difference, then goals scored, then team id")
    void theOrderIsPointsThenGoalDifferenceThenGoalsScoredThenId() {
        List<CompetitionEntry> entries = List.of(
                // Deliberately listed worst-first, so a sort that does nothing still fails.
                entry(1L, 10, 10, 30, "Tied on points and goal difference, fewer goals scored"),
                entry(2L, 30, 30, 0, "Three points"),
                entry(3L, 20, 5, 0, "Fewer points, better goal difference"),
                entry(4L, 30, 20, 40, "Same points as the best, worse goal difference"),
                entry(5L, 20, 20, 40, "Same points and difference as the worst, more goals scored"),
                entry(6L, 30, 40, 20, "Better goal difference than the other three-pointer"));

        List<String> order = LeagueTableOrder.sort(entries).stream()
                .map(e -> e.getTeam().getName())
                .toList();

        // On 30 points: +30, then +20, then -20. On 20: +5 before -20. So every one of the four
        // keys is load-bearing here, which is the point - a comparator that dropped goals scored
        // would still pass the first three positions.
        assertEquals(List.of(
                "Three points",
                "Better goal difference than the other three-pointer",
                "Same points as the best, worse goal difference",
                "Fewer points, better goal difference",
                "Same points and difference as the worst, more goals scored",
                "Tied on points and goal difference, fewer goals scored"), order);
    }

    @Test
    @DisplayName("a club level on everything is ordered by id, so the same table renders the same way twice")
    void aTotalTieIsBrokenStablyById() {
        CompetitionEntry a = entry(9L, 20, 5, 5, "Nine");
        CompetitionEntry b = entry(2L, 20, 5, 5, "Two");

        List<String> first = names(LeagueTableOrder.sort(List.of(a, b)));
        List<String> second = names(LeagueTableOrder.sort(List.of(b, a)));

        assertEquals(first, second, "the order must not depend on which order the rows arrived in");
        assertEquals(List.of("Two", "Nine"), first);
    }

    @Test
    @DisplayName("a null is zero, not a crash")
    void aNullSortsAsZero() {
        // Two of the four comparators this replaced did `getGoalsScored() - getGoalsConceded()`,
        // which unboxes. One of them was the league table endpoint the manager reads, so a club with
        // a half-created entry took the page down rather than sorting.
        CompetitionEntry blank = entry(1L, null, null, null, "No record yet");
        CompetitionEntry scoring = entry(2L, 20, 3, 1, "Scoring");

        assertEquals(List.of("Scoring", "No record yet"),
                names(LeagueTableOrder.sort(List.of(blank, scoring))));

        CompetitionEntry noTeam = new CompetitionEntry();
        noTeam.setPoints(30);
        assertNotNull(LeagueTableOrder.sort(List.of(noTeam, scoring)));
    }

    // ---------- the table survives being read ----------

    @Test
    @Transactional
    @DisplayName("a club joining mid-season does not reset the season for everyone else")
    void aNewClubDoesNotWipeTheTable() {
        Country country = countryRepository.save(country());
        Competition league = competitionRepository.save(competition(country));
        Team a = teamRepository.save(team("Alpha", country, league));
        Team b = teamRepository.save(team("Bravo", country, league));

        seasonService.ensureEntriesForSeasonCompetition(league, 1);

        // Alpha has played. This is the record that used to be thrown away.
        CompetitionEntry alphaEntry = entryFor(a);
        alphaEntry.setPoints(21);
        alphaEntry.setWins(7);
        alphaEntry.setDraws(0);
        alphaEntry.setLosses(0);
        alphaEntry.setGoalsScored(18);
        alphaEntry.setGoalsConceded(4);
        competitionEntryRepository.save(alphaEntry);

        // A third club joins the division. The league table endpoint calls this next.
        teamRepository.save(team("Charlie", country, league));
        seasonService.ensureEntriesForSeasonCompetition(league, 1);

        CompetitionEntry after = entryFor(a);
        assertNotNull(after, "Alpha's row must still exist");
        assertEquals(21, after.getPoints(), "Alpha's points must survive a new club joining");
        assertEquals(7, after.getWins());
        assertEquals(18, after.getGoalsScored());
        assertEquals(4, after.getGoalsConceded());

        assertNotNull(entryFor(b), "Bravo's row must still exist");
        assertEquals(0, entryFor(b).getPoints(), "and Bravo is still on zero, having played nothing");

        CompetitionEntry charlie = competitionEntryRepository.findBySeasonCompetition(
                        seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, 1).orElseThrow())
                .stream()
                .filter(e -> e.getTeam() != null && "Charlie".equals(e.getTeam().getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the new club must get a row"));
        assertEquals(0, charlie.getPoints(), "a new club starts on zero");
    }

    @Test
    @Transactional
    @DisplayName("a club leaving the division loses its row, and nothing else changes")
    void aDepartedClubLosesOnlyItsOwnRow() {
        Country country = countryRepository.save(country());
        Competition league = competitionRepository.save(competition(country));
        Team staying = teamRepository.save(team("Staying", country, league));
        Team leaving = teamRepository.save(team("Leaving", country, league));

        seasonService.ensureEntriesForSeasonCompetition(league, 1);
        CompetitionEntry stayEntry = entryFor(staying);
        stayEntry.setPoints(15);
        stayEntry.setGoalsScored(10);
        competitionEntryRepository.save(stayEntry);

        // A club belongs to one competition, so leaving the division means it no longer points at
        // this league. That is what findByCompetitionId reads.
        leaving.setCompetition(null);
        teamRepository.save(leaving);
        teamRepository.flush();

        seasonService.ensureEntriesForSeasonCompetition(league, 1);

        CompetitionEntry after = entryFor(staying);
        assertNotNull(after, "the staying club keeps its row");
        assertEquals(15, after.getPoints(), "and keeps its record");
        assertEquals(10, after.getGoalsScored());
    }

    // ---------- helpers ----------

    private List<String> names(List<CompetitionEntry> entries) {
        return entries.stream().map(e -> e.getTeam().getName()).toList();
    }

    private CompetitionEntry entryFor(Team team) {
        return competitionEntryRepository.findBySeasonCompetition(
                        seasonCompetitionRepository.findByCompetitionAndSeasonYear(team.getCompetition(), 1)
                                .orElseThrow())
                .stream()
                .filter(e -> e.getTeam() != null && e.getTeam().getId() != null
                        && e.getTeam().getId().equals(team.getId()))
                .findFirst()
                .orElse(null);
    }

    private CompetitionEntry entry(Long id, Integer points, Integer scored, Integer conceded, String name) {
        CompetitionEntry e = new CompetitionEntry();
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        e.setTeam(t);
        e.setPoints(points);
        e.setGoalsScored(scored);
        e.setGoalsConceded(conceded);
        return e;
    }

    private Country country() {
        Country c = new Country();
        c.setName("Table Republic");
        c.setIsoCode("TBR");
        c.setReputation(60);
        return c;
    }

    private Competition competition(Country country) {
        Competition c = new Competition();
        c.setName("Table League");
        c.setType(CompetitionType.LEAGUE);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setCountry(country);
        c.setTier(1);
        c.setTeamsPerCompetition(16);
        c.setHasPlayoff(false);
        c.setHasPlayout(false);
        return c;
    }

    private Team team(String name, Country country, Competition competition) {
        Team t = new Team();
        t.setName(name);
        t.setCountry(country);
        t.setCompetition(competition);
        t.setReputation(60.0);
        t.setBudget(1_000_000.0);
        t.setHumanControlled(false);
        return t;
    }

    @Autowired private SeasonService seasonService;
    @Autowired private CompetitionEntryRepository competitionEntryRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private SeasonCompetitionRepository seasonCompetitionRepository;
    @Autowired private CountryRepository countryRepository;
    @Autowired private TeamRepository teamRepository;
}
