package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * National matches are replayed into ranking points, per season and per level (P0-RANK-3).
 *
 * <p>The senior/U-21 separation is the point of this class, and it is enforced by a guard rather than by
 * convention: a senior side appearing in a U-21 fixture is a corrupt world, and scoring it would add two
 * countries' points into one total.
 */
class NationalRankingPointsReplayTest extends BaseTest {

    @Autowired
    NationalRankingPointsService replay;

    @Autowired
    CountrySeasonRankingPointsRepository ledger;

    @Autowired
    CountryRepository countries;

    @Autowired
    TeamRepository teams;

    @Autowired
    PlayerRepository players;

    @Autowired
    CompetitionRepository competitions;

    @Autowired
    MatchRepository matches;

    @Autowired
    ScheduleInsightService insights;

    @Test
    @Transactional
    @DisplayName("the ledger holds exactly the forecast formula for a national side too")
    void theLedgerHoldsTheForecastFormula() {
        Competition wc = aCompetition("WC", NationalTeamLevel.SENIOR, NationalStage.WORLD_CUP);
        Team home = aSide("NT home " + UUID.randomUUID(), 90);
        Team away = aSide("NT away " + UUID.randomUUID(), 55);
        Country homeCountry = aCountry(home, null, "NTH " + UUID.randomUUID());
        Country awayCountry = aCountry(away, null, "NTA " + UUID.randomUUID());

        aPlayedMatch(home, away, 6, 0, wc, 1);

        var result = replay.recompute();
        assertEquals(1, result.matchesScored(), "the match must be scored: " + result);

        double written = ledger
                .findByCountryIdAndLevelAndSeasonYear(homeCountry.getId(), NationalTeamLevel.SENIOR, 1)
                .orElseThrow(() -> new AssertionError("no senior row for the home country"))
                .getPoints();

        var prediction = insights.buildFixtureInsights(home, away).prediction();
        double expected = RankingPointsEngine.pointsFor(
                prediction.expectedHomeGoals() - prediction.expectedAwayGoals(), 6,
                RankingPointsEngine.NATIONAL_TOURNAMENT, RankingPointsEngine.TIER_1);

        assertEquals(expected, written, 0.01,
                "a World Cup match is worth the tournament value; got " + written + " expected " + expected);
        assertTrue(written > 0, "a 6-0 over a 55-rated side must earn points");
    }

    @Test
    @Transactional
    @DisplayName("a qualifying match is worth less than a tournament one, for an identical result")
    void qualifyingIsWorthLessThanATournament() {
        Competition qualifying = aCompetition("QUAL", NationalTeamLevel.SENIOR, NationalStage.QUALIFYING);
        Competition tournament = aCompetition("WC2", NationalTeamLevel.SENIOR, NationalStage.WORLD_CUP);

        Team strongA = aSide("Qual strong " + UUID.randomUUID(), 92);
        Team weakA = aSide("Qual weak " + UUID.randomUUID(), 50);
        Country qualWinner = aCountry(strongA, null, "QA " + UUID.randomUUID());
        aCountry(weakA, null, "QB " + UUID.randomUUID());
        aPlayedMatch(strongA, weakA, 6, 0, qualifying, 1);
        replay.recompute();
        double fromQualifying = ledger
                .findByCountryIdAndLevelAndSeasonYear(qualWinner.getId(), NationalTeamLevel.SENIOR, 1)
                .orElseThrow().getPoints();

        Team strongB = aSide("Tour strong " + UUID.randomUUID(), 92);
        Team weakB = aSide("Tour weak " + UUID.randomUUID(), 50);
        Country tourWinner = aCountry(strongB, null, "TA " + UUID.randomUUID());
        aCountry(weakB, null, "TB " + UUID.randomUUID());
        aPlayedMatch(strongB, weakB, 6, 0, tournament, 1);
        replay.recompute();
        double fromTournament = ledger
                .findByCountryIdAndLevelAndSeasonYear(tourWinner.getId(), NationalTeamLevel.SENIOR, 1)
                .orElseThrow().getPoints();

        assertTrue(fromTournament > fromQualifying,
                "the same 6-0 in the finals must beat the same 6-0 in qualifying: "
                        + fromTournament + " vs " + fromQualifying);
        assertEquals(fromQualifying * RankingPointsEngine.NATIONAL_TOURNAMENT
                        / RankingPointsEngine.NATIONAL_QUALIFYING,
                fromTournament, 0.01,
                "and by exactly the ratio of the two competition values, since everything else matches");
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 are two totals and a match only feeds its own level")
    void seniorAndYouthAreSeparateTotals() {
        Competition seniorQual = aCompetition("SQ", NationalTeamLevel.SENIOR, NationalStage.QUALIFYING);
        Competition youthQual = aCompetition("UQ", NationalTeamLevel.U21, NationalStage.QUALIFYING);

        Country country = aCountry(null, null, "SPLIT " + UUID.randomUUID());
        Team senior = aSide("Split senior " + UUID.randomUUID(), 88);
        Team youth = aSide("Split youth " + UUID.randomUUID(), 88);
        Team seniorRival = aSide("Split senior rival " + UUID.randomUUID(), 55);
        Team youthRival = aSide("Split youth rival " + UUID.randomUUID(), 55);

        country.setSeniorNationalTeam(senior);
        country.setU21NationalTeam(youth);
        country = countries.save(country);
        aCountry(seniorRival, null, "SPR " + UUID.randomUUID());
        // The U-21 rival must be registered as a U-21 side. The replay's level guard refuses to score a
        // senior side in a U-21 fixture, and did refuse when this line said senior.
        aCountry(null, youthRival, "UPR " + UUID.randomUUID());

        aPlayedMatch(senior, seniorRival, 6, 0, seniorQual, 1);
        aPlayedMatch(youth, youthRival, 6, 0, youthQual, 1);

        replay.recompute();

        double seniorTotal = ledger
                .findByCountryIdAndLevelAndSeasonYear(country.getId(), NationalTeamLevel.SENIOR, 1)
                .orElseThrow(() -> new AssertionError("no senior row")).getPoints();
        double youthTotal = ledger
                .findByCountryIdAndLevelAndSeasonYear(country.getId(), NationalTeamLevel.U21, 1)
                .orElseThrow(() -> new AssertionError("no U-21 row")).getPoints();

        assertTrue(seniorTotal > 0 && youthTotal > 0,
                "both sides scored: senior " + seniorTotal + ", youth " + youthTotal);
        assertEquals(seniorTotal, youthTotal, 0.01,
                "identical sides and identical results score identically - which also proves neither "
                        + "match was booked against the wrong level");
    }

    @Test
    @Transactional
    @DisplayName("the replay is idempotent")
    void theReplayIsIdempotent() {
        Competition qualifying = aCompetition("QIDEM", NationalTeamLevel.SENIOR, NationalStage.QUALIFYING);
        Team home = aSide("Idem home " + UUID.randomUUID(), 90);
        Team away = aSide("Idem away " + UUID.randomUUID(), 60);
        Country homeCountry = aCountry(home, null, "IH " + UUID.randomUUID());
        aCountry(away, null, "IA " + UUID.randomUUID());
        aPlayedMatch(home, away, 4, 0, qualifying, 2);

        replay.recompute();
        double first = ledger.findByCountryIdAndLevelAndSeasonYear(
                homeCountry.getId(), NationalTeamLevel.SENIOR, 2).orElseThrow().getPoints();
        replay.recompute();
        double second = ledger.findByCountryIdAndLevelAndSeasonYear(
                homeCountry.getId(), NationalTeamLevel.SENIOR, 2).orElseThrow().getPoints();

        assertEquals(1, ledger.findByCountryIdAndLevel(homeCountry.getId(), NationalTeamLevel.SENIOR).size(),
                "two rows for one country, level and season would be added together by the window");
        assertEquals(first, second, 0.01, "the replay is a rebuild, not an accumulation");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────

    private Competition aCompetition(String name, NationalTeamLevel level, NationalStage stage) {
        Competition competition = new Competition();
        competition.setName(name + " " + UUID.randomUUID());
        competition.setType(CompetitionType.TOURNAMENT);
        competition.setScope(CompetitionScope.INTERNATIONAL);
        competition.setTeamType(CompetitionTeamType.NATIONAL_TEAM);
        competition.setNationalLevel(level);
        competition.setNationalStage(stage);
        return competitions.save(competition);
    }

    private Team aSide(String name, int strength) {
        Team team = new Team();
        team.setName(name);
        team.setFormation("4-3-3");
        team.setType(CompetitionTeamType.NATIONAL_TEAM);
        team = teams.save(team);
        for (int i = 0; i < 11; i++) {
            Player player = new Player();
            player.setName(name + " p" + i);
            player.setTeam(team);
            player.setPosition(i == 0 ? Position.GK : Position.MID);
            player.setRating(strength);
            player.setAge(21);
            players.save(player);
        }
        return team;
    }

    private Country aCountry(Team senior, Team youth, String name) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode(name.substring(0, 1) + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country.setSeniorNationalTeam(senior);
        country.setU21NationalTeam(youth);
        return countries.save(country);
    }

    private void aPlayedMatch(Team home, Team away, int homeGoals, int awayGoals,
                              Competition competition, int season) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setCompetition(competition);
        match.setSeasonYear(season);
        match.setPlayed(true);
        match.setMatchDate(java.time.LocalDateTime.of(2026, 1, 1, 20, 0).plusDays(season * 7L));
        matches.save(match);
    }
}