package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

public class DetailedFootballAnalysisTest {

    @Test
    void analyzeFootballRealism() {
        int N = 5;
        
        for (int i = 0; i < N; i++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("HOME", "AWAY");
            MatchResult result = orchestrator.simulate(matchId);
            
            System.out.println("\n" + "=".repeat(100));
            System.out.println("MATCH " + (i+1) + " (" + matchId + "): " + result.homeGoals() + "-" + result.awayGoals());
            System.out.println("=".repeat(100));
            
            analyzeMatch(result);
        }
    }
    
    private void analyzeMatch(MatchResult result) {
        // OSNOVNA STATISTIKA
        System.out.println("\n📊 OSNOVNA STATISTIKA:");
        System.out.printf("  Ukupno šuteva: HOME=%d, AWAY=%d (total=%d)%n", 
            result.homeShots(), result.awayShots(), result.homeShots() + result.awayShots());
        System.out.printf("  Šutevi na gol: HOME=%d, AWAY=%d%n", result.homeShotsOnTarget(), result.awayShotsOnTarget());
        System.out.printf("  Golovi: HOME=%d, AWAY=%d%n", result.homeGoals(), result.awayGoals());
        System.out.printf("  Ofsajd: %d%n", countEvents(result, MatchEvent.MatchEventType.OFFSIDE));
        System.out.printf("  Fauli: HOME=%d, AWAY=%d (total=%d)%n", result.homeFouls(), result.awayFouls(), 
            result.homeFouls() + result.awayFouls());
        System.out.printf("  Korneri: HOME=%d, AWAY=%d%n", result.homeCorners(), result.awayCorners());
        System.out.printf("  Kartoni: HOME Y=%d R=%d, AWAY Y=%d R=%d%n", 
            result.homeYellowCards(), result.homeRedCards(), result.awayYellowCards(), result.awayRedCards());
        System.out.printf("  Poseda: HOME=%.1f%%, AWAY=%.1f%%%n", result.homePossession(), result.awayPossession());
        
        // EVENT LOG - DETALJAN
        System.out.println("\n📋 DETALJAN EVENT LOG:");
        TreeMap<Integer, List<String>> eventsByMinute = new TreeMap<>();
        Map<String, Integer> eventTypes = new HashMap<>();
        
        for (var e : result.events()) {
            if (e instanceof MatchStartEvent || e instanceof MatchEndEvent) continue;
            
            eventTypes.merge(e.type().name(), 1, Integer::sum);
            String desc = formatEventDetailed(e, result);
            eventsByMinute.computeIfAbsent(e.minute(), k -> new ArrayList<>()).add(desc);
        }
        
        for (var entry : eventsByMinute.entrySet()) {
            int min = entry.getKey();
            for (String line : entry.getValue()) {
                System.out.printf("  [%2d'] %s%n", min, line);
            }
        }
        
        System.out.println("\n📈 EVENT DISTRIBUCIJA:");
        eventTypes.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> System.out.printf("    %-15s: %3d%n", e.getKey(), e.getValue()));
        
        // FUDBALSKA ANALIZA
        System.out.println("\n⚽ FUDBALSKA ANALIZA:");
        analyzeFootballLogic(result);
        
        // TRAJANJA I RITAM IGRE
        System.out.println("\n⏱️  ANALIZA TRAJANJA AKCIJA:");
        analyzeActionDurations(result);
        
        // LOGIKA POZICIONIRANJA
        System.out.println("\n🎯 LOGIKA POZICIONIRANJA:");
        analyzePositioning(result);
    }
    
    private void analyzeFootballLogic(MatchResult result) {
        // Ček 1: Da li ima golova?
        if (result.homeGoals() + result.awayGoals() == 0) {
            System.out.println("  ⚠️  PROBLEM: Nema golova - simulacija je prejednoobrazna?");
        } else {
            System.out.println("  ✓ Golovi postoje - mogućnosti se kreiraju");
        }
        
        // Ček 2: Odnos šuteva prema golovima
        int totalShots = result.homeShots() + result.awayShots();
        int totalGoals = result.homeGoals() + result.awayGoals();
        if (totalShots > 0) {
            double shotsPerGoal = (double) totalShots / totalGoals;
            System.out.printf("  • Šuteva po golu: %.1f (realno: 10-15)%n", shotsPerGoal);
            if (shotsPerGoal < 5) {
                System.out.println("    ⚠️  PREVISOKO: Previše golova za broj šuteva (nerealno)");
            } else if (shotsPerGoal > 20) {
                System.out.println("    ⚠️  PRENISKO: Premalo golova za broj šuteva");
            } else {
                System.out.println("    ✓ Razuman odnos");
            }
        }
        
        // Ček 3: Šutevi na gol
        int sotsTotal = result.homeShotsOnTarget() + result.awayShotsOnTarget();
        if (sotsTotal > 0) {
            double conversionRate = (double) totalGoals / sotsTotal;
            System.out.printf("  • Konverzija (golovi/SOT): %.1f%% (realno: 8-15%%)%n", conversionRate * 100);
            if (conversionRate < 0.05) {
                System.out.println("    ⚠️  PRENISKO: Premalo golova iz šuteva na gol");
            } else if (conversionRate > 0.25) {
                System.out.println("    ⚠️  PREVISOKO: Previše golova iz šuteva");
            } else {
                System.out.println("    ✓ Realistična konverzija");
            }
        }
        
        // Ček 4: Ofsajd logika
        long offsides = countEvents(result, MatchEvent.MatchEventType.OFFSIDE);
        if (offsides == 0) {
            System.out.println("  ⚠️  Nema ofsajda - napadi nikad nisu dovoljno dubok?");
        } else {
            System.out.printf("  • Ofsajd: %d (realno: 5-15 po meču)%n", offsides);
        }
        
        // Ček 5: Poseda
        double homePos = result.homePossession();
        double awayPos = result.awayPossession();
        System.out.printf("  • Poseda: HOME=%.1f%% AWAY=%.1f%% (suma=%.1f%% - trebalo bi 100%%)%n", 
            homePos, awayPos, homePos + awayPos);
    }
    
    private void analyzeActionDurations(MatchResult result) {
        // Analiza proslojenih šuteva
        List<ShotEvent> shots = result.events().stream()
            .filter(e -> e instanceof ShotEvent)
            .map(e -> (ShotEvent) e)
            .collect(Collectors.toList());
        
        if (!shots.isEmpty()) {
            double avgTicksBeforeShot = countEventTicksBefore(result, MatchEvent.MatchEventType.SHOT_ON_TARGET, 
                MatchEvent.MatchEventType.SHOT_OFF_TARGET);
            System.out.printf("  • Prosečan broj tikova od početka posede do šuta: ~%.0f ticks%n", avgTicksBeforeShot);
            if (avgTicksBeforeShot < 10) {
                System.out.println("    ⚠️  Veoma brzi šutevi - odigravanja nema?");
            } else if (avgTicksBeforeShot > 50) {
                System.out.println("    ⚠️  Veoma spori šutevi - previše drblanja?");
            } else {
                System.out.println("    ✓ Razumno trajanje posede pre šuta");
            }
        }
        
        // Analiza trajanja pasa
        List<PassEvent> passes = result.events().stream()
            .filter(e -> e instanceof PassEvent)
            .map(e -> (PassEvent) e)
            .collect(Collectors.toList());
        
        if (!passes.isEmpty()) {
            System.out.printf("  • Ukupno paseva: %d%n", passes.size());
            long completed = passes.stream().filter(PassEvent::completed).count();
            long intercepted = passes.stream().filter(PassEvent::intercepted).count();
            System.out.printf("    - Uspešnih: %d (%.1f%%)%n", completed, (double)completed/passes.size()*100);
            System.out.printf("    - Presečenih: %d (%.1f%%)%n", intercepted, (double)intercepted/passes.size()*100);
            
            if ((double)completed/passes.size() < 0.60) {
                System.out.println("    ⚠️  Premalo uspešnih paseva - defanziva previše agresivna?");
            } else {
                System.out.println("    ✓ Razumna preciznost paseva");
            }
        }
    }
    
    private void analyzePositioning(MatchResult result) {
        // Analiza da li se igrači drže blizu lopty / da li se kreću
        long totalTicks = result.tickHistory().size();
        
        // Broji koliko često je ista osoba na lopti
        Map<Long, Integer> carrierTicks = new HashMap<>();
        for (var tick : result.tickHistory()) {
            if (tick.carrierId() != null) {
                carrierTicks.merge(tick.carrierId(), 1, Integer::sum);
            }
        }
        
        if (!carrierTicks.isEmpty()) {
            long maxCarries = carrierTicks.values().stream().mapToLong(Long::valueOf).max().orElse(0);
            long minCarries = carrierTicks.values().stream().mapToLong(Long::valueOf).min().orElse(0);
            double avgCarries = carrierTicks.values().stream().mapToDouble(Integer::doubleValue).average().orElse(0);
            
            System.out.printf("  • Nošenja lopte (ticks sa loptom po igraču): min=%d, max=%d, avg=%.1f%n", 
                minCarries, maxCarries, avgCarries);
            
            if (maxCarries > totalTicks / 3) {
                System.out.println("    ⚠️  Isti igrač previše dugo nosi loptu - nema kruženja?");
            } else {
                System.out.println("    ✓ Loptu drže različiti igrači");
            }
        }
        
        // Teleportacija provera
        boolean teleportation = false;
        double MAX_PACE = 14.0;
        List<TickSnapshot> ticks = result.tickHistory();
        for (int t = 0; t < ticks.size() - 1; t++) {
            var tickA = ticks.get(t);
            var tickB = ticks.get(t + 1);
            for (var snapA : tickA.players()) {
                final long pid = snapA.playerId();
                var snapB = tickB.players().stream()
                    .filter(s -> s.playerId() == pid)
                    .findFirst().orElse(null);
                if (snapB != null) {
                    double dist = snapA.distanceTo(snapB);
                    if (dist > MAX_PACE) {
                        teleportation = true;
                        System.out.printf("  ⚠️  TELEPORTACIJA: Igrač %d pomaknuo se %.1f jedinica u jednom tiku%n", pid, dist);
                    }
                }
            }
        }
        if (!teleportation) {
            System.out.println("  ✓ Nema teleportacija - kretanja su glatka");
        }
    }
    
    private long countEvents(MatchResult result, MatchEvent.MatchEventType type) {
        return result.events().stream().filter(e -> e.type() == type).count();
    }
    
    private double countEventTicksBefore(MatchResult result, MatchEvent.MatchEventType... types) {
        Set<MatchEvent.MatchEventType> typeSet = new HashSet<>(Arrays.asList(types));
        List<Integer> ticks = new ArrayList<>();
        for (var e : result.events()) {
            if (typeSet.contains(e.type())) {
                ticks.add(e.tick());
            }
        }
        if (ticks.isEmpty()) return 0;
        return ticks.stream().mapToInt(Integer::intValue).average().orElse(0);
    }
    
    private String formatEventDetailed(MatchEvent e, MatchResult result) {
        return switch (e) {
            case GoalEvent g -> 
                String.format("⚽ GOAL: %s (xG=%.3f)", g.scorerName(), g.xG());
            case ShotEvent s -> {
                String kind = s.saved() ? "SAVED" : (s.onTarget() ? "ON TARGET" : "MISSED");
                yield String.format("🔴 SHOT: %s %s (xG=%.3f)", s.shooterName(), kind, s.xG());
            }
            case PassEvent p -> {
                String kind = p.intercepted() ? "INTERCEPTED" : "COMPLETE";
                yield String.format("🔵 PASS: %s→%s %s", p.passerName(), 
                    (p.receiverName() != null ? p.receiverName() : "?"), kind);
            }
            case DuelEvent d ->
                String.format("💥 DUEL: %s vs %s (%s) - %s wins", d.player1Name(), d.player2Name(), 
                    d.duelType(), d.attackerWon() ? d.player1Name() : d.player2Name());
            case FoulEvent f -> {
                String where = f.penaltyFoul() ? "PENALTY!" : "FK";
                yield String.format("🟡 FOUL: %s on %s (%s)", f.takerName(), f.victimName(), where);
            }
            case CardEvent c -> {
                String card = c.cardType() == CardEvent.CardType.YELLOW ? "🟨 YELLOW" : "🟥 RED";
                yield String.format("%s: %s", card, c.playerName());
            }
            case OffsideEvent o ->
                String.format("🚩 OFFSIDE: %s", o.playerName());
            case SetPieceEvent sp ->
                String.format("📐 %s: %s", sp.setPieceType().name(), 
                    (sp.takerName() != null ? sp.takerName() : "?"));
            case PenaltyEvent p ->
                String.format("🎯 PENALTY: %s scored=%s", p.takerName(), p.scored());
            case InjuryEvent i ->
                String.format("🆘 INJURY: %s", i.playerName());
            case SubstitutionEvent s ->
                String.format("🔄 SUB: %s ⬅️ %s", s.playerOutName(), s.playerInName());
            default -> e.type().name() + " (tick=" + e.tick() + ")";
        };
    }
}
