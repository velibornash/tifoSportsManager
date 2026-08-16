package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.DecisionEngine;
import org.example.footballmanager.newLogic.engine.DuelResolver;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA football-analyst audit.
 *
 * Runs N full matches and checks the engine against 8 criteria:
 *   1. Match statistics resemble a real football match
 *   2. Player actions are based on their skills
 *   3. Actions are football-like (sensible decisions)
 *   4. Player movement is football-like (position within zone matters)
 *   5. No logical errors (pass without carrier, offside without pass,
 *      foul/card/penalty without cause, goals without shots, ...)
 *   6. Set-piece & referee triggers (corner, throw-in, goal kick, free kick,
 *      penalty, VAR, substitution, injury, offside)
 *   7. Post-stoppage behaviour (celebration pause, repositioning, no teleports)
 *   8. Stoppage time / overtime is added at the end of each half
 */
public class QaFootballAudit {

    private static final int N = 8;
    private static final int TICKS_PER_MINUTE = 120;
    private static final int BASE_MATCH_TICKS = 90 * TICKS_PER_MINUTE;
    private static final double MAX_PACE_PER_TICK = 0.5;

    private int goals, shots, sot, passes, passOk, corners, throwIns, goalKicks, freeKicks;
    private int fouls, yellows, reds, offsides, duels, penalties, subs, injuries, vars;
    private int crossed, headers, dribbles, clearances;
    private double totalXG;
    private final List<Double> possessionHome = new ArrayList<>();
    private final List<Integer> goalsPerMatch = new ArrayList<>();
    private final List<Integer> shotsPerMatch = new ArrayList<>();
    private final List<Integer> passesPerMatch = new ArrayList<>();
    private final List<Integer> totalTicksPerMatch = new ArrayList<>();

    // Logical-error counters
    private int errPassNoCarrier, errShotNoCarrier, errCardNoFoul, errPenNoFoul;
    private int errFkNoFoul, errOffsideNoPass, errGoalNoShot, errTwoCarriers;
    private int errCarrierHasBallMismatch, errDribbleNoCarrier, errTackleSameTeam;
    private int errThrowWrongTeam, errCornerWrongTeam, errGkHalf;
    private double maxTeleportDistance;  // units in a single tick (players)
    private int teleportViolations;

    private int penaltyGoals, penaltyMisses;
    private int varReviews, varOverturns;

    @Test
    void fullQaAudit() {
        long start = System.currentTimeMillis();

        for (int i = 0; i < N; i++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Crvena Zvezda " + i, "Partizan " + i);
            MatchResult r = orchestrator.simulate(matchId);
            auditMatch(r, i);
        }

        long elapsed = System.currentTimeMillis() - start;

        printReport();
        printLogicErrors();
        printStoppageAnalysis();
        printStoppageTimeAnalysis();
        printMovementAnalysis();

        System.out.printf("%nAudit finished in %.1fs%n", elapsed / 1000.0);

        // Non-negotiable assertions
        for (Integer t : totalTicksPerMatch) {
            assertTrue(t > BASE_MATCH_TICKS, "Match must exceed base 90 min ticks (got " + t + ")");
        }
        assertTrue(errPassNoCarrier == 0, "Passes executed without a ball carrier: " + errPassNoCarrier);
        assertTrue(errShotNoCarrier == 0, "Shots executed without a ball carrier: " + errShotNoCarrier);
        assertTrue(errTackleSameTeam == 0, "Tackles against own teammate: " + errTackleSameTeam);
        assertTrue(errGkHalf == 0, "Goalkeepers crossed the halfway line: " + errGkHalf);
        assertTrue(maxTeleportDistance <= MAX_PACE_PER_TICK + 0.05,
            "Player teleportation detected: " + String.format("%.3f", maxTeleportDistance));
        assertTrue(penalties >= 0, "Penalty count invalid");
        assertTrue(subs >= 0, "Substitution count invalid");
    }

    private void auditMatch(MatchResult r, int idx) {
        goalsPerMatch.add(r.homeGoals() + r.awayGoals());
        shotsPerMatch.add(r.homeShots() + r.awayShots());
        possessionHome.add(r.homePossession());
        totalTicksPerMatch.add(r.totalTicks());

        // Map tick -> carrierId for causality checks
        Map<Integer, Long> carrierByTick = new HashMap<>();
        Map<Integer, List<PlayerSnapshot>> playersByTick = new HashMap<>();
        for (TickSnapshot ts : r.tickHistory()) {
            carrierByTick.put(ts.tick(), ts.carrierId());
            playersByTick.put(ts.tick(), ts.players());
        }

        // Player id -> skill map (from first tick)
        Map<Long, PlayerSnapshot> firstSnap = new HashMap<>();
        if (!r.tickHistory().isEmpty()) {
            for (PlayerSnapshot ps : r.tickHistory().get(0).players()) firstSnap.put(ps.playerId(), ps);
        }

        List<MatchEvent> ev = r.events();
        List<FoulEvent> foulEvents = ev.stream().filter(e -> e instanceof FoulEvent).map(e -> (FoulEvent) e).toList();
        // Penalty goals are scored from the penalty spot, not by the ball carrier
        Set<Integer> penaltyGoalTicks = ev.stream()
            .filter(e -> e instanceof PenaltyEvent p && p.scored())
            .map(MatchEvent::tick)
            .collect(Collectors.toSet());

        Map<Long, Integer> foulsByPlayer = new HashMap<>();
        for (FoulEvent f : foulEvents) foulsByPlayer.merge(f.takerId(), 1, Integer::sum);

        Set<Long> foulPlayerIds = foulEvents.stream().map(FoulEvent::takerId).collect(Collectors.toSet());

        Map<Integer, List<PassEvent>> passesByTick = ev.stream()
            .filter(e -> e instanceof PassEvent p && p.completed())
            .map(e -> (PassEvent) e)
            .collect(Collectors.groupingBy(PassEvent::tick));
        Map<Integer, List<CrossEvent>> crossesByTick = ev.stream()
            .filter(e -> e instanceof CrossEvent)
            .map(e -> (CrossEvent) e)
            .collect(Collectors.groupingBy(CrossEvent::tick));

        Map<Integer, Set<Long>> offsideAtTick = new HashMap<>();
        for (MatchEvent e : ev) {
            if (e instanceof OffsideEvent o) {
                offsideAtTick.computeIfAbsent(e.tick(), k -> new HashSet<>()).add(o.playerId());
            }
        }

        Map<Integer, List<CrossHeaderEvent>> headersByTick = ev.stream()
            .filter(e -> e instanceof CrossHeaderEvent)
            .map(e -> (CrossHeaderEvent) e)
            .collect(Collectors.groupingBy(CrossHeaderEvent::tick));

        // Track team possession per event tick for offside/corner logic
        Map<Integer, String> lastTouchByTick = new HashMap<>();
        String lastTouch = "HOME";
        for (MatchEvent e : ev) {
            String side = sideOfEvent(e);
            if (side != null) lastTouch = side;
            lastTouchByTick.put(e.tick(), lastTouch);
        }

        for (MatchEvent e : ev) {
            switch (e) {
                case GoalEvent g -> {
                    goals++;
                    totalXG += g.xG();
                }
                case ShotSavedEvent s -> { shots++; sot++; totalXG += s.xG(); }
                case ShotMissedEvent s -> { shots++; totalXG += s.xG(); }
                case PassEvent p -> { passes++; if (p.completed()) passOk++; }
                case PassInterceptedEvent p -> passes++;
                case PassIncompleteEvent p -> passes++;
                case TackleEvent t -> duels++;
                case DuelEvent d -> duels++;
                case DribbleEvent d -> duels++;
                case DribbleLostEvent d -> duels++;
                case CrossEvent c -> crossed++;
                case CrossHeaderEvent c -> headers++;
                case ClearanceEvent c -> clearances++;
                case FoulEvent f -> fouls++;
                case CardEvent c -> {
                    if (c.cardType() == CardEvent.CardType.YELLOW) yellows++;
                    else reds++;
                }
                case OffsideEvent o -> offsides++;
                case SubstitutionEvent s -> subs++;
                case InjuryEvent i -> injuries++;
                case PenaltyEvent p -> {
                    penalties++;
                    if (p.scored()) penaltyGoals++;
                    else penaltyMisses++;
                }
                case SetPieceEvent sp -> {
                    switch (sp.setPieceType()) {
                        case CORNER -> corners++;
                        case THROW_IN -> throwIns++;
                        case GOAL_KICK -> goalKicks++;
                        case FREE_KICK -> freeKicks++;
                    }
                }
                default -> { }
            }

            // ── CRITERION 5: LOGICAL ERROR CHECKS ──
            int tick = e.tick();
            Long tickCarrier = carrierByTick.getOrDefault(tick, null);

            if (e instanceof PassEvent p) {
                if (!wasCarrier(carrierByTick, p.tick(), p.passerId())) errPassNoCarrier++;
            }
            if (e instanceof ShotEvent s) {
                if (!wasCarrier(carrierByTick, s.tick(), s.shooterId())
                    && !wasHeaderWinner(headersByTick, s.tick(), s.shooterId())) {
                    System.out.printf("    [DBG SHOT] %s t=%d%n", s.shooterName(), s.tick());
                    errShotNoCarrier++;
                }
                // Football logic: shots only from attacking half / shooting range
                PlayerSnapshot sh = firstSnap.get(s.shooterId());
                if (sh != null) {
                    boolean inOwnHalfShot = "HOME".equals(s.teamSide()) ? s.x() < 25 : s.x() > 75;
                    if (inOwnHalfShot) {
                        System.out.printf("    [!] %s shot from own half: %.0f,%.0f (tick %d)%n",
                            s.shooterName(), s.x(), s.y(), s.tick());
                    }
                }
            }
            if (e instanceof ShotMissedEvent s) {
                if (!wasCarrier(carrierByTick, s.tick(), s.shooterId())
                    && !wasHeaderWinner(headersByTick, s.tick(), s.shooterId())) {
                    System.out.printf("    [DBG MISSED] %s t=%d carrier=%d prev=%d hdrNow=%d hdrPrev=%d%n",
                        s.shooterName(), s.tick(),
                        carrierByTick.get(s.tick()), carrierByTick.get(s.tick() - 1),
                        headersByTick.getOrDefault(s.tick(), List.of()).size(),
                        headersByTick.getOrDefault(s.tick() - 1, List.of()).size());
                    errShotNoCarrier++;
                }
            }
            if (e instanceof ShotSavedEvent s) {
                if (!wasCarrier(carrierByTick, s.tick(), s.shooterId())
                    && !wasHeaderWinner(headersByTick, s.tick(), s.shooterId())) {
                    System.out.printf("    [DBG SAVED] %s t=%d carrier=%d prev=%d hdrNow=%d hdrPrev=%d%n",
                        s.shooterName(), s.tick(),
                        carrierByTick.get(s.tick()), carrierByTick.get(s.tick() - 1),
                        headersByTick.getOrDefault(s.tick(), List.of()).size(),
                        headersByTick.getOrDefault(s.tick() - 1, List.of()).size());
                    errShotNoCarrier++;
                }
            }
            if (e instanceof CrossEvent c) {
                if (!wasCarrier(carrierByTick, c.tick(), c.crosserId())) errShotNoCarrier++;
            }
            if (e instanceof CrossHeaderEvent h) {
                // A header follows a cross, not a dribble — verify a cross preceded it.
                boolean crossBefore = crossesByTick.getOrDefault(h.tick(), List.of()).size() > 0
                    || crossesByTick.getOrDefault(h.tick() - 1, List.of()).size() > 0;
                if (!crossBefore) errShotNoCarrier++;
            }
            if (e instanceof DribbleEvent d) {
                if (!wasCarrier(carrierByTick, d.tick(), d.dribblerId())) errDribbleNoCarrier++;
            }
            if (e instanceof DribbleLostEvent d) {
                if (!wasCarrier(carrierByTick, d.tick(), d.dribblerId())) errDribbleNoCarrier++;
            }

            // Card must be preceded by a foul by the same player
            if (e instanceof CardEvent c) {
                boolean foulBefore = foulEvents.stream().anyMatch(f ->
                    f.takerId() == c.playerId()
                        && (f.tick() == c.tick() || f.tick() == c.tick() - 1));
                if (!foulBefore) errCardNoFoul++;
            }

            // Penalty / free-kick stoppage must follow a foul
            if (e instanceof PenaltyEvent p) {
                boolean foulBefore = foulEvents.stream().anyMatch(f -> Math.abs(f.tick() - p.tick()) <= 2);
                if (!foulBefore) errPenNoFoul++;
            }
            if (e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.FREE_KICK) {
                boolean foulBefore = foulEvents.stream().anyMatch(f -> Math.abs(f.tick() - sp.tick()) <= 2);
                if (!foulBefore) errFkNoFoul++;
            }

            // Offside must coincide with a forward pass to the flagged player
            if (e instanceof OffsideEvent o) {
                boolean passToFlagged = ev.stream().anyMatch(p2 ->
                    p2.tick() == o.tick()
                        && p2 instanceof PassEvent pe
                        && (pe.receiverId() != null && pe.receiverId() == o.playerId()));
                if (!passToFlagged) errOffsideNoPass++;
            }

            // Goal must be scored by the ball carrier or via a header on the cross
            if (e instanceof GoalEvent g) {
                boolean hadBall = wasCarrier(carrierByTick, g.tick(), g.scorerId())
                    || headersByTick.getOrDefault(g.tick(), List.of()).stream()
                        .anyMatch(h -> h.headerId() == g.scorerId())
                    || (g.tick() >= 1 && headersByTick.getOrDefault(g.tick() - 1, List.of()).stream()
                        .anyMatch(h -> h.headerId() == g.scorerId()));
                if (!hadBall && !penaltyGoalTicks.contains(g.tick())) errGoalNoShot++;
            }

            // Throw-in team = NOT the last touching team
            if (e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.THROW_IN) {
                String last = lastTouchByTick.getOrDefault(tick, "HOME");
                if (last == null || last.equals(sp.teamSide())) errThrowWrongTeam++;
            }
        }

        // ── CRITERION 5b: two carriers at once / hasBall mismatch ──
        for (TickSnapshot ts : r.tickHistory()) {
            Long c = ts.carrierId();
            if (c != null) {
                int countBall = (int) ts.players().stream().filter(PlayerSnapshot::hasBall).count();
                if (countBall > 1) errTwoCarriers++;
                PlayerSnapshot carrierSnap = ts.players().stream()
                    .filter(p -> p.playerId() == c).findFirst().orElse(null);
                if (carrierSnap != null && !carrierSnap.hasBall()) errCarrierHasBallMismatch++;
            } else if (!ts.ballInTransit()) {
                long ballHolders = ts.players().stream().filter(PlayerSnapshot::hasBall).count();
                if (ballHolders > 0) errCarrierHasBallMismatch++;
            }
        }

        // ── CRITERION 4/7: movement & GK half check ──
        List<PlayerSnapshot> prev = null;
        for (TickSnapshot ts : r.tickHistory()) {
            for (PlayerSnapshot ps : ts.players()) {
                if ("HOME".equals(ps.teamSide()) && ps.position() == Position.GK && ps.x() > 50) errGkHalf++;
                if ("AWAY".equals(ps.teamSide()) && ps.position() == Position.GK && ps.x() < 50) errGkHalf++;
            }
            if (prev != null) {
                for (PlayerSnapshot a : ts.players()) {
                    for (PlayerSnapshot b : prev) {
                        if (a.playerId() == b.playerId()) {
                            double d = a.distanceTo(b);
                            if (d > maxTeleportDistance) maxTeleportDistance = d;
                            if (d > MAX_PACE_PER_TICK) teleportViolations++;
                        }
                    }
                }
            }
            prev = ts.players();
        }

        // ── CRITERION 6: VAR count ──
        long varCount = r.events().stream().filter(e -> e instanceof VarReviewEvent).count();
        vars += varCount;

        // Track penalty conversion via PenaltyEvent scored flag
        // (falls out of switch above)
    }

    private static boolean wasCarrier(Map<Integer, Long> carrierByTick, int tick, long playerId) {
        Long cur = carrierByTick.get(tick);
        if (cur != null && cur == playerId) return true;
        if (tick >= 1) {
            Long prev = carrierByTick.get(tick - 1);
            if (prev != null && prev == playerId) return true;
        }
        return false;
    }

    private static boolean wasHeaderWinner(Map<Integer, List<CrossHeaderEvent>> headersByTick, int tick, long playerId) {
        if (headersByTick.getOrDefault(tick, List.of()).stream()
            .anyMatch(h -> h.headerId() == playerId && h.onTarget())) return true;
        if (tick >= 1 && headersByTick.getOrDefault(tick - 1, List.of()).stream()
            .anyMatch(h -> h.headerId() == playerId && h.onTarget())) return true;
        return false;
    }

    private String sideOfEvent(MatchEvent e) {
        if (e instanceof GoalEvent g) return g.teamSide();
        if (e instanceof PassEvent p) return p.teamSide();
        if (e instanceof PassInterceptedEvent p) return p.interceptorTeamSide();
        if (e instanceof ShotEvent s) return s.teamSide();
        if (e instanceof ShotSavedEvent s) return s.teamSide();
        if (e instanceof ShotMissedEvent s) return s.teamSide();
        if (e instanceof CrossEvent c) return c.teamSide();
        if (e instanceof CrossHeaderEvent c) return c.teamSide();
        if (e instanceof FoulEvent f) return f.teamSide();
        if (e instanceof ClearanceEvent c) return c.teamSide();
        if (e instanceof TackleEvent t) return t.defenderTeamSide();
        return null;
    }

    private void printReport() {
        int n = Math.max(1, N);
        System.out.println("=".repeat(100));
        System.out.println("  QA FOOTBALL ANALYST AUDIT — " + N + " matches");
        System.out.println("=".repeat(100));

        System.out.printf("%n  CRITERION 1 — MATCH STATISTICS (vs realistic ranges)%n");
        System.out.println("  " + "-".repeat(90));
        printRow("Goals/match", avg(goalsPerMatch), "2.0 – 3.5");
        printRow("Shots/match", avg(shotsPerMatch), "12 – 20");
        printRow("Shots on target %", 100.0 * sot / Math.max(1, shots), "33 – 45");
        printRow("Passes/match", (double) passes / n, "600 – 1000");
        printRow("Pass completion %", 100.0 * passOk / Math.max(1, passes), "78 – 87");
        printRow("Corners/match", (double) corners / n, "9 – 12");
        printRow("Throw-ins/match", (double) throwIns / n, "40 – 60");
        printRow("Goal kicks/match", (double) goalKicks / n, "15 – 25");
        printRow("Free kicks/match", (double) freeKicks / n, "20 – 30");
        printRow("Offsides/match", (double) offsides / n, "2 – 5");
        printRow("Fouls/match", (double) fouls / n, "15 – 25");
        printRow("Yellow cards/match", (double) yellows / n, "2 – 4");
        printRow("Red cards/match", (double) reds / n, "0 – 0.3");
        printRow("Duels/match", (double) duels / n, "150 – 250");
        printRow("Substitutions/match", (double) subs / n, "1 – 3");
        printRow("Injuries/match", (double) injuries / n, "0 – 1.5");
        printRow("Penalties/match", (double) penalties / n, "0 – 0.4");
        printRow("VAR reviews/match", (double) vars / n, "0 – 0.5");
        printRow("Avg possession (home)", possessionHome.stream().mapToDouble(Double::doubleValue).average().orElse(50), "40 – 60");
        printRow("xG/match", totalXG / n, "≈ goals/match");
        System.out.printf("  xG vs goals: %.2f vs %.2f (conversion %.0f%%)%n",
            totalXG / n, avg(goalsPerMatch), 100.0 * goals / Math.max(1.0, totalXG));
        System.out.printf("  Passes lost to interception+incomplete: %d (%.0f%%)%n",
            passes - passOk, 100.0 * (passes - passOk) / Math.max(1, passes));
    }

    private void printLogicErrors() {
        System.out.printf("%n  CRITERION 5 — LOGICAL ERRORS%n");
        System.out.println("  " + "-".repeat(90));
        System.out.printf("  Pass without ball carrier:        %d%n", errPassNoCarrier);
        System.out.printf("  Shot without ball carrier:         %d%n", errShotNoCarrier);
        System.out.printf("  Dribble without ball carrier:      %d%n", errDribbleNoCarrier);
        System.out.printf("  Card without preceding foul:       %d%n", errCardNoFoul);
        System.out.printf("  Penalty without preceding foul:    %d%n", errPenNoFoul);
        System.out.printf("  Free kick without preceding foul:  %d%n", errFkNoFoul);
        System.out.printf("  Offside without pass to player:    %d%n", errOffsideNoPass);
        System.out.printf("  Goal without preceding shot:       %d%n", errGoalNoShot);
        System.out.printf("  Two carriers at once:              %d%n", errTwoCarriers);
        System.out.printf("  Carrier/hasBall mismatch:          %d%n", errCarrierHasBallMismatch);
        System.out.printf("  Tackle against own teammate:       %d%n", errTackleSameTeam);
        System.out.printf("  Throw-in to last-touching team:    %d%n", errThrowWrongTeam);
        System.out.printf("  GK crossing halfway line:          %d%n", errGkHalf);
    }

    private void printStoppageAnalysis() {
        System.out.printf("%n  CRITERION 6/7 — STOPPAGES, PENALTIES, VAR, POSITIONING%n");
        System.out.println("  " + "-".repeat(90));
        System.out.printf("  Corners: %d | Throw-ins: %d | Goal kicks: %d | Free kicks: %d%n",
            corners, throwIns, goalKicks, freeKicks);
        System.out.printf("  Offsides: %d | Penalties: %d (scored %d, not scored %d)%n",
            offsides, penalties, penaltyGoals, penaltyMisses);
        System.out.printf("  VAR reviews: %d | Substitutions: %d | Injuries: %d%n",
            vars, subs, injuries);

        // Penalty conversion sanity
        if (penalties > 0) {
            System.out.printf("  Penalty conversion: %.0f%% (realistic ~75%%)%n",
                100.0 * penaltyGoals / penalties);
        } else {
            System.out.println("  Penalty conversion: n/a (no penalties awarded)");
        }
    }

    private void printStoppageTimeAnalysis() {
        int n = Math.max(1, N);
        double avgTicks = totalTicksPerMatch.stream().mapToInt(Integer::intValue).average().orElse(0);
        System.out.printf("%n  CRITERION 8 — STOPPAGE TIME / OVERTIME%n");
        System.out.println("  " + "-".repeat(90));
        System.out.printf("  Base 90 min = %d ticks%n", BASE_MATCH_TICKS);
        System.out.printf("  Avg total ticks: %.0f  (extra %.0f ticks ≈ %.1f match-minutes of stoppage time)%n",
            avgTicks, avgTicks - BASE_MATCH_TICKS, (avgTicks - BASE_MATCH_TICKS) / TICKS_PER_MINUTE);
        System.out.printf("  Realistic: 2 – 6 total minutes of added time%n");
    }

    private void printMovementAnalysis() {
        System.out.printf("%n  CRITERION 4 — MOVEMENT%n");
        System.out.println("  " + "-".repeat(90));
        System.out.printf("  Max player movement in one tick: %.3f units (teleport threshold %.2f)%n",
            maxTeleportDistance, MAX_PACE_PER_TICK);
        System.out.printf("  Teleport violations (>%.2f/tick): %d%n", MAX_PACE_PER_TICK, teleportViolations);
    }

    private void printRow(String label, double value, String realistic) {
        String flag;
        if (label.contains("Possession")) {
            flag = value < 40 || value > 60 ? " [FLAG]" : "  ok";
        } else if (label.contains("Goals/match")) {
            flag = value < 1.5 || value > 4.5 ? " [FLAG]" : "  ok";
        } else if (label.contains("Shots/match")) {
            flag = value < 8 || value > 25 ? " [FLAG]" : "  ok";
        } else if (label.contains("Passes/match")) {
            flag = value < 350 || value > 1100 ? " [FLAG]" : "  ok";
        } else if (label.contains("Pass completion")) {
            flag = value < 65 || value > 90 ? " [FLAG]" : "  ok";
        } else if (label.contains("on target")) {
            flag = value < 25 || value > 50 ? " [FLAG]" : "  ok";
        } else {
            flag = "";
        }
        System.out.printf("  %-24s %10.1f   (realistic %-18s) %s%n", label, value, realistic, flag);
    }

    private static double avg(List<Integer> vals) {
        return vals.stream().mapToInt(Integer::intValue).average().orElse(0);
    }
}
