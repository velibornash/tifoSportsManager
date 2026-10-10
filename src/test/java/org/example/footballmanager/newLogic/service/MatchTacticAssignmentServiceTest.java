package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.sim.tactics.TacticMatchCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assigning a club's tactics to one fixture (T0-BE-2).
 *
 * <p><b>Max three, refused on write.</b> A fourth instruction is rejected with the reason. Accepting it
 * would produce a list of four from which only the first three are ever considered, and a manager would
 * believe the fourth was in force for ninety minutes. That is the same failure as the substitution rule
 * that could never fire, and it is why the limit is a refusal and not a truncation.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class MatchTacticAssignmentServiceTest {

    @Autowired MatchTacticAssignmentService assignments;
    @Autowired TacticLibraryService library;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;

    private MatchFixture fixture;
    private Long homeTacticId;
    private Long awayTacticId;

    @BeforeEach
    void setUp() {
        Team home = teams.save(team("Assign Home"));
        Team away = teams.save(team("Assign Away"));

        Competition competition = new Competition();
        competition.setName("Assign League " + System.nanoTime());
        competition.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        competition = competitions.save(competition);

        fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setCompetition(competition);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setMatchDate(LocalDateTime.now().plusDays(4));
        fixture = fixtures.save(fixture);

        homeTacticId = library.save(home.getId(), "Home 4-4-2", "4-4-2", "Balanced", rules(), null, true).getId();
        awayTacticId = library.save(away.getId(), "Away 4-4-2", "4-4-2", "Balanced", rules(), null, true).getId();
    }

    private Team team(String prefix) {
        Team team = new Team();
        team.setName(prefix + " " + System.nanoTime());
        return team;
    }

    private static String rules() {
        return "[{\"slotKey\":\"CML\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_5_5\"}]";
    }

    @Test
    @DisplayName("a club sets up to three tactics for a fixture, in priority order")
    void threeTacticsInPriorityOrder() {
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 1,
                TacticMatchCondition.ALWAYS, 0);
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 2,
                TacticMatchCondition.LEADING_BY_THREE, 60);

        List<org.example.footballmanager.newLogic.model.tactics.MatchTacticAssignment> home =
                assignments.forSide(fixture.getId(), "HOME");

        assertEquals(2, home.size());
        assertEquals(List.of(1, 2), home.stream()
                        .map(org.example.footballmanager.newLogic.model.tactics.MatchTacticAssignment::getPriority)
                        .toList(),
                "assignments read in the order they will be considered");
    }

    @Test
    @DisplayName("a club can never hold more than three, because there are only three priorities")
    void theLimitIsThreeAndItCannotBePassed() {
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 1, TacticMatchCondition.ALWAYS, 0);
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 2, TacticMatchCondition.DRAWING, 0);
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 3, TacticMatchCondition.TRAILING_BY_THREE, 0);

        // A fourth instruction cannot be created at all, and it is refused two ways depending on what the
        // manager typed. Priority 4 is outside the range; any priority inside it names an occupied slot,
        // which replaces what is there rather than adding a row. Neither leaves the club holding four,
        // which is the whole requirement.
        IllegalArgumentException outOfRange = assertThrows(IllegalArgumentException.class,
                () -> assignments.assign(fixture.getId(), "HOME", homeTacticId, 4,
                        TacticMatchCondition.ALWAYS, 0));
        assertTrue(outOfRange.getMessage().contains("1 to 3"),
                "the manager is told the range they can pick from, not that a constraint was violated: "
                        + outOfRange.getMessage());

        assertEquals(3, assignments.forSide(fixture.getId(), "HOME").size());

        // The count check in the service is defence in depth and cannot be reached from here; it exists
        // for the day priority is widened. This is the behaviour that actually holds the line today.
        assertEquals(3, MatchTacticAssignmentService.MAX_PER_SIDE,
                "if the priority range is ever widened past three, the cap is what stops a fourth row");
    }

    @Test
    @DisplayName("the three are per club, so both sides may set three")
    void theLimitIsPerClub() {
        for (int priority = 1; priority <= 3; priority++) {
            assignments.assign(fixture.getId(), "HOME", homeTacticId, priority,
                    TacticMatchCondition.ALWAYS, 0);
            assignments.assign(fixture.getId(), "AWAY", awayTacticId, priority,
                    TacticMatchCondition.ALWAYS, 0);
        }

        assertEquals(3, assignments.forSide(fixture.getId(), "HOME").size());
        assertEquals(3, assignments.forSide(fixture.getId(), "AWAY").size());
        assertEquals(6, assignments.forFixture(fixture.getId()).size(),
                "each side reads the score from its own point of view, so each needs its own set");
    }

    @Test
    @DisplayName("re-saving a priority replaces that slot instead of failing")
    void savingAtAnOccupiedPriorityEditsIt() {
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 1, TacticMatchCondition.ALWAYS, 0);

        assignments.assign(fixture.getId(), "HOME", homeTacticId, 1, TacticMatchCondition.DRAWING, 30);

        List<org.example.footballmanager.newLogic.model.tactics.MatchTacticAssignment> home =
                assignments.forSide(fixture.getId(), "HOME");
        assertEquals(1, home.size(), "editing priority 1 should not add a second instruction");
        assertEquals(TacticMatchCondition.DRAWING, home.get(0).getCondition());
        assertEquals(30, home.get(0).getMinuteFrom());
    }

    @Test
    @DisplayName("a club cannot assign another club's tactic")
    void aTacticFromAnotherClubIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> assignments.assign(
                fixture.getId(), "HOME", awayTacticId, 1, TacticMatchCondition.ALWAYS, 0),
                "the away club's tactic is not in the home club's library, and assigning it would put one "
                        + "club's shape on the other");
    }

    @Test
    @DisplayName("priority outside 1..3 and an unknown side are refused with a reason")
    void impossibleRequestsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> assignments.assign(
                fixture.getId(), "HOME", homeTacticId, 0, TacticMatchCondition.ALWAYS, 0));
        assertThrows(IllegalArgumentException.class, () -> assignments.assign(
                fixture.getId(), "HOME", homeTacticId, 4, TacticMatchCondition.ALWAYS, 0));
        assertThrows(IllegalArgumentException.class, () -> assignments.assign(
                fixture.getId(), "BENCH", homeTacticId, 1, TacticMatchCondition.ALWAYS, 0));
    }

    @Test
    @DisplayName("the minute is clamped rather than refused, because it is a slider")
    void theMinuteIsClamped() {
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 1, TacticMatchCondition.ALWAYS, -5);
        assignments.assign(fixture.getId(), "HOME", homeTacticId, 2, TacticMatchCondition.ALWAYS, 500);

        List<org.example.footballmanager.newLogic.model.tactics.MatchTacticAssignment> home =
                assignments.forSide(fixture.getId(), "HOME");
        assertEquals(0, home.get(0).getMinuteFrom());
        assertEquals(90, home.get(1).getMinuteFrom());
    }

    @Test
    @DisplayName("a fixture nobody touched has no assignments, which is the ordinary case")
    void anUnassignedFixtureIsNotAnError() {
        assertEquals(List.of(), assignments.forFixture(fixture.getId()),
                "the manager forgetting is not an error state; the club's default tactic stands");
    }
}