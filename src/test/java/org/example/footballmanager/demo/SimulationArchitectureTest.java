package org.example.footballmanager.demo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression testovi za ARHITEKTURNI REFAKTOR (SPRINT — SIMULATION
 * ARCHITECTURAL FOUNDATION REFACTOR).
 *
 * Proveravaju:
 *  - da je {@link Action} model konzistentan sa opservabilnim stanjem
 *    tokom zivotnog ciklusa (CHASE/CARRY/PASS/SHOT),
 *  - da je sekvenca akcija za fiksni seed OSTATa identicna pre refaktora
 *    (behavior snapshot — sprecava ponasanja da se nenamerno promeni).
 */
class SimulationArchitectureTest {

    @Test
    void actionModelIsConsistentWithObservableState() {
        SimulationEngine engine = newEngine(7);
        for (int round = 0; round < 120; round++) {
            engine.step();
            if (engine.isActionInProgress()) {
                Action action = engine.getCurrentAction();
                assertNotNull(action, "aktivna akcija mora biti vidljiva");
                assertActionConsistent(engine, action);
            }
            int ticks = 0;
            while (!engine.isRoundComplete() && ticks < 800) {
                engine.advance();
                ticks++;
            }
            if (engine.isCelebrating()) {
                engine.reset();
            }
        }
    }

    private static void assertActionConsistent(SimulationEngine engine, Action action) {
        String status = engine.getStatus();
        switch (action.getType()) {
            case CHASE -> {
                assertTrue(status.startsWith("CHASE") || status.contains("still moving"),
                    "CHASE status ne poklapa se: " + status);
                assertTrue(engine.getBall().getCarrier() == null
                        || engine.getBall().getCarrier() == engine.getCarrier());
            }
            case CARRY -> {
                assertTrue(status.startsWith("CARRY"), "CARRY status: " + status);
                assertNotNull(engine.getCarrier().getTarget(), "CARRY nosilac mora imati cilj");
                assertEquals(engine.getBall().getCarrier(), engine.getCarrier());
            }
            case PASS -> {
                assertTrue(status.startsWith("PASS"), "PASS status: " + status);
                assertNotNull(action.getTargetPlayer(), "PASS mora imati primaoca");
                assertTrue(action.getTargetPlayer().isLocked(), "primaoc mora biti LOCKED");
                assertEquals(action.getTargetPlayer().getPosition(), engine.getBall().getTarget());
                assertEquals(action.getActingPlayer(), engine.getCarrier());
                assertTrue(action.isPassInFlight());
                assertNull(engine.getBall().getCarrier(), "lopta nema nosioca tokom pasa");
            }
            case SHOT -> {
                assertTrue(status.startsWith("SHOT"), "SHOT status: " + status);
                assertTrue(action.isShotInFlight());
                assertEquals(ActionEngine.GOAL_POSITION, engine.getBall().getTarget());
                assertEquals(ActionEngine.GOAL_POSITION, action.getTargetPosition());
                assertNull(engine.getBall().getCarrier(), "lopta nema nosioca tokom suta");
            }
        }
    }

    /**
     * Behavior snapshot: za fiksne seed-ove sekvenca akcija (round -> status)
     * mora biti IDENTICNA kao pre refaktora. Prvih 8 akcija seed=1 zabelezeno
     * je pre refaktora i mora ostati nepromenjeno.
     */
    @Test
    void deterministicActionSequenceUnchanged() {
        SimulationEngine engine = newEngine(1);
        List<String> statuses = new ArrayList<>();
        for (int round = 0; round < 8; round++) {
            engine.step();
            statuses.add(engine.getStatus());
            int ticks = 0;
            while (!engine.isRoundComplete() && ticks < 800) {
                engine.advance();
                ticks++;
            }
        }
        assertEquals(List.of(
            "CARRY: HSTL -> (3.0,3.075)",
            "PASS: HSTL -> HDCR",
            "PASS: HDCR -> HSTL",
            "CARRY: HSTL -> (2.0,3.075)",
            "PASS: HSTL -> HDCL",
            "CARRY: HDCL -> (2.0,1.0)",
            "CARRY: HDCL -> (1.0,1.0)",
            "CARRY: HDCL -> (1.0,2.0)"
        ), statuses, "sekvenca akcija za seed=1 mora ostati ista nakon refaktora");
    }

    @Test
    void actionLifecycleClearsActionOnCompletion() {
        SimulationEngine engine = newEngine(3);
        engine.step();
        assertTrue(engine.isActionInProgress());
        int ticks = 0;
        while (!engine.isRoundComplete() && ticks < 800) {
            engine.advance();
            ticks++;
        }
        // Kada se turn zavrsi, akcija je ociscena (ispisana u Action Log-u).
        if (!engine.isCelebrating()) {
            assertNull(engine.getCurrentAction(), "po zavrsetku turna akcija mora biti null");
            assertTrue(!engine.isActionInProgress());
        }
    }

    @Test
    void passActionStoresReceiverAndBallTarget() {
        SimulationEngine engine = newEngine(42);
        // Trazimo neki PASS u prvih 60 akcija i proveravamo Action model.
        boolean seenPass = false;
        for (int round = 0; round < 60 && !seenPass; round++) {
            engine.step();
            if (engine.isActionInProgress() && engine.getCurrentAction().getType() == Action.Type.PASS) {
                Action action = engine.getCurrentAction();
                Player receiver = action.getTargetPlayer();
                assertNotNull(receiver);
                assertEquals(receiver.getPosition(), engine.getBall().getTarget());
                assertEquals(receiver, action.getTargetPlayer());
                seenPass = true;
            }
            int ticks = 0;
            while (!engine.isRoundComplete() && ticks < 800) {
                engine.advance();
                ticks++;
            }
            if (engine.isCelebrating()) {
                engine.reset();
            }
        }
        assertTrue(seenPass, "mora se desiti bar jedan PASS u 60 akcija");
    }

    private static SimulationEngine newEngine(long seed) {
        List<Player> players = TacticalGridDemo.createPlayers();
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        return new SimulationEngine(players, ball, TacticsRules.defaults(), new Random(seed));
    }
}
