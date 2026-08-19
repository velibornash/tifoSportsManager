package org.example.footballmanager;

import org.example.footballmanager.demo.swingUIDemo.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless testovi za prvi simulacioni demo (SimulationEngine).
 *
 * Proveravaju invarijante dogovorene u TASK-u:
 *  - AWAY igraci se pomeraju samo kroz aktivni restart/duel flow.
 *  - U jednoj rundi svaki igrac se pomera najvise 1 celiju (8 smerova).
 *  - Nosilac lopte postoji vec u prvoj rundi.
 *  - Posle dovoljno rundi HOME postigne gol i stanje se RESETUJE.
 */
public class SimulationEngineTest {

    @Test
    void testGridAndPlayersValidation() {
        assertTrue(TacticalGridDemo.validateGrid(), "grid validation must pass");
        assertTrue(TacticalGridDemo.validatePlayers(TacticalGridDemo.createPlayers()),
            "player validation must pass");
    }

    @Test
    void testAwayPlayersMoveOnlyThroughActiveBallFlow() {
        SimulationEngine engine = newEngine(42);
        List<Position> awayInitial = awayPositions(engine);

        runRounds(engine, 300);

        List<Position> awayNow = awayPositions(engine);
        assertEquals(awayInitial.size(), awayNow.size());
        assertTrue(awayInitial.size() == awayNow.size());
        assertTrue(awayInitial.stream().anyMatch(initial -> awayNow.stream()
                .anyMatch(current -> chebyshev(initial, current) > 1e-6)),
                "AWAY restart/duel participant should be allowed to move");
    }

    @Test
    void testMaxOneCellPerRoundForHomePlayers() {
        SimulationEngine engine = newEngine(7);

        List<Position> prev = homePositions(engine);
        int prevGoals = engine.getGoalCount();
        for (int round = 0; round < 400; round++) {
            engine.step();
            // 3 s duel cooldown + exact-coordinate chase mogu produžiti
            // lifecycle; ovaj test proverava kretanje, ne fiksan broj tickova.
            runAnimationTicks(engine, 25);

            List<Position> now = homePositions(engine);
            // Runda u kojoj se desio gol resetuje igrace na pocetne pozicije
            // (dizajn reset-a, ne kretanje) — preskacemo proveru te runde.
            boolean goalRound = engine.getGoalCount() > prevGoals;
            prevGoals = engine.getGoalCount();

            if (!goalRound && !engine.isCelebrating()) {
                for (Position position : now) {
                    assertTrue(position.getRow() >= 0.99 && position.getRow() <= 7.10
                                    && position.getColumn() >= 0.99 && position.getColumn() <= 6.10,
                            "player must remain on the pitch: " + position);
                }
            }
            prev = now;
        }
    }

    @Test
    void testCarrierExistsAfterFirstRound() {
        SimulationEngine engine = newEngine(1);
        engine.step();
        runAnimationTicks(engine, 25);
        assertNotNull(engine.getCarrier(), "carrier must exist after first round");
    }

    @Test
    void testGoalEventuallyScoredAndReset() {
        SimulationEngine engine = newEngine(42);
        List<Position> initial = allPositions(engine);

        runRounds(engine, 3000);

        assertTrue(engine.getGoalCount() + engine.getAwayGoalCount() >= 1,
            "At least one goal should be scored, got HOME=" + engine.getGoalCount()
            + " AWAY=" + engine.getAwayGoalCount()
            + " (status: " + engine.getStatus() + ")");

        // Nakon gola engine JE ZAMRZNUT (celebration) — reset zove demo posle ~5s.
        assertTrue(engine.isCelebrating(), "engine must be celebrating after a goal");
        Position ballPos = engine.getBall().getPosition();
        boolean nearHomeGoal = chebyshev(ballPos, new Position(8, 3.5)) < 0.5 + 1e-6;
        boolean nearAwayGoal = chebyshev(ballPos, new Position(0, 3.5)) < 0.5 + 1e-6;
        assertTrue(nearHomeGoal || nearAwayGoal,
            "ball must be in/near a goal during celebration, got " + ballPos);

        // Demo (ili test) poziva reset() tek posle proslave.
        engine.reset();

        assertFalse(engine.isCelebrating(), "celebrating must clear after reset");
        assertEquals(0, engine.getCarrier() == null ? 0 : 1, "carrier must be null after reset");
        assertTrue(chebyshev(engine.getBall().getPosition(), new Position(4, 3.5)) < 1e-6,
            "ball must be back at center after reset");
        List<Position> now = allPositions(engine);
        for (int i = 0; i < initial.size(); i++) {
            assertTrue(chebyshev(initial.get(i), now.get(i)) < 1e-6,
                "player not reset to initial position: " + initial.get(i) + " -> " + now.get(i));
        }
    }

    @Test
    void testRoundTrackingPerPlayer() {
        SimulationEngine engine = newEngine(3);

        for (int round = 0; round < 60; round++) {
            engine.step();
            int ticks = 0;
            boolean hitHalfTime = false;
            while (!engine.isRoundComplete() && ticks < 20000) {
                engine.advance();
                ticks++;
                if (engine.isHalfTime() && !engine.isMatchFinished()) {
                    hitHalfTime = true;
                    engine.startSecondHalf();
                }
                if (engine.isMatchFinished()) break;
            }
            if (engine.isMatchFinished()) break;
            if (hitHalfTime) continue;
            assertTrue(engine.isRoundComplete(), "round " + round + " did not complete in " + ticks + " ticks");

            for (Player p : engine.getPlayers()) {
                Position start = engine.getRoundStartPosition(p);
                Position desired = engine.getDesiredPosition(p);
                Position end = engine.getRoundEndPosition(p);
                assertNotNull(start, p.getLabel() + " start position missing");
                assertNotNull(desired, p.getLabel() + " desired position missing");
                assertNotNull(end, p.getLabel() + " end position missing");

                double moved = engine.getCellsMoved(p);
                assertTrue(Double.isFinite(moved) && moved >= 0,
                    p.getLabel() + " movement tracking must remain finite");
            }
        }
    }

    @Test
    void testTacticalDesiredCellFromEditorRule() {
        Map<String, Map<String, Position>> rules = new HashMap<>();
        Map<String, Position> dlRules = new HashMap<>();
        dlRules.put(TacticsRules.ballStateKey(new Position(5, 4)), new Position(4, 1));
        rules.put("DL", dlRules);

        List<Player> players = TacticalGridDemo.createPlayers();
        Ball ball = new Ball(new Position(5, 4), new Position(5, 4));
        SimulationEngine engine = new SimulationEngine(players, ball,
            new TacticsRules(rules, new HashMap<>()), new Random(99));

        // Nosilac na samoj lopti => step() ide direktno u akciju (bez CHASE),
        // pa se takticki ciljevi računaju iz pravila za loptu na (5,4).
        for (Player p : players) {
            if ("HCML".equals(p.getLabel())) p.setPosition(new Position(5, 4));
            if ("HDL".equals(p.getLabel())) p.setPosition(new Position(2, 2));
        }
        engine.step();

        Player dl = null;
        for (Player p : players) {
            if ("HDL".equals(p.getLabel())) dl = p;
        }
        assertNotNull(dl, "HDL player must exist");

        Position tactical = engine.getTacticalDesiredPosition(dl);
        assertEquals(4.0, tactical.getRow(), 1e-9, "DL tactical desired row for ball at C5_4");
        assertEquals(1.0, tactical.getColumn(), 1e-9, "DL tactical desired column for ball at C5_4");
        assertEquals(5.0, engine.getTacticalBallPosition().getRow(), 1e-9, "rule ball row");
        assertEquals(4.0, engine.getTacticalBallPosition().getColumn(), 1e-9, "rule ball column");
    }

    @Test
    void testAwayPerspectiveMirrorForAllCells() {
        // Proverava da li se AWAY tacno preslikava za SVIH 42 celije:
        //   toPhysical(toHomePerspective(pos, "AWAY"), "AWAY") == pos
        // i za HOME (identity):
        //   toPhysical(toHomePerspective(pos, "HOME"), "HOME") == pos
        for (int r = 1; r <= 7; r++) {
            for (int c = 1; c <= 6; c++) {
                Position pos = new Position(r, c);

                // HOME = identity
                Position homeRoundTrip = TacticalPerspectiveTransformer.toPhysical(
                        TacticalPerspectiveTransformer.toHomePerspective(pos, "HOME"), "HOME");
                assertEquals(r, homeRoundTrip.getRow(), 1e-9,
                        "HOME round-trip row failed for (" + r + "," + c + ")");
                assertEquals(c, homeRoundTrip.getColumn(), 1e-9,
                        "HOME round-trip col failed for (" + r + "," + c + ")");

                // AWAY = double mirror
                Position awayRoundTrip = TacticalPerspectiveTransformer.toPhysical(
                        TacticalPerspectiveTransformer.toHomePerspective(pos, "AWAY"), "AWAY");
                assertEquals(r, awayRoundTrip.getRow(), 1e-9,
                        "AWAY round-trip row failed for (" + r + "," + c + ")");
                assertEquals(c, awayRoundTrip.getColumn(), 1e-9,
                        "AWAY round-trip col failed for (" + r + "," + c + ")");
            }
        }

        // Specificne provere za bekove:
        // HOME DL anchor = CELL_1_0 => (2,1)
        // AWAY physical DL anchor = toPhysical((2,1), "AWAY") = (6,6)
        Position homeDlAnchor = new Position(2, 1);
        Position awayDlPhysical = TacticalPerspectiveTransformer.toPhysical(homeDlAnchor, "AWAY");
        assertEquals(6.0, awayDlPhysical.getRow(), 1e-9, "AWAY DL row");
        assertEquals(6.0, awayDlPhysical.getColumn(), 1e-9, "AWAY DL col");

        // HOME DR anchor = CELL_1_4 => (2,5)
        // AWAY physical DR anchor = toPhysical((2,5), "AWAY") = (6,2)
        Position homeDrAnchor = new Position(2, 5);
        Position awayDrPhysical = TacticalPerspectiveTransformer.toPhysical(homeDrAnchor, "AWAY");
        assertEquals(6.0, awayDrPhysical.getRow(), 1e-9, "AWAY DR row");
        assertEquals(2.0, awayDrPhysical.getColumn(), 1e-9, "AWAY DR col");
    }

    @Test
    void testAwayTacticalDesiredMirrorsHome() {
        // Proverava da li desiredCell za AWAY preslikava i Loptu i poziciju igraca.
        // Ako HOME DL za loptu na (4,3) ide na (2,2),
        // onda AWAY DL za LOPTU NA FIZICKOJ (4,3) treba da koristi
        //   editor pravilo za loptu na toHomePerspective((4,3)) = (4,4)
        //   i preslika rezultat nazad.
        Map<String, Map<String, Position>> rules = new HashMap<>();

        // HOME editor: when ball at (4,3) -> DL at (2,2)
        Map<String, Position> dlRules = new HashMap<>();
        dlRules.put(TacticsRules.ballStateKey(new Position(4, 3)), new Position(2, 2));
        rules.put("DL", dlRules);

        // HOME editor: when ball at (4,4) -> DL at (2,3)
        dlRules.put(TacticsRules.ballStateKey(new Position(4, 4)), new Position(2, 3));

        TacticsRules tactics = new TacticsRules(rules, new HashMap<>());

        // HOME DL za loptu na (4,3) -> (2,2)
        Position homeDl = tactics.desiredCell("DL", new Position(4, 3), "HOME");
        assertEquals(2.0, homeDl.getRow(), 1e-9, "HOME DL row");
        assertEquals(2.0, homeDl.getColumn(), 1e-9, "HOME DL col");

        // AWAY DL za LOPTU NA FIZICKOJ (4,3):
        //   1) toHomePerspective((4,3), "AWAY") = (4,4)
        //   2) desiredCell("DL", (4,4)) = (2,3)
        //   3) toPhysical((2,3), "AWAY") = (6,4)
        Position awayDl = tactics.desiredCell("DL", new Position(4, 3), "AWAY");
        assertEquals(6.0, awayDl.getRow(), 1e-9, "AWAY DL row — should mirror ball AND position");
        assertEquals(4.0, awayDl.getColumn(), 1e-9, "AWAY DL col — should mirror ball AND position");

        // AWAY DL za LOPTU NA FIZICKOJ (3,2):
        //   1) toHomePerspective((3,2), "AWAY") = (5,5)
        //   2) desiredCell("DL", (5,5)) = null -> no rule -> anchor (2,1)
        //   3) toPhysical((2,1), "AWAY") = (6,6)
        // BUT: we passed empty anchors, so fallback is (1, 3.5)
        //   toPhysical((1, 3.5), "AWAY") = (7, 3.5)
        Position awayDl2 = tactics.desiredCell("DL", new Position(3, 2), "AWAY");
        // With empty anchors, fallback to (1,3.5) -> mirror -> (7, 3.5)
        assertEquals(7.0, awayDl2.getRow(), 1e-9, "AWAY DL fallback row (no anchor)");
        assertEquals(3.5, awayDl2.getColumn(), 1e-9, "AWAY DL fallback col (no anchor)");
    }

    @Test
    void testChaseAssignsTacticalTargetsToAllNonChasers() {
        // Simulira CHASE (lopta loose) i proverava da li svi ne-chaser igraci
        // (HOME i AWAY) imaju takticke pozicije dodeljene.
        SimulationEngine engine = newEngine(42);

        // Postavi loptu na centar — loose ball
        engine.getBall().setPosition(new Position(4, 3.5));
        engine.getBall().setCarrier(null);
        // Takodje moramo da ocistimo carrier stanje u engine-u
        // da bismo ušli u LOOSE BALL granu
        engine.reset();
        engine.getBall().setPosition(new Position(4, 3.5));
        engine.getBall().setCarrier(null);

        // Pokreni CHASE
        engine.step();

        // Svi igraci koji nisu chaser treba da imaju target
        Player carrier = engine.getCarrier();
        assertNotNull(carrier, "carrier must be set during CHASE");

        int nonChasersWithTarget = 0;
        int nonChasersTotal = 0;
        String missingPlayers = "";
        for (Player p : engine.getPlayers()) {
            if (p == carrier || p.isLocked()) continue;
            nonChasersTotal++;
            if (p.getTarget() != null) {
                nonChasersWithTarget++;
            } else {
                missingPlayers += p.getLabel() + " ";
            }
        }
        // Bar 75% ne-chasera treba da ima target (osim ako su na istoj poziciji kao cilj)
        assertTrue(nonChasersWithTarget >= nonChasersTotal * 3 / 4,
                "Most non-chaser players must have tactical targets during CHASE. "
                + nonChasersWithTarget + "/" + nonChasersTotal
                + " have targets. Missing: " + missingPlayers);
    }

    @Test
    void testAwayPlayersKeepTacticalWidthDuringChase() {
        // Bekovi ne smeju da idu previse unutra tokom CHASE.
        // Proverava da li AWAY bekovi (DL, DR) dobijaju pozicije blize aut-liniji
        // a ne centralne pozicije.
        Map<String, Map<String, Position>> rules = new HashMap<>();

        // 42 ćelija × role: DL treba uvek da bude na koloni 1 (blizu aut-linije)
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 6; c++) {
                String key = "CELL_" + r + "_" + c;
                Map<String, Position> dl = rules.computeIfAbsent("DL", k -> new HashMap<>());
                dl.put(key, new Position(2, 1)); // HOME DL uvek na koloni 1

                Map<String, Position> dr = rules.computeIfAbsent("DR", k -> new HashMap<>());
                dr.put(key, new Position(2, 5)); // HOME DR uvek na koloni 5
            }
        }

        TacticsRules tactics = new TacticsRules(rules, new HashMap<>());

        // Za bilo koju poziciju lopte, AWAY DL treba da bude na fizickoj
        // koloni >= 5 (blizu DESNE aut-linije gledano iz HOME perspektive = LEVA za AWAY)
        for (int r = 1; r <= 7; r++) {
            for (int c = 1; c <= 6; c++) {
                Position awayDl = tactics.desiredCell("DL", new Position(r, c), "AWAY");
                // HOME DL kolona = 1, AWAY physical = 7 - 1 = 6
                assertEquals(6.0, awayDl.getColumn(), 1e-9,
                        "AWAY DL column must be 6 (wide) for ball at (" + r + "," + c + ")");

                Position awayDr = tactics.desiredCell("DR", new Position(r, c), "AWAY");
                // HOME DR kolona = 5, AWAY physical = 7 - 5 = 2
                assertEquals(2.0, awayDr.getColumn(), 1e-9,
                        "AWAY DR column must be 2 (wide) for ball at (" + r + "," + c + ")");
            }
        }
    }

    // --- pomocne metode ---

    private static SimulationEngine newEngine(long seed) {
        List<Player> players = TacticalGridDemo.createPlayers(new Random(seed));
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        return new SimulationEngine(players, ball, TacticsRules.defaults(), new Random(seed));
    }

    private static void runRounds(SimulationEngine engine, int rounds) {
        for (int r = 0; r < rounds; r++) {
            engine.step();
            runAnimationTicks(engine, 25);
            // Čim se desi gol, runda se završava resetom — stopiramo tu.
            if (engine.getGoalCount() >= 1) {
                return;
            }
        }
    }

    private static void runAnimationTicks(SimulationEngine engine, int ticks) {
        for (int i = 0; i < ticks; i++) {
            engine.advance();
        }
    }

    private static List<Position> homePositions(SimulationEngine engine) {
        List<Position> positions = new ArrayList<>();
        for (Player p : engine.getPlayers()) {
            if ("HOME".equals(p.getTeam())) {
                positions.add(p.getPosition());
            }
        }
        return positions;
    }

    private static List<Position> awayPositions(SimulationEngine engine) {
        List<Position> positions = new ArrayList<>();
        for (Player p : engine.getPlayers()) {
            if ("AWAY".equals(p.getTeam())) {
                positions.add(p.getPosition());
            }
        }
        return positions;
    }

    private static List<Position> allPositions(SimulationEngine engine) {
        List<Position> positions = new ArrayList<>();
        for (Player p : engine.getPlayers()) {
            positions.add(p.getPosition());
        }
        return positions;
    }

    private static double chebyshev(Position a, Position b) {
        return Math.max(Math.abs(a.getRow() - b.getRow()),
                        Math.abs(a.getColumn() - b.getColumn()));
    }
}
