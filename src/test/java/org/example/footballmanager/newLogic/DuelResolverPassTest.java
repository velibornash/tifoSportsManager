package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.DuelResolver;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DuelResolverPassTest {

    private PlayerSnapshot snap(long id, String side, Position pos, double x, double y, int passing, int technique) {
        return new PlayerSnapshot(id, "P" + id, side, pos, x, y, "NORMAL", false, 10, technique, passing, 10, 10, 10, 10);
    }

    @Test
    void completedPass_returnsReceiverCoords() {
        DuelResolver r = new DuelResolver();
        PlayerSnapshot carrier = snap(1, "HOME", Position.MID, 30, 50, 20, 20);
        PlayerSnapshot receiver = snap(2, "HOME", Position.MID, 50, 50, 10, 10);
        List<PlayerSnapshot> snaps = new ArrayList<>();
        snaps.add(carrier); snaps.add(receiver);

        // Pass outcomes are stochastic; run enough trials to find a COMPLETED
        // outcome and verify it lands on the receiver.
        boolean sawCompleted = false;
        for (int i = 0; i < 100 && !sawCompleted; i++) {
            DuelResolver.PassResolution res = r.resolvePass(carrier, receiver, snaps);
            if (res.outcome() == DuelResolver.PassOutcomeType.COMPLETED) {
                sawCompleted = true;
                assertNull(res.interceptor());
                assertEquals(receiver.x(), res.x());
                assertEquals(receiver.y(), res.y());
            }
        }
        assertTrue(sawCompleted, "resolver should produce at least one COMPLETED in 100 trials");
    }

    @Test
    void interceptedPass_returnsInterceptorCoords() {
        // Run many trials to find at least one interception
        DuelResolver r = new DuelResolver();
        boolean sawIntercept = false;
        for (int i = 0; i < 500 && !sawIntercept; i++) {
            PlayerSnapshot carrier = snap(1, "HOME", Position.MID, 50, 50, 5, 5);
            PlayerSnapshot receiver = snap(2, "HOME", Position.MID, 55, 50, 10, 10);
            PlayerSnapshot opp = snap(3, "AWAY", Position.DEF, 56, 51, 10, 10);
            List<PlayerSnapshot> snaps = new ArrayList<>();
            snaps.add(carrier); snaps.add(receiver); snaps.add(opp);
            DuelResolver.PassResolution res = r.resolvePass(carrier, receiver, snaps);
            if (res.outcome() == DuelResolver.PassOutcomeType.INTERCEPTED) {
                sawIntercept = true;
                assertSame(opp, res.interceptor());
                assertEquals(opp.x(), res.x());
                assertEquals(opp.y(), res.y());
            }
        }
        assertTrue(sawIntercept, "resolver should produce at least one INTERCEPTED in 500 trials");
    }

    @Test
    void nonOobOutcomesLandOnField() {
        DuelResolver r = new DuelResolver();
        PlayerSnapshot carrier = snap(1, "HOME", Position.MID, 30, 50, 10, 10);
        PlayerSnapshot receiver = snap(2, "HOME", Position.MID, 50, 50, 10, 10);
        PlayerSnapshot opp = snap(3, "AWAY", Position.DEF, 52, 50, 10, 10);
        List<PlayerSnapshot> snaps = new ArrayList<>();
        snaps.add(carrier); snaps.add(receiver); snaps.add(opp);

        for (int i = 0; i < 400; i++) {
            DuelResolver.PassResolution res = r.resolvePass(carrier, receiver, snaps);
            if (res.outcome() == DuelResolver.PassOutcomeType.OUT_OF_BOUNDS) continue; // OOB lands off-field by design
            double x = res.x(), y = res.y();
            assertTrue(x >= 0 && x <= 100 && y >= 0 && y <= 100,
                "non-OOB outcome must be on-field, got " + x + "," + y + " (" + res.outcome() + ")");
        }
    }
}
