package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-match preview reads the shape and the eleven the manager actually chose (T0-UI-8).
 *
 * <p><b>Six preview fields were hardcoded {@code null}</b> with the comment *"not knowable before a
 * match"*. That was true while a fixture had neither a lineup nor a tactic. It stopped being true the
 * moment both could be saved, and the preview kept showing "Not known yet" for numbers it could have
 * answered — while `homeFormation` reported the club's standing column rather than the shape being
 * picked in front of the screen.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ShapePreviewServiceTest {

    @Autowired ShapePreviewService shapePreview;
    @Autowired MatchLineupService lineups;
    @Autowired TacticLibraryService tactics;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired MatchRepository matches;
    @Autowired CompetitionRepository competitions;

    private Team club;
    private MatchFixture fixture;
    private List<Player> squad;

    @BeforeEach
    void setUp() {
        club = new Team();
        club.setName("Shape Club " + System.nanoTime());
        club = teams.save(club);

        Team other = new Team();
        other.setName("Shape Opponent " + System.nanoTime());
        other = teams.save(other);

        Competition competition = new Competition();
        competition.setName("Shape League " + System.nanoTime());
        competition.setType(CompetitionType.LEAGUE);
        competition = competitions.save(competition);

        fixture = new MatchFixture();
        fixture.setHomeTeam(club);
        fixture.setAwayTeam(other);
        fixture.setCompetition(competition);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setMatchDate(LocalDateTime.now().plusDays(5));
        fixture = fixtures.save(fixture);

        // One keeper, seven defenders, one striker, two midfielders — a shape 4-4-2 cannot hold.
        squad = new ArrayList<>();
        addPlayer("GK", PlayerRole.GOALKEEPER, 1);
        for (int i = 0; i < 7; i++) addPlayer("Def " + i, PlayerRole.CENTRE_BACK, 2);
        addPlayer("ST", PlayerRole.STRIKER, 3);
        addPlayer("W", PlayerRole.WINGER, 4);
        addPlayer("M", PlayerRole.CENTRE_MIDFIELDER, 5);
    }

    private Player addPlayer(String name, PlayerRole role, int rating) {
        Player player = newPlayer(name, role, rating);
        squad.add(player);
        return player;
    }

    /** A player who is not in the eleven — a substitute, or an extra built for a specific test. */
    private Player newPlayer(String name, PlayerRole role, int rating) {
        Player player = new Player();
        player.setName(name + " " + System.nanoTime());
        player.setTeam(club);
        player.setRole(role);
        player.setRating(rating);
        return players.save(player);
    }

    private List<Long> idsOf(List<Player> group) {
        return group.stream().map(Player::getId).toList();
    }

    private void playTheFixture() {
        Match match = new Match();
        match.setHomeTeam(club);
        match.setAwayTeam(fixture.getAwayTeam());
        match.setCompetition(fixture.getCompetition());
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setMatchDate(fixture.getMatchDate());
        match.setPlayed(true);
        match = matches.save(match);
        fixture.setPlayedMatch(match);
        fixture.setPlayed(true);
        fixtures.save(fixture);
    }

    @Test
    @DisplayName("a club with no eleven has nothing to measure, and says so")
    void anUnpickedClubHasNoShape() {
        Map<String, Object> shape = shapePreview.forSide(club, fixture, "HOME");

        assertEquals("4-4-2", shape.get("formation"), "with nothing chosen, the engine's shape stands");
        assertNull(shape.get("formationFitness"),
                "a perfect 100% would read as 'this eleven fits its formation' when no eleven was picked");
        assertNull(shape.get("positionMismatches"));
    }

    @Test
    @DisplayName("fitness measures the chosen eleven against the chosen shape")
    void fitnessIsMeasuredAgainstTheShape() {
        playTheFixture();
        // Eleven players: one keeper, seven centre backs, a striker, a winger and a midfielder. Under a
        // 4-4-2 that is seven defenders in a shape with four, so it must not come out perfect.
        lineups.save(club.getId(), fixture.getId(), idsOf(squad), List.of(), "4-4-2", "Balanced");

        Map<String, Object> shape = shapePreview.forSide(club, fixture, "HOME");

        assertEquals(11, shape.get("starterCount"));
        double fitness = (Double) shape.get("formationFitness");
        assertNotNull(fitness, "an eleven has been picked, so the fit is knowable");
        assertTrue(fitness < 1.0,
                "seven centre backs in a 4-4-2 is not a perfect fit, and a 1.0 here would be the number "
                        + "lying: " + fitness);
        assertTrue((Integer) shape.get("positionMismatches") > 0,
                "the defenders who have no slot in that shape are the mismatch count");
    }

    @Test
    @DisplayName("a shape that fits its eleven scores full marks")
    void aFittingShapeScoresFull() {
        playTheFixture();
        // 4-4-2 is GK, DL, DCL, DCR, DR, ML, CML, CMR, MR, STL, STR. None of these join the club's
        // standing squad, because `squad` is the eleven this test deliberately malfits.
        List<Player> fitting = new ArrayList<>();
        Player gk = newPlayer("Fit GK", PlayerRole.GOALKEEPER, 5);
        List<Player> backs = List.of(
                newPlayer("DL", PlayerRole.LEFT_BACK, 5),
                newPlayer("DCL", PlayerRole.CENTRE_BACK, 5),
                newPlayer("DCR", PlayerRole.RIGHT_CENTRE_BACK, 5),
                newPlayer("DR", PlayerRole.RIGHT_BACK, 5));
        List<Player> mids = List.of(
                newPlayer("ML", PlayerRole.DEFENSIVE_MIDFIELDER, 5),
                newPlayer("CML", PlayerRole.DEFENSIVE_MIDFIELDER, 5),
                newPlayer("CMR", PlayerRole.DEFENSIVE_MIDFIELDER, 5),
                newPlayer("MR", PlayerRole.DEFENSIVE_MIDFIELDER, 5));
        List<Player> strikers = List.of(
                newPlayer("STL", PlayerRole.STRIKER, 5),
                newPlayer("STR", PlayerRole.STRIKER, 5));
        fitting.addAll(List.of(gk));
        fitting.addAll(backs);
        fitting.addAll(mids);
        fitting.addAll(strikers);

        lineups.save(club.getId(), fixture.getId(), idsOf(fitting), List.of(), "4-4-2", "Balanced");

        Map<String, Object> shape = shapePreview.forSide(club, fixture, "HOME");

        assertEquals(1.0, (Double) shape.get("formationFitness"), 0.001,
                "eleven players each in a slot the shape has is a full fit");
        assertEquals(0, shape.get("positionMismatches"));
    }

    @Test
    @DisplayName("the shape shown is the one picked, not the club's standing column")
    void theShapeShownIsTheOnePicked() {
        playTheFixture();
        club.setFormation("5-3-2");
        club = teams.save(club);
        tactics.save(club.getId(), "Cup 4-3-3", "4-3-3", "Attacking",
                "[{\"slotKey\":\"CML\",\"possessionContext\":\"WE_HAVE_BALL\","
                        + "\"ballStateKey\":\"CELL_3_3\",\"targetCellKey\":\"CELL_5_2\"}]", null, true);

        Map<String, Object> shape = shapePreview.forSide(club, fixture, "HOME");

        assertEquals("4-3-3", shape.get("formation"),
                "showing the club's standing 5-3-2 while the manager is picking a 4-3-3 in front of the "
                        + "screen is a preview that describes a different match from the one being set up");
    }

    @Test
    @DisplayName("the bench is judged, and an empty bench is not scored")
    void theBenchIsJudged() {
        playTheFixture();
        lineups.save(club.getId(), fixture.getId(), idsOf(squad), List.of(), "4-4-2", "Balanced");

        assertNull(shapePreview.forSide(club, fixture, "HOME").get("benchQuality"),
                "with no substitutes named there is nothing to judge, and 0.0 would read as a weak bench "
                        + "rather than an absent one");

        List<Player> bench = List.of(
                newPlayer("Sub A", PlayerRole.CENTRE_MIDFIELDER, 6),
                newPlayer("Sub B", PlayerRole.CENTRE_MIDFIELDER, 4));
        lineups.save(club.getId(), fixture.getId(), idsOf(squad), idsOf(bench), "4-4-2", "Balanced");

        Map<String, Object> shape = shapePreview.forSide(club, fixture, "HOME");
        assertEquals(5.0, (Double) shape.get("benchQuality"), 0.001, "the mean of 6 and 4");
        assertEquals(2, shape.get("benchSize"));
    }
}