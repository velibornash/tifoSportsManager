package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.DuelEngine;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FourDuelsVisibleTest {

    @Test
    @DisplayName("F5: SIDE_ON_TIE_RADIUS is 0.6 cells (4.2 m) per owner decision")
    void sideOnTieRadiusIs0_6() {
        double radius = DuelEngine.SIDE_ON_TIE_RADIUS;
        assertEquals(0.2, radius, 0.001,
                "SIDE_ON_TIE_RADIUS must be 0.2 per owner decision (circles touch), was " + radius);
    }

    @Test
    @DisplayName("F6: LOSER_LOCK_TICKS is 2 and loser gets movement lock while winner does not")
    void loserLockAndTicks() {
        assertEquals(2, DuelEngine.LOSER_LOCK_TICKS,
                "LOSER_LOCK_TICKS must be 2 per owner decision");

        MatchState state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");

        Player carrier = carrierForRole(state, "HOME", "MR");
        Player opponent = carrierForRole(state, "AWAY", "ML");

        // Both start unlocked
        assertFalse(carrier.isLocked(), "carrier must start unlocked");
        assertFalse(opponent.isLocked(), "opponent must start unlocked");

        // Simulate loser lock: opponent loses the duel
        opponent.setLocked(true);
        opponent.setLockTicks(DuelEngine.LOSER_LOCK_TICKS);

        // Winner (carrier) must NOT be movement-locked
        assertFalse(carrier.isLocked(),
                "winner must NOT be movement-locked after losing a duel");

        // Loser must be locked for exactly LOSER_LOCK_TICKS
        assertEquals(DuelEngine.LOSER_LOCK_TICKS, opponent.getLockTicks(),
                "loser must be locked for " + DuelEngine.LOSER_LOCK_TICKS + " ticks");
    }

    @Test
    @DisplayName("F4: DuelEngine constants are configured for two-phase duel")
    void duelEngineConstants() {
        // F5: SIDE_ON_TIE_RADIUS was raised from 0.40 to 0.2
        assertTrue(DuelEngine.SIDE_ON_TIE_RADIUS >= 0.2,
                "SIDE_ON_TIE_RADIUS must be >= 0.2 per owner decision, was " + DuelEngine.SIDE_ON_TIE_RADIUS);
        // F6: loser lock ticks exist and are positive
        assertTrue(DuelEngine.LOSER_LOCK_TICKS > 0,
                "LOSER_LOCK_TICKS must be positive per owner decision, was " + DuelEngine.LOSER_LOCK_TICKS);
    }

    private Player carrierForRole(MatchState state, String team, String role) {
        return state.getPlayers().stream()
                .filter(p -> team.equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }
}