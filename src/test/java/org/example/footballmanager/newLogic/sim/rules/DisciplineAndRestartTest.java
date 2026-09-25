package org.example.footballmanager.newLogic.sim.rules;

import org.example.footballmanager.newLogic.sim.model.MatchPhase;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.util.SimUtils;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discipline + restart verifications (user mandate 2026-09-24):
 *  - a red card REMOVES the player (sentOff → isUnavailable → 10v11)
 *  - a player who already has one yellow NEVER receives a second yellow —
 *    it auto-upgrades to red and the player is sent off
 *  - fouls restart with the ball to the fouled team (free kick at the foul
 *    spot / penalty on the spot), opponents pushed 9.15 m away
 *  - the real VARService is wired into DisciplineService
 */
class DisciplineAndRestartTest {

    private static final int MAX_ITER = 20000;

    // ---------------------------------------------------------------- red card

    @Test
    void redCardSendsPlayerOff() {
        SimulationRandom.seed(20260924L);
        MatchState state = new MatchState();
        Player attacker = attacker(new Position(5.0, 3.5), "STL");
        Player defender = defender(new Position(5.0, 3.2), "DCL");
        state.getPlayers().add(attacker);
        state.getPlayers().add(defender);
        state.setCarrier(attacker);

        DisciplineService service = new DisciplineService(state, null);

        boolean sawDirectRed = false;
        for (int i = 0; i < MAX_ITER && !sawDirectRed; i++) {
            state.setLastTouchPlayer(defender);
            DisciplineService.DisciplineResult res = service.evaluateFoul(state);
            if (res.redCard() && "Red card".equals(res.description())) sawDirectRed = true;
        }

        assertTrue(sawDirectRed, "expected at least one straight red in " + MAX_ITER + " foul contests");
        assertTrue(defender.isSentOff(), "red card must send the player off");
        assertTrue(defender.getSentOffTick() >= 0, "sent-off tick must be recorded (drives minutes)");
        assertTrue(defender.isUnavailable(), "sent-off player must be unavailable (10v11)");
        assertTrue(state.getRedCards() >= 1);
    }

    @Test
    void secondYellowAutoUpgradesToRedAndNeverShownAsYellow() {
        SimulationRandom.seed(90210L);
        MatchState state = new MatchState();
        Player attacker = attacker(new Position(5.0, 3.5), "STL");
        Player defender = defender(new Position(5.0, 3.2), "DCL");
        defender.setYellowCardsInMatch(1); // already on one yellow
        state.getPlayers().add(attacker);
        state.getPlayers().add(defender);
        state.setCarrier(attacker);

        DisciplineService service = new DisciplineService(state, null);

        boolean sawSecondYellowRed = false;
        for (int i = 0; i < MAX_ITER && !sawSecondYellowRed; i++) {
            state.setLastTouchPlayer(defender);
            DisciplineService.DisciplineResult res = service.evaluateFoul(state);

            assertFalse(res.yellowCard(),
                    "a player already on one yellow must never receive a second yellow — it must upgrade to red");

            if (res.redCard() && "Second yellow -> red card".equals(res.description())) {
                sawSecondYellowRed = true;
                assertTrue(defender.isSentOff());
                assertTrue(defender.isUnavailable());
                assertTrue(defender.getSentOffTick() >= 0);
            }
        }

        assertTrue(sawSecondYellowRed,
                "expected second-yellow -> red upgrade in " + MAX_ITER + " foul contests");
        assertTrue(state.getRedCards() >= 1);
    }

    // ----------------------------------------------------------------- restarts

    @Test
    void freeKickPutsBallAtFoulSpotForFouledTeamAndPushesOpponents() {
        MatchState state = new MatchState();
        Position spot = new Position(4.5, 4.0);

        Player homeAttacker = attacker(new Position(5.0, 3.5), "STL");  // fouled = HOME
        Player homeOpp1 = player("HOME", new Position(5.3, 3.5), "CML"); // HOME (opponents of AWAY)
        Player homeOpp2 = player("HOME", new Position(1.5, 1.2), "DL");
        Player away1 = player("AWAY", new Position(3.0, 3.0), "ML");     // only AVAILABLE AWAY player
        Player awaySentOff = player("AWAY", new Position(4.4, 3.9), "CML"); // nearest AWAY — but SENT OFF
        awaySentOff.setSentOff(true);
        awaySentOff.setSentOffTick(100);
        state.getPlayers().add(homeAttacker);
        state.getPlayers().add(homeOpp1);
        state.getPlayers().add(homeOpp2);
        state.getPlayers().add(away1);
        state.getPlayers().add(awaySentOff);
        state.setCarrier(homeAttacker);

        new RestartManager().handleFreeKick(state, spot, "AWAY");

        assertEquals(spot.getRow(), state.getBall().getPosition().getRow(), 1e-9);
        assertEquals(spot.getColumn(), state.getBall().getPosition().getColumn(), 1e-9);
        assertEquals("AWAY", state.getRestartTeam());
        assertEquals("FREE_KICK", state.getSetPieceType());
        assertSame(MatchPhase.SET_PIECE, state.getPhase());
        assertNull(state.getCarrier(), "carrier is cleared during the restart");

        // Taker must be an AVAILABLE AWAY player — the sent-off nearest one is excluded (10v11).
        Player taker = state.getRestartTaker();
        assertNotNull(taker);
        assertEquals("AWAY", taker.getTeam());
        assertFalse(taker.isUnavailable());
        assertSame(away1, taker, "sent-off player must be skipped as taker");

        // All opponents (HOME) pushed at least 9.15 m (~0.65 cells) from the ball.
        double minOppDst = Math.min(
                SimUtils.distance(homeOpp1.getPosition(), spot),
                SimUtils.distance(homeOpp2.getPosition(), spot));
        assertTrue(minOppDst >= RestartManager.RESTART_OPPONENT_DISTANCE - 1e-9,
                "opponents must be pushed to the FIFA Law 13 minimum distance, got " + minOppDst);
    }

    @Test
    void penaltyPlacesBallOnSpotWithNearestAttackerTaker() {
        MatchState state = new MatchState();
        Player homeTaker = attacker(new Position(7.0, 3.5), "STL"); // nearest HOME attacker to the spot
        Player awayPlayer = player("AWAY", new Position(7.3, 3.3), "DL");
        state.getPlayers().add(homeTaker);
        state.getPlayers().add(awayPlayer);
        state.setCarrier(homeTaker);

        new RestartManager().handlePenalty(state, "HOME");

        Position spot = RestartManager.PENALTY_SPOT_HOME;
        assertEquals(spot.getRow(), state.getBall().getPosition().getRow(), 1e-9);
        assertEquals(spot.getColumn(), state.getBall().getPosition().getColumn(), 1e-9);
        assertEquals("HOME", state.getRestartTeam());
        assertEquals("PENALTY_HOME", state.getSetPieceType());
        assertSame(MatchPhase.SET_PIECE, state.getPhase());
        assertNull(state.getCarrier());

        Player taker = state.getRestartTaker();
        assertNotNull(taker);
        assertEquals("HOME", taker.getTeam());
        assertTrue(taker.isAttacker(), "penalty taker should be an attacker");
    }

    @Test
    void deferredOffsideCreatesFreeKickAndRecorderEvent() {
        MatchState state = new MatchState();
        Player carrier = attacker(new Position(4.5, 3.5), "STL");
        Player receiver = player("HOME", new Position(7.0, 3.5), "STR");
        Player defender = player("AWAY", new Position(6.5, 3.5), "DCL");
        state.getPlayers().add(carrier);
        state.getPlayers().add(receiver);
        state.getPlayers().add(defender);
        state.setCarrier(carrier);
        state.setPendingVARReview("ONSIDE_CHECK", receiver, "AWAY");
        state.setOffsideDeferred(true);
        state.setOffsideDeferredDecisionForward(true);

        MatchRecorder recorder = new MatchRecorder();
        OffsideService service = new OffsideService(
                state, null, recorder, new RestartManager());
        service.resolvePendingVAROffside(state);

        assertEquals("AWAY", state.getRestartTeam());
        assertEquals("FREE_KICK", state.getSetPieceType());
        assertNull(state.getCarrier());
        assertEquals(1, recorder.getEvents().stream()
                .filter(event -> "OFFSIDE".equals(event.getType()))
                .count());
    }

    // ------------------------------------------------------------------- VAR

    @Test
    void realVarServiceWiredIntoDiscipline() {
        SimulationRandom.seed(7L);
        MatchState state = new MatchState();
        Player attacker = attacker(new Position(5.0, 3.5), "STL");
        Player defender = defender(new Position(5.0, 3.2), "DCL");
        state.getPlayers().add(attacker);
        state.getPlayers().add(defender);
        state.setCarrier(attacker);

        VARService varService = new VARService(state, new Random(11));
        DisciplineService service = new DisciplineService(state, varService);

        Set<String> validDecisions = Set.of(
                "NONE", "RED_CONFIRMED", "RED_OVERTURNED",
                "YELLOW_CONFIRMED", "YELLOW_UPGRADED_TO_RED", "YELLOW_DOWNGRADED",
                "PENALTY_CONFIRMED", "PENALTY_OVERTURNED");

        int fouls = 0;
        for (int i = 0; i < MAX_ITER; i++) {
            state.setLastTouchPlayer(defender);
            DisciplineService.DisciplineResult res = service.evaluateFoul(state);
            if (!res.foul()) continue;
            fouls++;
            assertNotNull(res.varDecision());
            assertTrue(validDecisions.contains(res.varDecision()),
                    "unexpected VAR decision: " + res.varDecision());
        }

        assertTrue(fouls > 0, "expected at least one foul");
        assertTrue(state.getYellowCards() + state.getRedCards() > 0,
                "expected discipline to register on the match state");
    }

    // ---------------------------------------------------------------- helpers

    private static Player attacker(Position pos, String role) {
        return player("HOME", pos, role);
    }

    private static Player defender(Position pos, String role) {
        return player("AWAY", pos, role);
    }

    private static Player player(String team, Position pos, String role) {
        return new Player("P-" + team + "-" + role, role + " (" + team + ")", team, role,
                pos, pos, PlayerSkills.neutral());
    }
}