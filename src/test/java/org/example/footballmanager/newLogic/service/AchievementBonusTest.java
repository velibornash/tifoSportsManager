package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-off achievement bonuses: qualification, every further phase, and every trophy
 * ({@code P0-RANK-5}).
 *
 * <p>Every assertion is against the **exact documented amount**, not merely "something was added" — a
 * bonus that fires but pays the wrong number is the failure mode, and a test that only checks a sign
 * would pass it.
 */
class AchievementBonusTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired
    AchievementBonusService bonuses;

    @Autowired
    CompetitionRepository competitions;

    @Autowired
    MatchFixtureRepository fixtures;

    @Autowired
    MatchRepository matches;

    @Autowired
    TeamRepository teams;

    @Autowired
    CountryRepository countries;

    @Autowired
    ClubSeasonRankingPointsRepository clubLedger;

    @Autowired
    CountrySeasonRankingPointsRepository countryLedger;

    @Test
    @Transactional
    @DisplayName("reaching the tournament pays qualification plus the group stage")
    void reachingTheTournamentPaysQualificationAndTheGroups() {
        Competition wc = aTournament(NationalTeamLevel.SENIOR, "WCA");
        Country country = aCountry("Qualifier", "QAL");
        Team champion = aSide(country, "champion");
        Team alsoQualified = aSide(aCountry("Also out", "QUT"), "second");

        // The champion gets past the round of sixteen; the other side stops in the groups.
        aFixture(wc, champion, alsoQualified, NationalTournamentSchedule.ROUND_LAST_SIXTEEN, 2, 1);
        aFixture(wc, champion, aSide(aCountry("QF loser", "QFL"), "ql"), 
                NationalTournamentSchedule.ROUND_QUARTER_FINAL, 2, 0);

        bonuses.apply(SEASON);

        double championPoints = countryLedger
                .findByCountryIdAndLevelAndSeasonYear(country.getId(), NationalTeamLevel.SENIOR, SEASON)
                .orElseThrow(() -> new AssertionError("no row for the qualifier")).total();

        assertTrue(championPoints >= RankingPointsEngine.WC_QUALIFIED,
                "a nation that got past the round of sixteen must at least have the qualification bonus");
        assertTrue(championPoints > RankingPointsEngine.WC_QUALIFIED + RankingPointsEngine.WC_GROUP_STAGE,
                "and more than qualification plus the groups, because it reached a later phase: "
                        + championPoints);
    }

    @Test
    @Transactional
    @DisplayName("the bonus is exactly the ladder for the phase reached")
    void theBonusIsExactlyTheLadder() {
        Competition wc = aTournament(NationalTeamLevel.SENIOR, "WCB");
        Country country = aCountry("Finalist", "FIN");
        Team finalist = aSide(country, "finalist");
        Team r16 = aSide(aCountry("R16", "R16"), "r16");
        Team qf = aSide(aCountry("QF", "QFC"), "qf");

        aFixture(wc, finalist, r16, NationalTournamentSchedule.ROUND_LAST_SIXTEEN, 2, 1);
        aFixture(wc, finalist, qf, NationalTournamentSchedule.ROUND_QUARTER_FINAL, 2, 0);
        aFixture(wc, finalist, aSide(aCountry("SF", "SFC"), "sf"),
                NationalTournamentSchedule.ROUND_SEMI_FINAL, 2, 0);
        // Loses the final on penalties-level score, so this test is about *reaching* it. An earlier
        // version had the finalist win 2-1 here, which quietly made it the winning test as well and the
        // expected amount - computed with wonIt = false - was wrong for a reason the assertion could not
        // explain.
        aFixture(wc, finalist, aSide(aCountry("F", "FFC"), "f"),
                NationalTournamentSchedule.ROUND_FINAL, 1, 2);

        bonuses.apply(SEASON);

        double got = countryLedger
                .findByCountryIdAndLevelAndSeasonYear(country.getId(), NationalTeamLevel.SENIOR, SEASON)
                .orElseThrow().total();

        double expected = RankingPointsEngine.tournamentPhaseBonus(
                NationalTournamentSchedule.ROUND_FINAL, false,
                RankingPointsEngine.WC_QUALIFIED, RankingPointsEngine.WC_GROUP_STAGE,
                RankingPointsEngine.WC_ROUND_OF_SIXTEEN, RankingPointsEngine.WC_QUARTER_FINAL,
                RankingPointsEngine.WC_SEMI_FINAL, RankingPointsEngine.WC_FINAL,
                RankingPointsEngine.WC_WINNER);

        assertEquals(expected, got, 0.01,
                "a finalist pays exactly qualification + groups + R16 + QF + SF + final and nothing else");
        assertEquals(RankingPointsEngine.WC_QUALIFIED + RankingPointsEngine.WC_GROUP_STAGE
                        + RankingPointsEngine.WC_ROUND_OF_SIXTEEN + RankingPointsEngine.WC_QUARTER_FINAL
                        + RankingPointsEngine.WC_SEMI_FINAL + RankingPointsEngine.WC_FINAL,
                got, 0.01, "spelled out so the ladder is readable in the test too");
    }

    @Test
    @Transactional
    @DisplayName("winning outright pays the winner's bonus on top of reaching the final")
    void winningPaysMoreThanReachingTheFinal() {
        Competition wc = aTournament(NationalTeamLevel.SENIOR, "WCC");
        Country champion = aCountry("Champion", "CHM");
        Team winner = aSide(champion, "winner");
        Team beaten = aSide(aCountry("Beaten", "BTN"), "beaten");

        aFixture(wc, winner, beaten, NationalTournamentSchedule.ROUND_FINAL, 3, 1);

        bonuses.apply(SEASON);

        double got = countryLedger
                .findByCountryIdAndLevelAndSeasonYear(champion.getId(), NationalTeamLevel.SENIOR, SEASON)
                .orElseThrow().total();

        double reachedOnly = RankingPointsEngine.tournamentPhaseBonus(
                NationalTournamentSchedule.ROUND_FINAL, false,
                RankingPointsEngine.WC_QUALIFIED, RankingPointsEngine.WC_GROUP_STAGE,
                RankingPointsEngine.WC_ROUND_OF_SIXTEEN, RankingPointsEngine.WC_QUARTER_FINAL,
                RankingPointsEngine.WC_SEMI_FINAL, RankingPointsEngine.WC_FINAL,
                RankingPointsEngine.WC_WINNER);

        assertEquals(reachedOnly + RankingPointsEngine.WC_WINNER, got, 0.01,
                "winning is worth the final AND the trophy, because both happened");
        assertTrue(got > reachedOnly);
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 tournaments are read from separate competitions and never pooled")
    void levelsAreNotPooled() {
        Competition seniorWc = aTournament(NationalTeamLevel.SENIOR, "WCS");
        Competition youthWc = aTournament(NationalTeamLevel.U21, "WCU");
        Country country = aCountry("Both levels", "BTH");

        Team seniorSide = aSide(country, "senior");
        Team youthSide = new Team();
        youthSide.setName("youth " + UUID.randomUUID());
        youthSide.setFormation("4-3-3");
        youthSide.setType(CompetitionTeamType.NATIONAL_TEAM);
        youthSide = teams.save(youthSide);
        country.setU21NationalTeam(youthSide);
        countries.save(country);

        Team seniorRival = aSide(aCountry("Senior rival", "SVR"), "sr");
        Team youthRival = new Team();
        youthRival.setName("youth rival " + UUID.randomUUID());
        youthRival.setType(CompetitionTeamType.NATIONAL_TEAM);
        youthRival = teams.save(youthRival);
        aCountry("Youth rival holder", "YRH").setU21NationalTeam(youthRival);
        countries.save(countries.findByIsoCode("YRH").orElseThrow());

        aFixture(seniorWc, seniorSide, seniorRival, NationalTournamentSchedule.ROUND_FINAL, 3, 1);
        aFixture(youthWc, youthSide, youthRival, NationalTournamentSchedule.ROUND_LAST_SIXTEEN, 1, 0);

        bonuses.apply(SEASON);

        double seniorPoints = countryLedger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.SENIOR, SEASON).orElseThrow().total();
        double youthPoints = countryLedger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.U21, SEASON).orElseThrow().total();

        assertTrue(seniorPoints > youthPoints,
                "the senior side reached the final and won, the U-21 side went out in the last sixteen, "
                        + "so they must not be equal: senior " + seniorPoints + " youth " + youthPoints);
    }

    @Test
    @Transactional
    @DisplayName("a national cup is tier-weighted: a third-division title is worth less than a top-flight one")
    void clubBonusesAreTierWeighted() {
        // Two coherent knockouts, one per division.
        //
        // The first version of this ran two independent round-one ties in ONE cup and called both clubs
        // winners. That is not a knockout, and it exposed the rule doing its job: there is exactly one
        // champion per competition, so one of the two clubs was correctly paid nothing and the test read
        // that as a missing row rather than as the answer.
        Competition topCup = aCup();
        Competition thirdCup = aCup();

        Competition topFlight = aDivision(1);
        Competition third = aDivision(3);

        Team topWinner = aClub(topFlight, "top winner");
        Team topFinalist = aClub(topFlight, "top finalist");
        aFixture(topCup, topWinner, aClub(topFlight, "top semi loser"), 1, 2, 1);
        aFixture(topCup, topWinner, topFinalist, 2, 2, 0);

        Team thirdWinner = aClub(third, "third winner");
        Team thirdFinalist = aClub(third, "third finalist");
        aFixture(thirdCup, thirdWinner, aClub(third, "third semi loser"), 1, 2, 1);
        aFixture(thirdCup, thirdWinner, thirdFinalist, 2, 2, 0);

        bonuses.apply(SEASON);

        double top = clubLedger.findByTeamIdAndSeasonYear(topWinner.getId(), SEASON)
                .orElseThrow(() -> new AssertionError("the tier-1 champion has no row")).getBonusPoints();
        double bottom = clubLedger.findByTeamIdAndSeasonYear(thirdWinner.getId(), SEASON)
                .orElseThrow(() -> new AssertionError("the tier-3 champion has no row")).getBonusPoints();

        assertEquals(RankingPointsEngine.NATIONAL_CUP_WINNER, top, 0.01,
                "a tier-1 cup win pays the full amount");
        assertEquals(RankingPointsEngine.clubBonus(RankingPointsEngine.NATIONAL_CUP_WINNER, 3),
                bottom, 0.01, "and a tier-3 one pays it times 0.70");
        assertTrue(top > bottom, "35 against 24.5: the division is part of the achievement");
    }

    @Test
    @Transactional
    @DisplayName("a finalist that loses the final is paid for reaching it and not for winning it")
    void aLosingFinalistIsPaidForReachingTheFinal() {
        Competition cup = aCup();
        Team winner = aClub(aDivision(1), "champ");
        Team loser = aClub(aDivision(1), "loser");
        aFixture(cup, winner, aClub(aDivision(1), "semi loser"), 1, 2, 1);
        // Home goals first: 2-1 here is the CHAMPION winning. An earlier version wrote 1-2, which is the
        // away side winning, so the "loser" won the final and was correctly paid the trophy.
        aFixture(cup, winner, loser, 2, 2, 1);

        bonuses.apply(SEASON);

        // No row at all, which is the honest outcome: a club that reached the final of a domestic cup and
        // lost it has no bonus to hold. An earlier version read it with orElseThrow and reported the
        // missing row as a failure, when "no row" is the answer.
        assertTrue(clubLedger.findByTeamIdAndSeasonYear(loser.getId(), SEASON).isEmpty(),
                "a losing finalist holds no bonus, so there is nothing for the window to read");
        double winnerBonus = clubLedger.findByTeamIdAndSeasonYear(winner.getId(), SEASON)
                .orElseThrow().getBonusPoints();

        assertEquals(RankingPointsEngine.NATIONAL_CUP_WINNER, winnerBonus, 0.01);
    }

    private Competition aCup() {
        Competition cup = new Competition();
        cup.setName("Cup " + UUID.randomUUID());
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup.setTier(1);
        return competitions.save(cup);
    }

    @Test
    @Transactional
    @DisplayName("applying twice does not pay the trophy twice")
    void theBonusIsIdempotentPerSeason() {
        Competition wc = aTournament(NationalTeamLevel.SENIOR, "WCI");
        Country country = aCountry("Twice", "TWC");
        Team winner = aSide(country, "winner");
        Team beaten = aSide(aCountry("Twice beaten", "TWB"), "beaten");
        aFixture(wc, winner, beaten, NationalTournamentSchedule.ROUND_FINAL, 3, 1);

        bonuses.apply(SEASON);
        double first = countryLedger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.SENIOR, SEASON).orElseThrow().total();
        bonuses.apply(SEASON);
        double second = countryLedger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.SENIOR, SEASON).orElseThrow().total();

        assertEquals(first, second, 0.01,
                "a button that can be pressed twice must not pay two World Cups. Got " + first
                        + " then " + second);
    }

    @Test
    @Transactional
    @DisplayName("an unplayed final pays nobody the trophy bonus")
    void anUnplayedFinalPaysNoTrophy() {
        Competition wc = aTournament(NationalTeamLevel.SENIOR, "WCU2");
        Country country = aCountry("Unplayed", "UNP");
        Team side = aSide(country, "side");
        Team other = aSide(aCountry("Unplayed other", "UNO"), "other");
        aFixtureUnplayed(wc, side, other, NationalTournamentSchedule.ROUND_FINAL);

        bonuses.apply(SEASON);

        double got = countryLedger.findByCountryIdAndLevelAndSeasonYear(
                        country.getId(), NationalTeamLevel.SENIOR, SEASON)
                .map(CountrySeasonRankingPoints::total).orElse(0.0);
        double reachedOnly = RankingPointsEngine.tournamentPhaseBonus(
                NationalTournamentSchedule.ROUND_FINAL, false,
                RankingPointsEngine.WC_QUALIFIED, RankingPointsEngine.WC_GROUP_STAGE,
                RankingPointsEngine.WC_ROUND_OF_SIXTEEN, RankingPointsEngine.WC_QUARTER_FINAL,
                RankingPointsEngine.WC_SEMI_FINAL, RankingPointsEngine.WC_FINAL,
                RankingPointsEngine.WC_WINNER);

        assertEquals(reachedOnly, got, 0.01,
                "reaching a final that has not been played is worth reaching it, and no trophy");
    }

    @Test
    @Transactional
    @DisplayName("the bonus adds to the per-match points already in the season")
    void theBonusAddsToTheSeasonItDoesNotReplaceIt() {
        Competition wc = aTournament(NationalTeamLevel.SENIOR, "WCA2");
        Country country = aCountry("Both", "BT2");
        Team side = aSide(country, "side");
        Team other = aSide(aCountry("Both other", "BTO"), "other");

        // The replay's row exists first: this season already has match points in it.
        countryLedger.save(new CountrySeasonRankingPoints(country, NationalTeamLevel.SENIOR, SEASON, 55.5));
        aFixture(wc, side, other, NationalTournamentSchedule.ROUND_FINAL, 3, 1);

        bonuses.apply(SEASON);

        double got = countryLedger.findByCountryIdAndLevelAndSeasonYear(
                        country.getId(), NationalTeamLevel.SENIOR, SEASON).orElseThrow().total();

        double trophyOnly = RankingPointsEngine.tournamentPhaseBonus(
                NationalTournamentSchedule.ROUND_FINAL, true,
                RankingPointsEngine.WC_QUALIFIED, RankingPointsEngine.WC_GROUP_STAGE,
                RankingPointsEngine.WC_ROUND_OF_SIXTEEN, RankingPointsEngine.WC_QUARTER_FINAL,
                RankingPointsEngine.WC_SEMI_FINAL, RankingPointsEngine.WC_FINAL,
                RankingPointsEngine.WC_WINNER);

        assertEquals(55.5 + trophyOnly, got, 0.01,
                "a club that won a game and won the cup has earned both; the bonus adds, and the "
                        + "ledger's one-row-per-season is what lets it");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────

    private Competition aTournament(NationalTeamLevel level, String iso) {
        Competition tournament = new Competition();
        tournament.setName("WC " + iso + " " + UUID.randomUUID());
        tournament.setType(CompetitionType.TOURNAMENT);
        tournament.setScope(CompetitionScope.INTERNATIONAL);
        tournament.setTeamType(CompetitionTeamType.NATIONAL_TEAM);
        tournament.setNationalLevel(level);
        tournament.setNationalStage(NationalStage.WORLD_CUP);
        return competitions.save(tournament);
    }

    private Competition aDivision(int tier) {
        Competition division = new Competition();
        division.setName("Division " + tier + " " + UUID.randomUUID());
        division.setType(CompetitionType.LEAGUE);
        division.setScope(CompetitionScope.NATIONAL);
        division.setTeamType(CompetitionTeamType.CLUB);
        division.setTier(tier);
        return competitions.save(division);
    }

    private Team aClub(Competition division, String name) {
        Team club = new Team();
        club.setName(name + " " + UUID.randomUUID());
        club.setFormation("4-4-2");
        club = teams.save(club);
        club.setCompetition(division);
        return teams.save(club);
    }

    private Team aSide(Country country, String name) {
        Team side = new Team();
        side.setName(name + " " + UUID.randomUUID());
        side.setFormation("4-3-3");
        side.setType(CompetitionTeamType.NATIONAL_TEAM);
        side = teams.save(side);
        country.setSeniorNationalTeam(side);
        countries.save(country);
        return side;
    }

    private Country aCountry(String name, String iso) {
        Country country = new Country();
        country.setName(name + " " + UUID.randomUUID());
        country.setIsoCode(iso);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private void aFixture(Competition competition, Team home, Team away, int round,
                          int homeGoals, int awayGoals) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setCompetition(competition);
        match.setSeasonYear(SEASON);
        match.setPlayed(true);
        match.setMatchDate(java.time.LocalDateTime.of(2026, 2, 1, 18, 0));
        match = matches.save(match);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setRoundNumber(round);
        fixture.setSeasonYear(SEASON);
        fixture.setPlayed(true);
        fixture.setPlayedMatch(match);
        fixture.setMatchDate(match.getMatchDate());
        fixtures.save(fixture);
    }

    private void aFixtureUnplayed(Competition competition, Team home, Team away, int round) {
        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setRoundNumber(round);
        fixture.setSeasonYear(SEASON);
        fixture.setPlayed(false);
        fixture.setMatchDate(java.time.LocalDateTime.of(2026, 2, 1, 18, 0));
        fixtures.save(fixture);
    }
}