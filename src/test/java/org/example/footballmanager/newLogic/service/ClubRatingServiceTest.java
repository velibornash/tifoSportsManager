package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A club's Elo rating, from the football it has played (owner, 2026-10-01).
 *
 * <p><b>These build their own club history</b>, for the reason {@code NationalRatingServiceTest} sets
 * out: the replay reads the match table, so a test that asserts against an empty one proves nothing
 * while looking exactly like a passing test. {@code recompute()} joins the caller's transaction, so a
 * {@code @Transactional} test can build a history the replay will actually see.
 *
 * <p><b>That is also why the committed write is a separate class.</b> Every test here asserts on the
 * entities inside the test's own transaction, where Hibernate's dirty checking would make a missing
 * {@code save()} invisible. {@code ClubRatingPersistenceTest} clears the context and re-reads, which is
 * the only shape that can catch it.
 *
 * <p>The seeded world is left alone rather than asserted on. Its match table is whatever the H2
 * profile produced, and a test that depended on it being empty would start failing the day it was not.
 *
 * <p><b>The ISO codes are hand-allocated and the reason is worth keeping.</b> Every test class in the
 * suite shares one in-memory H2 database, and
 * {@link org.example.footballmanager.newLogic.util.InternationalClubCupsQueryBudgetTest} is not
 * transactional and commits — so the countries it creates are still in the table when this class runs.
 * Two of these tests failed with a unique-constraint violation on codes this class had chosen for
 * itself, and {@code CLB}/{@code CLC} are now that class's. Check before reusing a code here.
 */
class ClubRatingServiceTest extends BaseTest {

    private static final String PREFIX = "ZZ Elo";

    @Autowired private ClubRatingService ratings;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private MatchRepository matches;
    @Autowired private CompetitionRepository competitions;

    // --- the ladder the owner specified ---

    @Test
    @Transactional
    @DisplayName("every club starts on its own division's rung: 1500, 1400, 1300, 1200, 1100")
    void clubsStartOnTheirDivisionLadder() {
        Country country = aCountry("ZZ Ladder", "CLA");
        Team[] byTier = new Team[5];
        for (int tier = 1; tier <= 5; tier++) {
            byTier[tier - 1] = aClub(country, "Ladder T" + tier, aDivision(country, "ZZ Ladder Div " + tier, tier));
        }
        // A club nobody has ever rated, to prove the replay seeds rather than only rewriting.
        Team unplayed = aClub(country, "Ladder Idle", aDivision(country, "ZZ Ladder Idle Div", 3));

        ratings.recompute();

        assertEquals(1500.0, byTier[0].getEloRating(), "tier 1 is not on the owner's 1500");
        assertEquals(1400.0, byTier[1].getEloRating(), "tier 2 is not 100 below tier 1");
        assertEquals(1300.0, byTier[2].getEloRating(), "tier 3 is not 1200 or 1400");
        assertEquals(1200.0, byTier[3].getEloRating(), "tier 4 is not on the ladder");
        assertEquals(1100.0, byTier[4].getEloRating(), "tier 5 is not on the ladder");
        assertEquals(1300.0, unplayed.getEloRating(),
                "a club that has never played must still carry its tier's rung, or the ranking tables "
                        + "cannot sort a freshly built world");
    }

    // --- the results say so in the column ---

    @Test
    @Transactional
    @DisplayName("a result moves both clubs, up for the winner and down for the loser")
    void aResultMovesBothClubs() {
        Country country = aCountry("ZZ Result", "CLD");
        Competition division = aDivision(country, "ZZ Result Div", 1);
        Team winner = aClub(country, "Result Home", division);
        Team loser = aClub(country, "Result Away", division);

        played(division, winner, loser, 3, 0, 1);

        ratings.recompute();

        assertTrue(winner.getEloRating() > 1500.0,
                "the winner is rated " + winner.getEloRating() + " after beating an equal side");
        assertTrue(loser.getEloRating() < 1500.0,
                "the loser is rated " + loser.getEloRating() + " after losing to an equal side");
    }

    @Test
    @Transactional
    @DisplayName("the delta column is the movement from the previous value, not a running total")
    void theDeltaIsRelativeToThePreviousValue() {
        Country country = aCountry("ZZ Delta", "CLE");
        Competition division = aDivision(country, "ZZ Delta Div", 1);
        Team club = aClub(country, "Delta FC", division);
        Team first = aClub(country, "Delta Opponent One", division);
        Team second = aClub(country, "Delta Opponent Two", division);

        // Win one, then lose one. A running total would read the sum; the spec is the movement from
        // the last stored value, which is what a ranking row's "+/-" has to mean.
        played(division, club, first, 2, 0, 1);
        played(division, second, club, 2, 0, 2);

        ratings.recompute();

        assertEquals(club.getEloRating() - club.getEloPreviousRating(), club.getEloDelta(), 0.0001,
                "eloDelta is not eloRating - eloPreviousRating, so the ranking tables would print a "
                        + "number that means nothing");
        assertTrue(club.getEloPreviousRating() > 1500.0,
                "the previous value should be the rating after the win, not the seed; it is "
                        + club.getEloPreviousRating());
        assertTrue(club.getEloDelta() < 0, "the last match was a defeat, so the delta should be negative");
    }

    @Test
    @Transactional
    @DisplayName("a club that has not played shows no movement rather than a missing number")
    void anUnplayedClubHasAZeroDelta() {
        Country country = aCountry("ZZ Quiet", "CLF");
        Team club = aClub(country, "Quiet FC", aDivision(country, "ZZ Quiet Div", 2));

        ratings.recompute();

        assertNotNull(club.getEloDelta(),
                "a club with no matches has no delta at all, so the ranking table renders a blank "
                        + "instead of saying nothing has happened");
        assertEquals(0.0, club.getEloDelta(), 0.0001, "an unplayed club should read as no change");
    }

    // --- the weighting the owner asked for ---

    @Test
    @Transactional
    @DisplayName("an international club cup outweighs a league match, which outweighs a domestic cup tie")
    void theCompetitionDecidesHowMuchAMatchIsWorth() {
        Country country = aCountry("ZZ Weight", "CLG");

        double leagueGain = gainFrom(aCupPlay(country, "ZZ League Case", CompetitionType.LEAGUE, CompetitionScope.NATIONAL));
        double nationalCupGain = gainFrom(aCupPlay(country, "ZZ National Cup Case", CompetitionType.CUP, CompetitionScope.NATIONAL));
        double internationalCupGain = gainFrom(aCupPlay(country, "ZZ International Case", CompetitionType.CUP, CompetitionScope.INTERNATIONAL));

        // The owner's order, which is deliberately NOT "knockouts count for more": a league match is the
        // reference point, a cup tie is a single meeting between clubs that play each other anyway, and
        // a continental cup is the heaviest club football there is. MatchValue carries the same order and
        // had it wrong once at 1.15 for cup, until a property test caught it.
        assertTrue(leagueGain > nationalCupGain,
                "a league match moved " + leagueGain + " and a cup tie " + nationalCupGain
                        + " — a rating that cannot tell league football from a knockout is averaging a "
                        + "signal and its absence");
        assertTrue(internationalCupGain > leagueGain,
                "an international club cup moved " + internationalCupGain + " against a league match's "
                        + leagueGain + " — Champions, Masters and Challenge are meant to be the heaviest "
                        + "club football there is");
    }

    @Test
    @Transactional
    @DisplayName("beating a much stronger club is worth more than beating a level one")
    void anUpsetIsWorthMoreThanARoutineWin() {
        Country country = aCountry("ZZ Upset", "CLH");

        Competition topFlight = aDivision(country, "ZZ Upset T1", 1);
        Competition bottomFlight = aDivision(country, "ZZ Upset T5", 5);
        Team giant = aClub(country, "Upset Giant", topFlight);
        Team minnow = aClub(country, "Upset Minnow", bottomFlight);
        Team rival = aClub(country, "Upset Rival", bottomFlight);

        played(topFlight, minnow, giant, 1, 0, 1);
        played(bottomFlight, minnow, rival, 1, 0, 2);

        ratings.recompute();

        double upsetGain = minnow.getEloPreviousRating() - 1100.0;
        double routineGain = minnow.getEloRating() - minnow.getEloPreviousRating();
        assertTrue(upsetGain > routineGain,
                "defeating a first-tier club gained " + Math.round(upsetGain) + " and defeating a fifth-tier "
                        + "club gained " + Math.round(routineGain) + " — a rating that cannot tell an upset "
                        + "from a routine win does not rank anything");
    }

    // --- the properties the replay depends on ---

    @Test
    @Transactional
    @DisplayName("the replay is idempotent — a second run changes nothing")
    void replayIsIdempotent() {
        Country country = aCountry("ZZ Repeat", "CLI");
        Competition division = aDivision(country, "ZZ Repeat Div", 1);
        Team home = aClub(country, "Repeat Home", division);
        Team away = aClub(country, "Repeat Away", division);
        played(division, home, away, 2, 1, 1);

        ClubRatingService.Result first = ratings.recompute();
        String afterFirst = eloColumn();
        ClubRatingService.Result second = ratings.recompute();

        assertEquals(afterFirst, eloColumn(),
                "a second replay moved the column. This runs at the end of every matchday and after "
                        + "every admin rebuild, so a drifting replay would walk the world away from its ladder");
        assertEquals(first.matchesReplayed(), second.matchesReplayed(),
                "the two runs replayed a different number of matches, so they cannot be the same computation");
    }

    @Test
    @Transactional
    @DisplayName("the replay starts from the ladder, never from the stored number")
    void theReplayIsNotARatchet() {
        Country country = aCountry("ZZ Ratchet", "CLJ");
        Competition division = aDivision(country, "ZZ Ratchet Div", 1);
        Team home = aClub(country, "Ratchet Home", division);
        Team away = aClub(country, "Ratchet Away", division);
        played(division, home, away, 3, 1, 1);

        ratings.recompute();
        String afterOne = eloColumn();
        ratings.recompute();
        ratings.recompute();

        assertEquals(afterOne, eloColumn(),
                "the column moved on repeats — a replay that began from the stored value could only "
                        + "ever push ratings further apart, and this runs on every boot-adjacent path");
    }

    @Test
    @Transactional
    @DisplayName("a national side is never given a club rating")
    void nationalSidesAreNotClubs() {
        Country country = aCountry("ZZ National", "CLK");
        Team nationalSide = new Team();
        nationalSide.setName("ZZ National Senior");
        nationalSide.setType(CompetitionTeamType.NATIONAL_TEAM);
        nationalSide = teams.save(nationalSide);
        country.setSeniorNationalTeam(nationalSide);
        countries.save(country);

        Competition division = aDivision(country, "ZZ National Div", 1);
        Team club = aClub(country, "National Case FC", division);
        played(division, club, nationalSide, 4, 0, 1);

        ratings.recompute();

        assertNull(nationalSide.getEloRating(),
                "a national side picked up a club Elo of " + nationalSide.getEloRating()
                        + " — it has no division, so there is no tier to seed it from, and a country's "
                        + "standing is NationalRatingService's business");
    }

    @Test
    @Transactional
    @DisplayName("the run reports how many clubs actually moved off their seed")
    void theRunReportsMovement() {
        Country country = aCountry("ZZ Count", "CLM");
        Competition division = aDivision(country, "ZZ Count Div", 1);
        Team home = aClub(country, "Count Home", division);
        Team away = aClub(country, "Count Away", division);
        played(division, home, away, 1, 0, 1);

        ClubRatingService.Result result = ratings.recompute();

        assertEquals(2, result.clubsMoved(),
                "exactly the two clubs that played should have moved off their seed, was "
                        + result.clubsMoved() + " out of " + result.clubsRated());
        assertTrue(result.matchesReplayed() >= 1, "no match was replayed, so this measured nothing");
        assertTrue(result.highest() >= result.lowest(),
                "the reported range is inverted: " + result.highest() + " / " + result.lowest());
    }

    // --- helpers ---

    /**
     * One club playing one match in one competition, and the rating it gained.
     *
     * <p>Deliberately a fresh country and fresh clubs per case: the cases are compared against each
     * other, so they cannot share a division, a fixture history or a rating.
     */
    private CupPlay aCupPlay(Country country, String name, CompetitionType type, CompetitionScope scope) {
        Competition competition = new Competition();
        competition.setName(name + " Competition");
        competition.setType(type);
        competition.setScope(scope);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition.setTier(1);
        competition.setCountry(country);
        competition = competitions.save(competition);

        Team winner = aClub(country, name + " Winner", aDivision(country, name + " Div", 1));
        Team loser = aClub(country, name + " Loser", aDivision(country, name + " Div B", 1));
        played(competition, winner, loser, 2, 0, 1);
        ratings.recompute();
        return new CupPlay(winner);
    }

    private record CupPlay(Team winner) {
    }

    /** How many rating points the winner of a single match took out of it. */
    private double gainFrom(CupPlay play) {
        return play.winner().getEloRating() - play.winner().getEloPreviousRating();
    }

    private Country aCountry(String name, String iso) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode(iso);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private Competition aDivision(Country country, String name, int tier) {
        Competition division = new Competition();
        division.setName(name);
        division.setType(CompetitionType.LEAGUE);
        division.setScope(CompetitionScope.NATIONAL);
        division.setTeamType(CompetitionTeamType.CLUB);
        division.setTier(tier);
        division.setDivisionLevel(1);
        division.setTeamsPerCompetition(10);
        division.setCountry(country);
        return competitions.save(division);
    }

    private Team aClub(Country country, String name, Competition division) {
        Team club = new Team();
        club.setName(name + " FC");
        club.setCountry(country);
        club.setCompetition(division);
        club.setHumanControlled(false);
        club.setReputation(50.0);
        return teams.save(club);
    }

    private void played(Competition competition, Team home, Team away,
                        int homeGoals, int awayGoals, int day) {
        Match match = new Match();
        match.setCompetition(competition);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setPlayed(true);
        match.setFinished(true);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setSeasonYear(1);
        match.setWeekNumber(day);
        match.setMatchDate(LocalDateTime.of(2026, 2, day, 20, 45));
        match.setEventJson("[]");
        match.setStatsJson("{}");
        matches.save(match);
    }

    /** Every rated club in the world, as one string, so two runs can be compared exactly. */
    private String eloColumn() {
        return teams.findAll().stream()
                .filter(team -> team.getEloRating() != null)
                .sorted(Comparator.comparing(Team::getId))
                .map(team -> team.getName() + "=" + team.getEloRating())
                .toList()
                .toString();
    }
}
