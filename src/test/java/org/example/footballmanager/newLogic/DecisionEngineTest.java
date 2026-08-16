package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.AwarenessEngine;
import org.example.footballmanager.newLogic.engine.DecisionEngine;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlayerDecisionDemo contract tests:
 *  1. decide() is fully deterministic — identical state always yields the same
 *     action. No random sampling happens when selecting the action.
 *  2. Skills shape the decision — a high-skill carrier is more likely to trust
 *     technique (dribble) in open space, a low-skill carrier plays it safe.
 *  3. Situational gates hold — CLEAR is only available to defenders under
 *     pressure, SHOOT is never chosen outside shooting range.
 */
public class DecisionEngineTest {

    private final DecisionEngine decisions = new DecisionEngine();
    private final AwarenessEngine awareness = new AwarenessEngine();

    // ── Determinism ─────────────────────────────────────────────────────────

    @Test
    void decideIsDeterministic_givenIdenticalState() {
        MatchState state = buildOpenMidfieldState();
        PlayerSnapshot carrier = snapshot(state, 3);
        awareness.update(state);

        DecisionEngine.BallAction first = decisions.decide(state, carrier, awareness);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, decisions.decide(state, carrier, awareness),
                "Decision changed on repetition " + i + " — selection must not use RNG");
        }
    }

    @Test
    void decideIsDeterministic_acrossDuplicateStates() {
        MatchState s1 = buildOpenMidfieldState();
        MatchState s2 = buildOpenMidfieldState();
        awareness.update(s1);
        awareness.update(s2);

        assertEquals(
            decisions.decide(s1, snapshot(s1, 3), awareness),
            decisions.decide(s2, snapshot(s2, 3), awareness),
            "Two structurally identical states must produce the same action");
    }

    // ── Skill sensitivity ───────────────────────────────────────────────────

    @Test
    void higherSkillCarrierChoosesMoreAssertivePlay_inOpenSpace() {
        // WNG at (55,35), HOME. One opponent ~11m away (pressure 0, some space).
        // Two teammates within 15m. No forward teammate for a long ball.
        MatchState lowState = buildOpenMidfieldState();
        setSkills(lowState, snapshot(lowState, 4), 5, 5, 5, 5, 5, 5, 12);
        awareness.update(lowState);

        MatchState highState = buildOpenMidfieldState();
        setSkills(highState, snapshot(highState, 4), 20, 20, 20, 20, 20, 20, 12);
        awareness.update(highState);

        PlayerSnapshot lowCarrier = snapshot(lowState, 4);
        PlayerSnapshot highCarrier = snapshot(highState, 4);

        DecisionEngine.BallAction lowAction = decisions.decide(lowState, lowCarrier, awareness);
        DecisionEngine.BallAction highAction = decisions.decide(highState, highCarrier, awareness);

        assertNotEquals(lowAction, highAction,
            "Skill difference must change the decision (low=" + lowAction + ", high=" + highAction + ")");
        assertEquals(DecisionEngine.BallAction.DRIBBLE, highAction,
            "High-skill winger in open space should dribble (got " + highAction + ")");
        assertEquals(DecisionEngine.BallAction.SHORT_PASS, lowAction,
            "Low-skill carrier in open space should play it safe with a short pass (got " + lowAction + ")");
    }

    @Test
    void shootingSkillRaisedByThirteenPointsFlipsDecision_toShoot() {
        // Attacker at (72,50) — 24m from the home goal, boxed in by three opponents
        // (pressure 1.0), no teammates nearby. Shooting skill decides the action:
        // weak (6) attacker doesn't trust the shot, strong (19) attacker pulls the
        // trigger despite the pressure.
        MatchState lowState = buildCloseRangeState();
        setSkills(lowState, snapshot(lowState, 4), 12, 6, 6, 6, 6, 6, 12);
        awareness.update(lowState);

        MatchState highState = buildCloseRangeState();
        setSkills(highState, snapshot(highState, 4), 12, 6, 6, 6, 19, 6, 12);
        awareness.update(highState);

        PlayerSnapshot lowShooter = snapshot(lowState, 4);
        PlayerSnapshot highShooter = snapshot(highState, 4);

        DecisionEngine.BallAction lowAction = decisions.decide(lowState, lowShooter, awareness);
        DecisionEngine.BallAction highAction = decisions.decide(highState, highShooter, awareness);

        assertEquals(DecisionEngine.BallAction.SHOOT, highAction,
            "High shooting skill near goal should select SHOOT (got " + highAction + ")");
        assertNotEquals(DecisionEngine.BallAction.SHOOT, lowAction,
            "Weak shooting skill near goal should prefer something else (got " + lowAction + ")");
    }

    // ── Situational gates ───────────────────────────────────────────────────

    @Test
    void clearOnlyAvailableToDefenders_underPressureInOwnThird() {
        // Attacker deep in the opponent half under heavy pressure must never clear.
        MatchState state = buildOpenMidfieldState();
        setSkills(state, snapshot(state, 4), 12, 12, 12, 12, 12, 12, 12);
        awareness.update(state);
        PlayerSnapshot attacker = snapshot(state, 4);

        for (int i = 0; i < 10; i++) {
            assertNotEquals(DecisionEngine.BallAction.CLEAR, decisions.decide(state, attacker, awareness),
                "A winger must never select CLEAR");
        }
    }

    @Test
    void shootNeverChosenOutsideShotRange() {
        // Carrier in own half (30,50) — 66m from goal, far beyond SHOT_DISTANCE.
        MatchState state = buildOwnHalfState();
        setSkills(state, snapshot(state, 4), 20, 20, 20, 20, 20, 20, 12);
        awareness.update(state);
        PlayerSnapshot carrier = snapshot(state, 4);

        for (int i = 0; i < 10; i++) {
            assertNotEquals(DecisionEngine.BallAction.SHOOT, decisions.decide(state, carrier, awareness),
                "SHOOT must be gated by distance");
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private MatchState newState() {
        Team home = new Team();
        home.setName("HOME");
        Team away = new Team();
        away.setName("AWAY");
        home.selectLineup(List.of(), List.of());
        away.selectLineup(List.of(), List.of());

        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        return new MatchState(match);
    }

    private PlayerSnapshot snapshot(MatchState state, long id) {
        return state.playerSnapshots.stream().filter(s -> s.playerId() == id).findFirst().orElseThrow();
    }

    private void setSkills(MatchState state, PlayerSnapshot snap, int pace, int technique, int passing,
                           int playmaking, int shooting, int defending, int stamina) {
        // PlayerSnapshot skills are final — rebuild the snapshot with the new values.
        PlayerSnapshot rebuilt = new PlayerSnapshot(snap.playerId(), snap.name(), snap.teamSide(),
            snap.position(), snap.x(), snap.y(), snap.state(), snap.hasBall(),
            pace, technique, passing, playmaking, shooting, defending, stamina);
        for (int i = 0; i < state.playerSnapshots.size(); i++) {
            if (state.playerSnapshots.get(i) == snap) {
                state.playerSnapshots.set(i, rebuilt);
                return;
            }
        }
        throw new IllegalStateException("Snapshot not found in state");
    }

    // Open midfield: carrier WNG at (55,35) with 2 teammates close by and a single
    // opponent ~11m away. Used by the determinism + skill-sensitivity tests.
    private MatchState buildOpenMidfieldState() {
        MatchState state = newState();
        state.playerSnapshots.addAll(List.of(
            home(1, Position.GK, 4, 50, 12),
            home(2, Position.DEF, 40, 50, 12),
            home(3, Position.MID, 50, 42, 12),
            home(4, Position.WNG, 55, 35, 12),
            home(5, Position.ATT, 60, 40, 12),
            away(101, Position.GK, 96, 50, 12),
            away(102, Position.DEF, 80, 50, 12),
            away(103, Position.MID, 55, 46, 12)
        ));
        state.carrierId = 4L;
        return state;
    }

    // Close range: carrier ATT at (72,50) — 24m from goal, three opponents within 8m
    // (pressure 1.0), no teammates within 15m. Used by the shooting skill-flip test.
    private MatchState buildCloseRangeState() {
        MatchState state = newState();
        state.playerSnapshots.addAll(List.of(
            home(1, Position.GK, 4, 50, 12),
            home(2, Position.DEF, 40, 50, 12),
            home(3, Position.MID, 45, 40, 12),
            home(4, Position.ATT, 72, 50, 12),
            home(5, Position.ATT, 50, 55, 12),
            away(101, Position.GK, 96, 50, 12),
            away(102, Position.DEF, 74, 47, 12),
            away(103, Position.DEF, 70, 45, 12),
            away(104, Position.DEF, 76, 53, 12)
        ));
        state.carrierId = 4L;
        return state;
    }

    // Own half: carrier WNG at (30,50), all playmates far away.
    private MatchState buildOwnHalfState() {
        MatchState state = newState();
        state.playerSnapshots.addAll(List.of(
            home(1, Position.GK, 4, 50, 12),
            home(2, Position.DEF, 20, 40, 12),
            home(3, Position.MID, 25, 60, 12),
            home(4, Position.WNG, 30, 50, 12),
            away(101, Position.GK, 96, 50, 12),
            away(102, Position.DEF, 70, 50, 12),
            away(103, Position.MID, 65, 45, 12)
        ));
        state.carrierId = 4L;
        return state;
    }

    private PlayerSnapshot home(long id, Position pos, double x, double y, int stamina) {
        return new PlayerSnapshot(id, "HOME_" + pos, "HOME", pos, x, y, "NORMAL", false,
            12, 12, 12, 12, 12, 12, stamina);
    }

    private PlayerSnapshot away(long id, Position pos, double x, double y, int stamina) {
        return new PlayerSnapshot(id, "AWAY_" + pos, "AWAY", pos, x, y, "NORMAL", false,
            12, 12, 12, 12, 12, 12, stamina);
    }
}
