package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

public class NewMatchSimulatorTest {

    @Test
    void runFullMatchAndPrint() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        // ── HEADER ──
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║              MATCH SIMULATION RESULT               ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ── SCORE ──
        System.out.println("  " + result.homeGoals() + " - " + result.awayGoals());
        System.out.println();

        // ── STATS ──
        System.out.println("  ┌─────────────────────────────────────────────────┐");
        System.out.println("  │                   MATCH STATS                  │");
        System.out.println("  ├──────────────────────┬──────────────────────────┤");
        System.out.printf("  │ %-20s │ %8s                  │%n", "Statistic", "Value");
        System.out.println("  ├──────────────────────┼──────────────────────────┤");
        System.out.printf("  │ %-20s │ %8d                  │%n", "Total Ticks", result.totalTicks());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Ticks/Minute", result.ticksPerMinute());
        System.out.printf("  │ %-20s │ %8.1f%%              │%n", "Possession (Home)", result.homePossession());
        System.out.printf("  │ %-20s │ %8.1f%%              │%n", "Possession (Away)", result.awayPossession());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Shots (Home)", result.homeShots());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Shots (Away)", result.awayShots());
        System.out.printf("  │ %-20s │ %8d                  │%n", "SOT (Home)", result.homeShotsOnTarget());
        System.out.printf("  │ %-20s │ %8d                  │%n", "SOT (Away)", result.awayShotsOnTarget());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Fouls (Home)", result.homeFouls());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Fouls (Away)", result.awayFouls());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Corners (Home)", result.homeCorners());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Corners (Away)", result.awayCorners());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Yellow (Home)", result.homeYellowCards());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Yellow (Away)", result.awayYellowCards());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Avg Rating (Home)", (int) result.homeAvgRating());
        System.out.printf("  │ %-20s │ %8d                  │%n", "Avg Rating (Away)", (int) result.awayAvgRating());
        System.out.println("  └──────────────────────┴──────────────────────────┘");
        System.out.println();

        // ── EVENT LOG ──
        System.out.println("  ┌─────────────────────────────────────────────────┐");
        System.out.println("  │                   EVENT LOG                    │");
        System.out.println("  └─────────────────────────────────────────────────┘");
        System.out.println();

        TreeMap<Integer, List<String>> eventsByMinute = new TreeMap<>();
        for (var e : result.events()) {
            if (e instanceof MatchStartEvent || e instanceof MatchEndEvent) continue;
            eventsByMinute.computeIfAbsent(e.minute(), k -> new ArrayList<>())
                .add(formatEvent(e));
        }

        for (var entry : eventsByMinute.entrySet()) {
            int min = entry.getKey();
            for (String line : entry.getValue()) {
                System.out.println("  [" + min + "'] " + line);
            }
        }
        System.out.println();

        // ── TICK SUMMARY ──
        System.out.println("  ┌─────────────────────────────────────────────────┐");
        System.out.println("  │                  TICK SUMMARY                  │");
        System.out.println("  └─────────────────────────────────────────────────┘");
        System.out.println();

        long posHome = result.tickHistory().stream()
            .filter(t -> {
                if (t.ballInTransit()) return false;
                if (t.carrierId() == null) return false;
                String team = resolveTeamAtTick(t, result);
                return "HOME".equals(team);
            })
            .count();
        long posAway = result.tickHistory().stream()
            .filter(t -> {
                if (t.ballInTransit()) return false;
                if (t.carrierId() == null) return false;
                String team = resolveTeamAtTick(t, result);
                return "AWAY".equals(team);
            })
            .count();
        long noPoss = result.tickHistory().size() - posHome - posAway;

        System.out.printf("  %-20s %,10d%n", "Home possession ticks", posHome);
        System.out.printf("  %-20s %,10d%n", "Away possession ticks", posAway);
        System.out.printf("  %-20s %,10d%n", "Loose ball / transit", noPoss);
        System.out.printf("  %-20s %,10d%n", "Total ticks", result.tickHistory().size());

        // Count events by type
        System.out.println();
        System.out.println("  Event distribution:");
        Map<String, Long> eventCounts = result.events().stream()
            .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
            .collect(Collectors.groupingBy(
                e -> e.type().name(),
                Collectors.counting()
            ));
        eventCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> System.out.printf("    %-20s %3d%n", e.getKey(), e.getValue()));

        // ── BALL POSITIONS (first 10, last 10) ──
        System.out.println();
        System.out.println("  Ball position samples:");
        List<TickSnapshot> ticks = result.tickHistory();
        for (int i = 0; i < Math.min(10, ticks.size()); i++) {
            var t = ticks.get(i);
            System.out.printf("    tick=%-4d min=%2d  ball=(%5.1f, %5.1f)  carrier=%s%n",
                t.tick(), t.minute(), t.ball().x(), t.ball().y(),
                t.carrierId() != null ? t.carrierId() : "null");
        }
        if (ticks.size() > 20) {
            System.out.println("    ...");
            for (int i = Math.max(10, ticks.size() - 10); i < ticks.size(); i++) {
                var t = ticks.get(i);
                System.out.printf("    tick=%-4d min=%2d  ball=(%5.1f, %5.1f)  carrier=%s%n",
                    t.tick(), t.minute(), t.ball().x(), t.ball().y(),
                    t.carrierId() != null ? t.carrierId() : "null");
            }
        }

        // ── ASSERTIONS ──
        assert result.homeGoals() >= 0 : "Home goals should be >= 0";
        assert result.awayGoals() >= 0 : "Away goals should be >= 0";
        assert result.totalTicks() > 0 : "Should have ticks";
        assert !result.events().isEmpty() : "Should have events";
        assert result.homeShots() + result.awayShots() > 0 : "Should have shots";
        assert Math.abs(result.homePossession() + result.awayPossession() - 100.0) < 1.0
            : "Possession should sum to ~100%";

        assert result.homeGoals() + result.awayGoals() >= 1
            : "Full match should produce at least one goal (got " + result.homeGoals() + "-" + result.awayGoals() + ")";
        for (var e : result.events()) {
            if (e instanceof DuelEvent d) {
                assert d.player1Id() != d.player2Id()
                    : "Self-duel detected: " + d.player1Name() + " (id=" + d.player1Id() + ")";
                assert !d.player1Name().equals(d.player2Name())
                    : "Same-name duel detected: " + d.player1Name();
            }
        }

        // No-teleportation check: every player move per tick must be ≤ max possible
        // Theoretical max: DRIBBLE_MULT(1.6) * PACE_STEP_MAX(4.2) * modifier(1.18) + blend(4.2) ≈ 12.0
        double MAX_PACE_PER_TICK = 14.0;
        var th = result.tickHistory();
        for (int t = 0; t < th.size() - 1; t++) {
            var tickA = th.get(t);
            var tickB = th.get(t + 1);
            for (var snapA : tickA.players()) {
                final long pid = snapA.playerId();
                var snapB = tickB.players().stream()
                    .filter(s -> s.playerId() == pid)
                    .findFirst().orElse(null);
                if (snapB == null) continue;
                double dist = snapA.distanceTo(snapB);
                assert dist <= MAX_PACE_PER_TICK
                    : "Teleportation! CPlayer " + pid + " moved " + String.format("%.1f", dist)
                    + " units in one tick (tick " + tickA.tick() + " -> " + tickB.tick() + ")";
            }
        }

        System.out.println();
        System.out.println("  ✓ All assertions passed.");
        System.out.printf("  ✓ No-teleportation check: max dist per tick ≤ %.1f%n", MAX_PACE_PER_TICK);
        System.out.println();
    }

    private String formatEvent(MatchEvent e) {
        return switch (e) {
            case GoalEvent g ->
                "⚽ GOAL! " + g.scorerName() + " (" + g.teamSide() + ")  [" + g.homeScoreAfter() + "-" + g.awayScoreAfter() + "]  xG=" + String.format("%.3f", g.xG());
            case ShotEvent s -> {
                String kind = s.saved() ? "SAVED" : (s.onTarget() ? "ON TARGET" : "MISSED");
                yield "🔴 SHOT: " + s.shooterName() + " (" + s.teamSide() + ")  " + kind + "  xG=" + String.format("%.3f", s.xG());
            }
            case PassEvent p -> {
                String kind = p.intercepted() ? "INTERCEPTED" : "PASS";
                yield "🔵 " + kind + ": " + p.passerName() + " → " + (p.receiverName() != null ? p.receiverName() : "?") + " (" + p.teamSide() + ")";
            }
            case DuelEvent d ->
                "💥 DUEL: " + d.player1Name() + " vs " + d.player2Name() + " (" + d.duelType() + ")  winner=" + (d.attackerWon() ? d.player1Name() : d.player2Name());
            case FoulEvent f -> {
                String where = f.penaltyFoul() ? "PENALTY!" : "FK";
                yield "🟡 FOUL: " + f.takerName() + " on " + f.victimName() + " (" + f.teamSide() + ")  " + where;
            }
            case CardEvent c -> {
                String card = c.cardType() == CardEvent.CardType.YELLOW ? "YELLOW" : "RED";
                yield "🟨 " + card + " CARD: " + c.playerName() + " (" + c.teamSide() + ")";
            }
            case OffsideEvent o ->
                "🚩 OFFSIDE: " + o.playerName() + " (" + o.teamSide() + ")";
            case SetPieceEvent sp ->
                "📐 " + sp.setPieceType().name() + ": " + (sp.takerName() != null ? sp.takerName() : "?") + " (" + sp.teamSide() + ")";
            case PenaltyEvent p ->
                "🎯 PENALTY: " + p.takerName() + " (" + p.teamSide() + ")  scored=" + p.scored();
            case InjuryEvent i ->
                "🆘 INJURY: " + i.playerName() + " (" + i.teamSide() + ")";
            case SubstitutionEvent s ->
                "🔄 SUB: " + s.playerOutName() + " ⬅️ " + s.playerInName() + " (" + s.teamSide() + ")";
            default -> e.type().name() + " (tick=" + e.tick() + ")";
        };
    }

    private String resolveTeamAtTick(TickSnapshot tick, MatchResult result) {
        if (tick.carrierId() == null) return null;
        return tick.players().stream()
            .filter(s -> s.playerId() == tick.carrierId())
            .findFirst()
            .map(PlayerSnapshot::teamSide)
            .orElse(null);
    }
}
