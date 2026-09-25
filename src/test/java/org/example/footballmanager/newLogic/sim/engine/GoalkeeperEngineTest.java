package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.GoalPhysical;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.PitchEnvironment;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goalkeeper model: positioning (bisector + advancing off the line) and the
 * graded save (skill widens his reach, ball speed narrows it, and the chance of
 * holding it falls off with distance from his body).
 */
class GoalkeeperEngineTest {

    private MatchState state;
    private GoalkeeperEngine engine;
    private Player homeGk;
    private Player awayGk;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(99L);
        state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        engine = new GoalkeeperEngine();
        homeGk = keeperOf("HOME");
        awayGk = keeperOf("AWAY");
    }

    private Player keeperOf(String team) {
        return state.getPlayers().stream()
                .filter(p -> p.getTeam().equals(team) && p.isGoalkeeper())
                .findFirst().orElseThrow();
    }

    private void placeBall(double row, double col) {
        state.getBall().setPosition(new Position(row, col));
        state.getBall().stop();
    }

    // ── Positioning ──────────────────────────────────────────────────────────

    @Test
    void keeperStaysBetweenTheBallAndTheMiddleOfHisGoal() {
        // Ball on the right side of the pitch; the keeper must shade right too,
        // i.e. sit on the line from the ball to the goal-mouth centre.
        placeBall(4.5, 6.5);
        Position t = engine.targetPosition(state, homeGk);
        double mouthCentre = (GoalPhysical.MOUTH_LEFT + GoalPhysical.MOUTH_RIGHT) / 2.0;

        // He is closer to the ball's column than to the far post, because the
        // ball is on the right.
        assertTrue(t.getColumn() > mouthCentre,
                "keeper must shade toward the ball's side, got col " + t.getColumn());
        // And he is between the ball's column and the centre.
        assertTrue(t.getColumn() <= 6.5 && t.getColumn() >= mouthCentre - 1e-6);
    }

    @Test
    void keeperAdvancesOffHisLineAsTheBallComesCloser() {
        placeBall(7.0, 4.0);   // far away, near the AWAY goal — HOME keeper is deep
        Position far = engine.targetPosition(state, homeGk);

        placeBall(2.5, 4.0);   // right in front of him
        Position near = engine.targetPosition(state, homeGk);

        double farAdvance = far.getRow() - PitchEnvironment.HOME_GOAL_LINE;
        double nearAdvance = near.getRow() - PitchEnvironment.HOME_GOAL_LINE;
        assertTrue(nearAdvance > farAdvance,
                "must come off his line as the ball approaches: far " + farAdvance
                        + " near " + nearAdvance);
        assertTrue(nearAdvance > 0.5, "should come well off the line at 1.5 cells, got " + nearAdvance);
    }

    @Test
    void keeperNeverLeavesThePitchOrTheGoalMouthSpan() {
        // Ball in a far corner, keeper on the opposite side of the pitch.
        placeBall(1.2, 1.0);
        Position t = engine.targetPosition(state, homeGk);
        assertTrue(t.getColumn() >= GoalPhysical.MOUTH_LEFT - 1.0,
                "must not stray inside the post, got " + t.getColumn());
        assertTrue(t.getColumn() <= GoalPhysical.MOUTH_RIGHT + 1.0,
                "must not stray outside the post, got " + t.getColumn());
        assertTrue(t.getRow() >= PitchEnvironment.HOME_GOAL_LINE - 1e-9,
                "HOME keeper must never stand behind his own goal line");
    }

    @Test
    void awayKeeperMirrorsHomeKeeperExactly() {
        // (4.5, 6.5) and (4.5, 1.5) are exact mirrors about col 4.0 (and row
        // 4.5, which both share), so the two keepers' targets must be exact
        // mirrors of each other (col c -> 8 - c, row r -> 9 - r).
        placeBall(4.5, 6.5);
        Position home = engine.targetPosition(state, homeGk);
        placeBall(4.5, 1.5);
        Position away = engine.targetPosition(state, awayGk);

        assertEquals(8.0 - home.getColumn(), away.getColumn(), 1e-9,
                "keeper column must be an exact mirror for both sides");
        assertEquals(9.0 - home.getRow(), away.getRow(), 1e-9,
                "keeper row must be an exact mirror for both sides");
    }

    @Test
    void keeperComesOutForAOneOnOne() {
        // Carrier alone inside the box.
        Player carrier = state.getPlayers().stream()
                .filter(p -> p.getTeam().equals("AWAY") && p.getRole().equals("STR"))
                .findFirst().orElseThrow();
        state.setCarrier(carrier);
        carrier.setPosition(new Position(2.0, 4.0));
        placeBall(2.0, 4.0);
        // Move the HOME defenders well away so it is a genuine one-on-one.
        for (Player p : state.getPlayers()) {
            if ("HOME".equals(p.getTeam()) && p != homeGk) {
                p.setPosition(new Position(6.5, 6.5));
            }
        }
        Position target = engine.targetPosition(state, homeGk);

        // Resting off-line distance is 0.35 cells; a one-on-one must send him
        // much further out — and never past the ball itself (he may close the
        // attacker down, but he cannot stand behind him).
        double advance = target.getRow() - PitchEnvironment.HOME_GOAL_LINE;
        assertTrue(advance > 0.8, "must rush out at a one-on-one, advanced only " + advance);
        assertTrue(target.getRow() <= 2.0 + 1e-9,
                "must never advance past the attacker, got row " + target.getRow());
    }

    @Test
    void keeperNeverAdvancesPastTheBall() {
        // A closed-down striker 0.5 cells away: the bisector fraction would be
        // > 1 and extrapolate the keeper past the goal centre onto the wrong
        // side of the pitch. The advance is capped at the ball's distance.
        placeBall(1.5, 4.0);
        Position target = engine.targetPosition(state, homeGk);
        assertTrue(target.getColumn() > 3.0 && target.getColumn() < 5.0,
                "keeper must stay in the goal-mouth area, got col " + target.getColumn());
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    @Test
    void betterKeeperHasMoreReach() {
        Player weak = keeperWithKeeperSkill(4);
        Player strong = keeperWithKeeperSkill(19);
        double weakReach = engine.effectiveReach(weak, 0.0);
        double strongReach = engine.effectiveReach(strong, 0.0);
        assertTrue(strongReach > weakReach,
                "a better keeper must cover more: " + weakReach + " vs " + strongReach);
    }

    @Test
    void fasterBallShrinksHisReach() {
        double slow = engine.effectiveReach(homeGk, 0.2);
        double fast = engine.effectiveReach(homeGk, BallPhysicsEngine.MAX_BALL_SPEED);
        assertTrue(fast < slow, "a faster ball must leave him less reach: " + fast + " vs " + slow);
    }

    @Test
    void ballOutsideHisReachBeatsHim() {
        double reach = engine.effectiveReach(homeGk, 0.5);
        assertTrue(!engine.trySave(homeGk, reach * 1.5, 0.5),
                "a ball beyond his reach must never be saved");
    }

    @Test
    void shotRightAtHimIsAlmostAlwaysSavedAndCornerShotIsNot() {
        SimulationRandom.seed(7L);
        int atHim = 0, inCorner = 0;
        int trials = 400;
        for (int i = 0; i < trials; i++) {
            if (engine.trySave(homeGk, 0.0, 0.4)) atHim++;
            double reach = engine.effectiveReach(homeGk, 0.4);
            if (engine.trySave(homeGk, reach * 0.95, 0.4)) inCorner++;
        }
        assertTrue(atHim > trials * 0.85,
                "a ball straight at the keeper should be held, held " + atHim + "/" + trials);
        assertTrue(inCorner < atHim,
                "a ball at the edge of his reach must be harder than one at his body: "
                        + inCorner + " vs " + atHim);
    }

    @Test
    void distanceToSegmentMeasuresPerpendicularDistance() {
        // Segment along row 4.0 from col 2.0 to col 6.0; keeper at (4.0, 4.0).
        double d = engine.distanceToSegment(
                new Position(4.0, 4.0), new Position(4.0, 2.0), new Position(4.0, 6.0));
        assertEquals(0.0, d, 1e-9, "keeper on the segment must measure zero");
        double off = engine.distanceToSegment(
                new Position(5.0, 4.0), new Position(4.0, 2.0), new Position(4.0, 6.0));
        assertEquals(1.0, off, 1e-9, "one cell off the line must measure one cell");
    }

    private Player keeperWithKeeperSkill(int skill) {
        PlayerSkills s = new PlayerSkills(11, 13, skill, 9, 9, 11, 6, 8);
        Player gk = new Player("TEST-GK", "T", "HOME", "GK",
                new Position(1.5, 4.0), new Position(1.5, 4.0), s, 90);
        assertNotNull(gk);
        return gk;
    }
}
