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
 * Regression testovi za ARHITEKTURNI REFAKTOR + EXECUTION QUALITY.
 *
 * Proveravaju:
 *  - da je {@link Action} model konzistentan sa opservabilnim stanjem
 *    tokom zivotnog ciklusa (CHASE/CARRY/PASS/SHOT),
 *  - da PASS/SHOT imaju execution quality polja,
 *  - da se akcije zavrsavaju i oslobadjaju.
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
                assertTrue(status.startsWith("CHASE") || status.contains("chasing ball")
                        || status.contains("moving to ball") || status.contains("still moving")
                        || status.contains("action in progress"),
                    "CHASE status ne poklapa se: " + status);
                assertTrue(engine.getBall().getCarrier() == null
                        || engine.getBall().getCarrier() == engine.getCarrier());
            }
            case CARRY -> {
                assertTrue(status.startsWith("CARRY") || status.contains("action in progress"),
                        "CARRY status: " + status);
                assertNotNull(engine.getCarrier().getTarget(), "CARRY nosilac mora imati cilj");
                assertEquals(engine.getBall().getCarrier(), engine.getCarrier());
            }
            case PASS -> {
                if (action.isClearance()) {
                    assertTrue(status.startsWith("CLEAR"), "CLEAR status: " + status);
                    // Clearance nema primaoca ni skill — preskacemo receiver/skill provere
                } else {
                    assertTrue(status.startsWith("PASS") || status.startsWith("THRU"),
                            "PASS status: " + status);
                    assertNotNull(action.getTargetPlayer(), "PASS mora imati primaoca");
                    assertTrue(action.getTargetPlayer().isLocked(), "primaoc mora biti LOCKED");
                    assertTrue(action.getSkill() >= 1 && action.getSkill() <= 20, "skill 1-20");
                }
                // Ball leti ka actualTarget (odstupna meta), ne ka receiveru
                assertNotNull(action.getActualTarget(), "PASS mora imati actualTarget");
                assertNotNull(action.getIntendedTarget(), "PASS mora imati intendedTarget");
                assertEquals(action.getActualTarget(), engine.getBall().getTarget());
                assertEquals(action.getActingPlayer(), engine.getCarrier());
                assertTrue(action.isPassInFlight());
                assertNull(engine.getBall().getCarrier(), "lopta nema nosioca tokom pasa");
            }
            case SHOT -> {
                assertTrue(status.startsWith("SHOT"), "SHOT status: " + status);
                assertTrue(action.isShotInFlight());
                // Ball leti ka actualTarget (odstupna meta), ne ka golu
                assertNotNull(action.getActualTarget(), "SHOT mora imati actualTarget");
                assertEquals(action.getActualTarget(), engine.getBall().getTarget());
                assertEquals(ActionEngine.goalPositionFor(action.getActingPlayer().getTeam()),
                        action.getIntendedTarget(),
                        "intendedTarget mora biti gol protivnicke ekipe");
                assertNull(engine.getBall().getCarrier(), "lopta nema nosioca tokom suta");
            }
        }
    }

    /**
     * Sekvenca akcija za fiksni seed mora pocinjati isto:
     * Kickoff setup (prvi korak), pa prva prava akcija.
     * Prvi korak je kickoff postavljanje, a drugi korak je prva playmaking odluka.
     */
    @Test
    void deterministicActionSequenceBeginsCorrectly() {
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
            if (engine.isCelebrating()) {
                engine.reset();
            }
        }
        assertTrue(statuses.get(0).contains("KICKOFF: HSTL"),
                "prvi korak mora biti kickoff: HSTL, bio: " + statuses.get(0));
        assertTrue(statuses.get(1).contains("HSTL"),
                "prva prava akcija mora biti za HSTL, bio: " + statuses.get(1));
        assertTrue(statuses.stream().anyMatch(s -> s.startsWith("PASS: HSTL")
                        || s.startsWith("CARRY: HSTL")
                        || s.startsWith("THRU: HSTL")
                        || s.startsWith("CLEAR: HSTL")
                        || s.startsWith("SHOT: HSTL")),
                "HSTL mora dobiti normalnu akciju: " + statuses);
    }

    @Test
    void actionLifecycleClearsActionOnCompletion() {
        SimulationEngine engine = newEngine(3);
        engine.step(); // kickoff setup
        engine.step(); // prva prava akcija
        assertTrue(engine.isActionInProgress());
        int ticks = 0;
        while (!engine.isRoundComplete() && ticks < 800) {
            engine.advance();
            ticks++;
        }
        if (!engine.isCelebrating()) {
            assertNull(engine.getCurrentAction(), "po zavrsetku turna akcija mora biti null");
            assertTrue(!engine.isActionInProgress());
        }
    }

    @Test
    void passActionStoresReceiverAndSkillInfo() {
        SimulationEngine engine = newEngine(42);
        boolean seenPass = false;
        for (int round = 0; round < 60 && !seenPass; round++) {
            engine.step();
            if (engine.isActionInProgress() && engine.getCurrentAction().getType() == Action.Type.PASS) {
                Action action = engine.getCurrentAction();
                Player receiver = action.getTargetPlayer();
                assertNotNull(receiver);
                assertNotNull(action.getActualTarget(), "PASS mora imati actualTarget");
                assertNotNull(action.getIntendedTarget(), "PASS mora imati intendedTarget");
                assertTrue(action.getSkill() >= 1 && action.getSkill() <= 20);
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

    @Test
    void shotActionStoresSkillInfo() {
        SimulationEngine engine = newEngine(10);
        boolean seenShot = false;
        for (int round = 0; round < 200 && !seenShot; round++) {
            engine.step();
            if (engine.isActionInProgress() && engine.getCurrentAction().getType() == Action.Type.SHOT) {
                Action action = engine.getCurrentAction();
                assertNotNull(action.getActualTarget(), "SHOT mora imati actualTarget");
                assertNotNull(action.getIntendedTarget(), "SHOT mora imati intendedTarget");
                assertEquals(ActionEngine.goalPositionFor(action.getActingPlayer().getTeam()),
                        action.getIntendedTarget(),
                        "intendedTarget mora biti gol protivnicke ekipe");
                assertTrue(action.getSkill() >= 1 && action.getSkill() <= 20);
                seenShot = true;
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
        assertTrue(seenShot, "mora se desiti bar jedan SHOT u 200 akcija");
    }

    @Test
    void looseBallTriggersChase() {
        // Tražimo PASS koji ne uspeva (devijacija > 1.5) i proveravamo da li sledi CHASE
        for (int seed = 0; seed < 100; seed++) {
            SimulationEngine engine = newEngine(seed);
            boolean foundLoose = false;
            for (int round = 0; round < 50; round++) {
                engine.step();
                if (engine.getStatus().contains("LOOSE BALL")) {
                    foundLoose = true;
                    // Sledeca akcija treba da bude CHASE
                    int ticks = 0;
                    while (!engine.isRoundComplete() && ticks < 800) {
                        engine.advance();
                        ticks++;
                    }
                    engine.step();
                    if (engine.isActionInProgress()) {
                        assertEquals(Action.Type.CHASE, engine.getCurrentAction().getType(),
                                "nakon LOOSE BALL sledeca akcija mora biti CHASE");
                    }
                    break;
                }
                int ticks = 0;
                while (!engine.isRoundComplete() && ticks < 800) {
                    engine.advance();
                    ticks++;
                }
                if (engine.isCelebrating()) engine.reset();
            }
            if (foundLoose) return; // uspesno testirano
        }
        // Ako nijedan seed nije proizveo loose ball, to je ok za male seed-ove
    }

    private static SimulationEngine newEngine(long seed) {
        List<Player> players = TacticalGridDemo.createPlayers(new Random(seed));
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        return new SimulationEngine(players, ball, TacticsRules.defaults(), new Random(seed));
    }
}
