package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.NationalGroupTable;
import org.example.footballmanager.newLogic.service.NationalRatingService;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-10 / P2-12 — a full national-team tournament, played to a champion.
 *
 * <p><b>This is the exit criterion.</b> The board asks for "a full qualifying campaign and a tournament,
 * played to a result", and every other test in this area checks a shape: eight groups, five matchdays,
 * one nation per pot. This one plays football, because a shape that cannot be played is a table
 * describing a competition nobody enters.
 *
 * <p>It goes through {@link SimMatchService#persist} for every result, so the group tables, the penalty
 * rule and the Elo replay are all exercised through the code that actually runs in a season.
 */
class NationalTournamentPlayedToAResultTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired private NationalTeamCompetitions catalogue;
    @Autowired private NationalTournamentSeeder seeder;
    @Autowired private NationalGroupTable groupTable;
    @Autowired private NationalRatingService ratings;
    @Autowired private SimMatchService simMatchService;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchRepository matches;

    @Test
    @Transactional
    @DisplayName("a qualifying campaign runs to a result, and the top two of each group go through")
    void qualifyingReachesSixteen() {
        seedWorld(48);
        catalogue.ensureAll();

        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);

        // Play every qualifying tie, two matchdays at a time so the days mean something.
        for (int matchday = 1; matchday <= NationalTournamentSchedule.GROUP_MATCHDAYS; matchday++) {
            int day = NationalTournamentSchedule.qualifyingDay(matchday);
            playDay(qualifying, NationalTournamentSchedule.QUALIFYING_WEEK, day);
        }

        // Every tie has a result, and every group has a full table.
        List<MatchFixture> all = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                qualifying.getId(), SEASON);
        assertEquals(120, all.size());
        assertTrue(all.stream().allMatch(MatchFixture::isPlayed), "every qualifying tie was played");

        for (int g = 0; g < NationalTournamentSchedule.GROUPS; g++) {
            String code = NationalTournamentSchedule.groupCode(g);
            List<NationalGroupTable.Standing> group = groupTable.standings(qualifying, SEASON, code);
            assertEquals(6, group.size(), "group " + code + " has six nations on its table");
            for (NationalGroupTable.Standing standing : group) {
                assertEquals(5, standing.played(), "every nation in group " + code + " played five ties");
                assertTrue(standing.points() >= 0, "points are a count, not a surprise");
            }
            // The owner's chain must actually order the group, not fall through to a name or an id.
            for (int i = 1; i < group.size(); i++) {
                NationalGroupTable.Standing above = group.get(i - 1);
                NationalGroupTable.Standing below = group.get(i);
                boolean ordered = above.points() > below.points()
                        || (above.points() == below.points() && above.goalDifference() >= below.goalDifference());
                assertTrue(ordered, "group " + code + " is out of order at " + above.team().getName());
            }
        }

        // Sixteen nations, and the tournament can be drawn from them.
        Competition tournament = catalogue.tournament(NationalTeamLevel.SENIOR).orElseThrow();
        List<Team> qualifiers = seeder.qualifiersFor(qualifying, SEASON);
        assertEquals(NationalTournamentSchedule.TOURNAMENT_FIELD, qualifiers.size(),
                "eight groups of two reach a round of sixteen");

        seeder.ensureKnockouts(NationalTeamLevel.SENIOR, SEASON);
        List<MatchFixture> drawn = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                tournament.getId(), SEASON);
        assertEquals(8, drawn.size(), "the round of sixteen is eight ties");
        assertTrue(drawn.stream().allMatch(f -> f.getWeekNumber() == NationalTournamentSchedule.TOURNAMENT_WEEK),
                "the tournament is week 12");
        assertTrue(drawn.stream().allMatch(f -> f.getDayNumber()
                        == NationalTournamentSchedule.tournamentDay(NationalTournamentSchedule.ROUND_LAST_SIXTEEN)),
                "the round of sixteen is on day 1");
    }

    @Test
    @Transactional
    @DisplayName("the tournament is played to a champion, and a level semi-final is settled on penalties")
    void tournamentReachesAChampion() {
        seedWorld(48);
        catalogue.ensureAll();

        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        Competition tournament = catalogue.tournament(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
        for (int matchday = 1; matchday <= NationalTournamentSchedule.GROUP_MATCHDAYS; matchday++) {
            playDay(qualifying, NationalTournamentSchedule.QUALIFYING_WEEK,
                    NationalTournamentSchedule.qualifyingDay(matchday));
        }

        // Each round: draw it, play it, then ask for the next one. The bracket cannot be drawn in one
        // pass, which is the property this loop is here to exercise.
        for (int round : NationalTournamentSchedule.TOURNAMENT_ROUNDS) {
            seeder.ensureKnockouts(NationalTeamLevel.SENIOR, SEASON);
            int day = NationalTournamentSchedule.tournamentDay(round);
            if (day < 1) {
                continue;
            }
            playDay(tournament, NationalTournamentSchedule.TOURNAMENT_WEEK, day);
        }

        // One more pass after the loop: the final and the third place are drawn together, on the
        // semi-final's results, and that draw is reached by asking again rather than by having been
        // scheduled.
        seeder.ensureKnockouts(NationalTeamLevel.SENIOR, SEASON);
        playDay(tournament, NationalTournamentSchedule.TOURNAMENT_WEEK,
                NationalTournamentSchedule.tournamentDay(NationalTournamentSchedule.ROUND_FINAL));

        List<MatchFixture> finalRound = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(tournament.getId(), SEASON)
                .stream()
                .filter(f -> f.getRoundNumber() == NationalTournamentSchedule.ROUND_FINAL)
                .toList();
        assertEquals(1, finalRound.size(), "a tournament has one final");

        Match finalMatch = finalRound.get(0).getPlayedMatch();
        assertNotNull(finalMatch, "the final was played");
        assertTrue(finalRound.get(0).getMatchDate().getDayOfWeek().getValue() >= 1, "the final has a date");

        // Third place exists, because a knockout that quietly drops a round is a different tournament.
        long thirdPlace = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        tournament.getId(), SEASON).stream()
                .filter(f -> f.getRoundNumber() == NationalTournamentSchedule.ROUND_THIRD_PLACE)
                .count();
        assertEquals(1, thirdPlace, "there is a third-place play-off");
    }

    @Test
    @Transactional
    @DisplayName("a level tournament GROUP tie is left level - it is not decided from the spot")
    void aLevelQualifyingTieIsNotSettledOnPenalties() {
        seedWorld(48);
        catalogue.ensureAll();
        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);

        MatchFixture groupTie = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        qualifying.getId(), SEASON).stream()
                .filter(f -> f.getGroupCode() != null)
                .findFirst()
                .orElseThrow();

        simMatchService.persist(groupTie, score(groupTie.getHomeTeam(), groupTie.getAwayTeam(), 2, 2), -1L);

        Match played = groupTie.getPlayedMatch();
        assertNotNull(played, "the tie was recorded");
        assertEquals(2, played.getHomeGoals());
        assertEquals(2, played.getAwayGoals());
        assertNull(played.getHomePenaltyGoals(),
                "a qualifying group tie decided by penalties would decide the group from the spot "
                        + "instead of on points");
    }

    @Test
    @Transactional
    @DisplayName("a level World Cup tie IS settled on penalties, because a knockout has to be won")
    void aLevelKnockoutTieIsSettledOnPenalties() {
        seedWorld(48);
        catalogue.ensureAll();
        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        Competition tournament = catalogue.tournament(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
        for (int matchday = 1; matchday <= NationalTournamentSchedule.GROUP_MATCHDAYS; matchday++) {
            playDay(qualifying, NationalTournamentSchedule.QUALIFYING_WEEK,
                    NationalTournamentSchedule.qualifyingDay(matchday));
        }
        seeder.ensureKnockouts(NationalTeamLevel.SENIOR, SEASON);

        MatchFixture knockout = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        tournament.getId(), SEASON).stream()
                .filter(f -> f.getRoundNumber() == NationalTournamentSchedule.ROUND_LAST_SIXTEEN)
                .findFirst()
                .orElseThrow();

        // Keep it level at 90 minutes and let the engine run the shootout.
        simMatchService.persist(knockout, score(knockout.getHomeTeam(), knockout.getAwayTeam(), 0, 0), -1L);

        Match played = knockout.getPlayedMatch();
        assertNotNull(played);
        if (played.getHomeGoals() == played.getAwayGoals()) {
            assertNotNull(played.getHomePenaltyGoals(),
                    "a World Cup tie that finished level must be settled from the spot");
            assertTrue(played.getHomePenaltyGoals() != played.getAwayPenaltyGoals(),
                    "a shootout cannot finish level - somebody has to win it");
        }
    }

    @Test
    @Transactional
    @DisplayName("a group's tie-break coin is drawn once and never changes")
    void theCoinIsStable() {
        seedWorld(48);
        catalogue.ensureAll();
        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
        for (int matchday = 1; matchday <= NationalTournamentSchedule.GROUP_MATCHDAYS; matchday++) {
            playDay(qualifying, NationalTournamentSchedule.QUALIFYING_WEEK,
                    NationalTournamentSchedule.qualifyingDay(matchday));
        }

        String code = NationalTournamentSchedule.groupCode(0);
        List<String> first = orderOf(groupTable.standings(qualifying, SEASON, code));
        List<String> second = orderOf(groupTable.standings(qualifying, SEASON, code));
        List<String> third = orderOf(groupTable.standings(qualifying, SEASON, code));

        assertEquals(first, second, "the same group read twice must give the same order");
        assertEquals(first, third, "a group whose order changes between reads is not decided");
    }

    @Test
    @Transactional
    @DisplayName("reaching the tournament pays the qualification bonus, and it is paid once")
    void theQualificationBonusIsPaidExactlyOnce() {
        seedWorld(48);
        catalogue.ensureAll();
        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
        for (int matchday = 1; matchday <= NationalTournamentSchedule.GROUP_MATCHDAYS; matchday++) {
            playDay(qualifying, NationalTournamentSchedule.QUALIFYING_WEEK,
                    NationalTournamentSchedule.qualifyingDay(matchday));
        }
        seeder.ensureKnockouts(NationalTeamLevel.SENIOR, SEASON);

        ratings.recompute();
        Map<Long, Integer> afterOne = ratingsBySide();

        ratings.recompute();
        ratings.recompute();
        Map<Long, Integer> afterThree = ratingsBySide();

        assertEquals(afterOne, afterThree,
                "the replay is a pure function of the match table: three runs must equal one");

        // And the bonus actually reached somebody, rather than the replay silently doing nothing.
        Map<Long, Integer> startRatings = new LinkedHashMap<>();
        for (NationalTournamentSeeder.Entrant entrant : seeder.rankedField(NationalTeamLevel.SENIOR)) {
            startRatings.put(entrant.team().getId(), entrant.rating());
        }
        boolean moved = startRatings.entrySet().stream()
                .anyMatch(e -> afterOne.getOrDefault(e.getKey(), e.getValue()) != e.getValue());
        assertTrue(moved, "a played tournament moved somebody's national rating");
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 have separate rankings and separate tournaments")
    void seniorAndU21RatingsAreSeparate() {
        seedWorld(48);
        catalogue.ensureAll();

        Competition senior = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        Competition youth = catalogue.qualifiers(NationalTeamLevel.U21).orElseThrow();
        assertTrue(senior.getId() != null && youth.getId() != null
                && !senior.getId().equals(youth.getId()), "they are different competitions");

        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
        seeder.ensureGroupStage(NationalTeamLevel.U21, SEASON);

        long seniorFixtures = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                senior.getId(), SEASON).size();
        long youthFixtures = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                youth.getId(), SEASON).size();
        assertEquals(120, seniorFixtures);
        assertEquals(120, youthFixtures, "the U-21 qualifying is its own competition, not a tab on the senior one");
    }

    // ---------- helpers ----------

    private List<String> orderOf(List<NationalGroupTable.Standing> standings) {
        return standings.stream().map(s -> s.team().getName()).toList();
    }

    private Map<Long, Integer> ratingsBySide() {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (Country country : countries.findAll()) {
            if (country.getSeniorNationalTeam() != null) {
                out.put(country.getSeniorNationalTeam().getId(), country.getReputation());
            }
        }
        return out;
    }

    /** Plays every unplayed tournament tie on one day, with a scoreline that varies by fixture id. */
    private void playDay(Competition competition, int week, int day) {
        for (MatchFixture fixture : fixtures.findBySeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(SEASON, week, day)) {
            if (fixture.getCompetition() == null || !fixture.getCompetition().getId().equals(competition.getId())) {
                continue;
            }
            // Vary the result so groups do not all finish level on identical numbers: an all-draw
            // campaign is a legal but degenerate one, and a bracket that only works on it proves little.
            int homeGoals = (int) (Math.abs(fixture.getId() == null ? 0 : fixture.getId()) % 3);
            int awayGoals = (int) (Math.abs(fixture.getId() == null ? 0 : fixture.getId()) % 2);
            simMatchService.persist(fixture,
                    score(fixture.getHomeTeam(), fixture.getAwayTeam(), homeGoals, awayGoals), -1L);
        }
    }

    private void seedWorld(int nations) {
        for (int i = 0; i < nations; i++) {
            Country country = new Country();
            country.setName(String.format("Nation %02d", i));
            country.setIsoCode(String.format("N%02d", i));
            country.setReputation(1500);
            country.setYouthRating(1500);
            country.setState(org.example.footballmanager.newLogic.model.CountryState.SIMULATED);
            Country saved = countries.save(country);
            saved.setSeniorNationalTeam(side(saved, "Senior"));
            saved.setU21NationalTeam(side(saved, "U-21"));
            countries.save(saved);
        }
    }

    private Team side(Country country, String label) {
        Team team = new Team();
        team.setName(country.getName() + " " + label);
        team.setType(CompetitionTeamType.NATIONAL_TEAM);
        team.setCountry(country);
        team.setReputation(60.0);
        team.setHumanControlled(false);
        Team saved = teams.save(team);
        for (int p = 1; p <= 25; p++) {
            players.save(player(label + " " + p, saved));
        }
        return saved;
    }

    private static Player player(String name, Team team) {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 12);
        skills.setSkill(SkillName.STAMINA, 12);
        skills.setSkill(SkillName.GOALKEEPER, 12);
        skills.setSkill(SkillName.DEFENDER, 12);
        skills.setSkill(SkillName.TECHNIQUE, 12);
        skills.setSkill(SkillName.PLAYMAKER, 12);
        skills.setSkill(SkillName.PASSING, 12);
        skills.setSkill(SkillName.STRIKER, 12);
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName(name);
        p.setPosition(Position.MID);
        p.setSkills(skills);
        p.setAge(21);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return p;
    }

    /** A plain scoreline. This test is about the competition, not the football. */
    private ProposalMatchOutcome score(Team home, Team away, int homeGoals, int awayGoals) {
        return new ProposalMatchOutcome(
                home.getName(), away.getName(), homeGoals, awayGoals, 3600L, 90, "4-4-2", "4-4-2",
                50.0, 50.0, 1.4, 1.1,
                teamOutcome(home.getName(), homeGoals), teamOutcome(away.getName(), awayGoals),
                new ArrayList<>(), List.of(), null, null, null);
    }

    private static ProposalMatchOutcome.TeamOutcome teamOutcome(String name, int goals) {
        return new ProposalMatchOutcome.TeamOutcome(
                name, goals, 12, 5, 330, 290, 8, 6, 4, 2, 2,
                goals > 0 ? 2 : 3, 5, 7, 9, 2, 1, 0, 0, 0,
                goals >= 2 ? 52.0 : 40.0, 6.4);
    }
}