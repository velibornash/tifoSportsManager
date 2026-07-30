package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import static org.example.footballmanager.newLogic.model.Position.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;
import java.util.OptionalDouble;

public class FootballIntegrationTest {

    @Test
    void comprehensiveFootballAnalysis() {
        int N = 3;
        List<MatchResult> results = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("HOME", "AWAY");
            MatchResult result = orchestrator.simulate(matchId);
            results.add(result);

            System.out.println("\n" + "=".repeat(120));
            System.out.println("MEČ " + (i + 1) + " (ID=" + matchId + "): "
                + result.homeGoals() + "-" + result.awayGoals());
            System.out.println("=".repeat(120));
            printFullAnalysis(result);
        }

        System.out.println("\n" + "#".repeat(120));
        System.out.println(" ZAVRŠNI IZVEŠTAJ — PROSEK 3 MEČA");
        System.out.println("#".repeat(120));
        printAggregate(results);
    }

    // ═══════════════════════════════════════════════════════════
    // 1. EVENT BROJANJE
    // ═══════════════════════════════════════════════════════════

    private void printFullAnalysis(MatchResult r) {
        var events = r.events();

        // ─── 1. POSEDA & IGRA ──────────────────────────
        System.out.println("\n▶ POSE DA & TRAJANJE:");
        System.out.printf("  Poseda: HOME=%.1f%% AWAY=%.1f%%%n", r.homePossession(), r.awayPossession());
        System.out.printf("  Ukupno tikova: %d (%.1f minuta)%n", r.totalTicks(), r.totalTicks() / 120.0);

        // ─── 2. ŠUTEVI ─────────────────────────────────
        long[] shotStats = countShots(events);
        System.out.println("\n▶ ŠUTEVI:");
        System.out.printf("  Ukupno šuteva: %d%n", shotStats[0]);
        System.out.printf("  U okvir gola: %d (%.1f%%)%n", shotStats[1],
            shotStats[0] > 0 ? shotStats[1] * 100.0 / shotStats[0] : 0);
        System.out.printf("  Pored gola: %d%n", shotStats[2]);
        System.out.printf("  Golovi: %d%n", r.homeGoals() + r.awayGoals());
        System.out.printf("  Konverzija (golovi/SOT): %.1f%%%n",
            shotStats[1] > 0 ? (r.homeGoals() + r.awayGoals()) * 100.0 / shotStats[1] : 0);
        double homeXg = Double.longBitsToDouble(shotStats[3]);
        double awayXg = Double.longBitsToDouble(shotStats[4]);
        System.out.printf("  xG ukupno: %.2f (HOME: %.2f, AWAY: %.2f)%n",
            homeXg + awayXg, homeXg, awayXg);
        System.out.printf("  Prosek golova po meču: %.1f%n", (r.homeGoals() + r.awayGoals()) / 1.0);
        System.out.printf("  Šuteva po golu: %.1f%n",
            (r.homeGoals() + r.awayGoals()) > 0 ? shotStats[0] * 1.0 / (r.homeGoals() + r.awayGoals()) : Double.POSITIVE_INFINITY);

        // ─── 3. PASOVI ─────────────────────────────────
        long passCount = countByType(events, e -> e instanceof PassEvent);
        long completedPasses = countByType(events, e -> e instanceof PassEvent p && p.completed());
        long interceptedPasses = countByType(events, e -> e instanceof PassEvent p && p.intercepted());
        long incompletePasses = countByType(events, e -> e instanceof PassIncompleteEvent);
        long longBalls = countByType(events, e -> e instanceof LongBallEvent);
        long throughBalls = countByType(events, e -> e instanceof ThroughBallEvent);
        long receptions = countByType(events, e -> e instanceof ReceiveEvent);
        System.out.println("\n▶ PASOVI:");
        System.out.printf("  Ukupno pasova: %d%n", passCount);
        System.out.printf("  Uspešnih: %d (%.1f%%)%n", completedPasses,
            passCount > 0 ? completedPasses * 100.0 / passCount : 0);
        System.out.printf("  Presečenih: %d (%.1f%%)%n", interceptedPasses,
            passCount > 0 ? interceptedPasses * 100.0 / passCount : 0);
        System.out.printf("  Nepotpunih: %d%n", incompletePasses);
        System.out.printf("  Dugačkih lopti (15-35m): %d%n", longBalls);
        System.out.printf("  Through-ball (iza odbrane): %d%n", throughBalls);
        System.out.printf("  Prijema lopte: %d%n", receptions);
        System.out.printf("  Pasova po minuti: %.1f%n", passCount / (r.totalTicks() / 120.0));

        // ─── 4. DRIBLINZI ──────────────────────────────
        long dribbles = countByType(events, e -> e instanceof DribbleEvent);
        long dribblesLost = countByType(events, e -> e instanceof DribbleLostEvent);
        long carries = countNonDribbleCarries(events);
        System.out.println("\n▶ DRIBLINZI & NOŠENJE LOPTE:");
        System.out.printf("  Driblinga (uspešnih): %d%n", dribbles);
        System.out.printf("  Izgubljenih driblinga: %d%n", dribblesLost);
        System.out.printf("  Nošenje lopte bez driblinga: ~%d akcija%n", carries);

        long[] crossStats = countCrosses(events);
        System.out.println("\n▶ CENTARŠUTI:");
        System.out.printf("  Centaršuteva: %d%n", crossStats[0]);
        System.out.printf("  Odbijenih centaršuteva: %d%n", crossStats[1]);
        System.out.printf("  Glava iz centra: %d%n", crossStats[2]);

        // ─── 5. DUELLI ─────────────────────────────────
        long duels = countByType(events, e -> e instanceof DuelEvent);
        long tackles = countByType(events, e -> e instanceof TackleEvent);
        long tackleFouls = countByType(events, e -> e instanceof TackleFoulEvent);
        System.out.println("\n▶ DUELLI & STARTNEKOVI:");
        System.out.printf("  Duele (ukupno): %d%n", duels);
        System.out.printf("  Startnekovi: %d%n", tackles);
        System.out.printf("  Faul startnekovi: %d%n", tackleFouls);

        // ─── 6. SET PIECES ──────────────────────────────
        long corners = countByType(events, e -> e instanceof SetPieceEvent sp
            && sp.setPieceType() == SetPieceEvent.SetPieceType.CORNER);
        long freeKicks = countByType(events, e -> e instanceof SetPieceEvent sp
            && sp.setPieceType() == SetPieceEvent.SetPieceType.FREE_KICK);
        long throwIns = countByType(events, e -> e.getClass().getSimpleName().equals("ThrowInEvent")
            || (e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.THROW_IN));
        long goalKicks = countByType(events, e -> e.getClass().getSimpleName().equals("GoalKickEvent")
            || (e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.GOAL_KICK));
        System.out.println("\n▶ SET PIECES:");
        System.out.printf("  Kornera: %d%n", corners);
        System.out.printf("  Slobodnih udaraca: %d%n", freeKicks);
        System.out.printf("  Auta: %d%n", throwIns);
        System.out.printf("  Gol-auta: %d%n", goalKicks);

        // ─── 7. FAULOVI & PENALI ───────────────────────
        long fouls = countByType(events, e -> e instanceof FoulEvent);
        long penalties = countByType(events, e -> e instanceof FoulEvent f && f.penaltyFoul());
        System.out.println("\n▶ FAULOVI & PENALI:");
        System.out.printf("  Faulova (ukupno): %d%n", fouls);
        System.out.printf("  Penala dosuđeno: %d%n", penalties);

        // ─── 8. KARTONI ────────────────────────────────
        long yellows = countByType(events, e -> e instanceof CardEvent c
            && c.cardType() == CardEvent.CardType.YELLOW);
        long reds = countByType(events, e -> e instanceof CardEvent c
            && c.cardType() == CardEvent.CardType.RED);
        System.out.println("\n▶ KARTONI:");
        System.out.printf("  Žutih kartona: %d%n", yellows);
        System.out.printf("  Crvenih kartona: %d%n", reds);

        // ─── 9. OFSAJD ─────────────────────────────────
        long offsides = countByType(events, e -> e instanceof OffsideEvent);
        System.out.println("\n▶ OFSAJD:");
        System.out.printf("  Ofsajda: %d%n", offsides);

        // ─── 10. SUPS & POVREDE ────────────────────────
        long subs = countByType(events, e -> e instanceof SubstitutionEvent);
        long injuries = countByType(events, e -> e instanceof InjuryEvent);
        long gkSaves = countByType(events, e -> e instanceof GkSaveEvent);
        long gkCatches = countByType(events, e -> e instanceof GkCatchEvent);

        System.out.println("\n▶ OSTALO:");
        System.out.printf("  Golmanske odbrane (save): %d%n", gkSaves);
        System.out.printf("  Golmani uhvatili loptu: %d%n", gkCatches);
        System.out.printf("  Izmene: %d%n", subs);
        System.out.printf("  Povrede: %d%n", injuries);

        // ─── 11. VAR ───────────────────────────────────
        long varReviews = countByType(events, e -> "VARReviewEvent".equals(e.getClass().getSimpleName()));
        System.out.printf("  VAR pregleda: %d%n", varReviews);

        // ─── 12. POSESIJA ANALIZA ──────────────────────
        printPossessionAnalysis(events, r);

        // ─── 13. POZICIONIRANJE ────────────────────────
        printPositioningAnalysis(r);

        // ─── 14. OFSAJD KRETANJE ────────────────────────
        printOffsideMovement(r);

        // ─── 15. EVENT DISTRIBUCIJA ─────────────────────
        printEventDistribution(events, r.tickHistory());
    }

    // ═══════════════════════════════════════════════════════════
    // 2. POSESIJA ANALIZA
    // ═══════════════════════════════════════════════════════════

    private void printPossessionAnalysis(List<MatchEvent> events, MatchResult r) {
        List<PossessionStartEvent> starts = events.stream()
            .filter(e -> e instanceof PossessionStartEvent)
            .map(e -> (PossessionStartEvent) e)
            .collect(Collectors.toList());

        List<PossessionEndEvent> ends = events.stream()
            .filter(e -> e instanceof PossessionEndEvent)
            .map(e -> (PossessionEndEvent) e)
            .collect(Collectors.toList());

        System.out.println("\n▶ POSESIJA — ANALIZA NAPADA:");
        System.out.printf("  Broj posesija (započetih): %d%n", starts.size());
        System.out.printf("  Broj posesija (završenih): %d%n", ends.size());

        // Chain analysis: average pass count per possession
        double avgPassCount = ends.stream()
            .filter(e -> !e.description().contains("pregled"))
            .mapToInt(PossessionEndEvent::passCount)
            .average().orElse(0);
        System.out.printf("  Prosečan broj pasova po posesiji: %.1f%n", avgPassCount);

        // Longest possession chains
        int maxPassCount = ends.stream()
            .mapToInt(PossessionEndEvent::passCount)
            .max().orElse(0);
        System.out.printf("  Najduža posesija (pasova): %d%n", maxPassCount);

        // Possession end reasons
        Map<String, Long> endReasons = ends.stream()
            .collect(Collectors.groupingBy(
                e -> e.description().length() > 30 ? e.description().substring(0, 30) : e.description(),
                Collectors.counting()));
        System.out.println("  Razlozi kraja posesije:");
        endReasons.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .forEach(e -> System.out.printf("    • %s: %d%n", e.getKey(), e.getValue()));

        // Time per possession (estimated from possessionEnd ticks)
        if (!starts.isEmpty() && !ends.isEmpty()) {
            var sortedEnds = ends.stream().sorted(Comparator.comparingInt(MatchEvent::tick)).toList();
            var sortedStarts = starts.stream().sorted(Comparator.comparingInt(MatchEvent::tick)).toList();
            int minSize = Math.min(sortedEnds.size(), sortedStarts.size());
            List<Integer> durations = new ArrayList<>();
            for (int i = 0; i < minSize; i++) {
                durations.add(sortedEnds.get(i).tick() - sortedStarts.get(i).tick());
            }
            double avgDuration = durations.stream().mapToInt(Integer::intValue).average().orElse(0);
            System.out.printf("  Prosečno trajanje posesije: %.1f tikova (%.1f sekundi)%n",
                avgDuration, avgDuration / 2.0);
            int maxDuration = durations.stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.printf("  Najduža posesija: %d tikova (%.1f sekundi)%n",
                maxDuration, maxDuration / 2.0);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 3. POZICIONIRANJE — UZORKOVANJE TIKOVA
    // ═══════════════════════════════════════════════════════════

    private void printPositioningAnalysis(MatchResult r) {
        var ticks = r.tickHistory();
        if (ticks.isEmpty()) {
            System.out.println("\n▶ POZICIONIRANJE: Nema tick history-ja");
            return;
        }

        // Uzorkujemo svaki 500. tick (svakih ~4 minuta)
        int SAMPLE_INTERVAL = Math.max(1, ticks.size() / 20);
        System.out.println("\n▶ POZICIONIRANJE IGRAČA (uzorkovanje na svakih ~" + SAMPLE_INTERVAL + " tickova):");

        boolean[] homeDefendersCloseToCarrier = {false};
        boolean[] awayDefendersCloseToCarrier = {false};
        boolean[] homeAttackersAhead = {false};
        boolean[] awayAttackersAhead = {false};
        boolean[] gkInPositionOccurred = {false};
        int[] playerCountSampled = {0};
        double[] totalMovement = {0};
        int[] movementSamples = {0};

        for (int ti = 0; ti < ticks.size(); ti += SAMPLE_INTERVAL) {
            var tick = ticks.get(ti);
            if (tick.players().isEmpty() || tick.ball() == null) continue;

            double bx = tick.ball().x();
            double by = tick.ball().y();
            Long cid = tick.carrierId();

            for (var snap : tick.players()) {
                if (snap.position() == null || (cid != null && snap.playerId() == cid)) continue;
                double dx = snap.x() - bx;
                double dy = snap.y() - by;
                double distToBall = Math.sqrt(dx * dx + dy * dy);

                // Provera: da li su defanzivci blizu nosioca lopte?
                String opp = "HOME".equals(snap.teamSide()) ? "AWAY" : "HOME";
                boolean carrierIsOpp = cid != null
                    && tick.players().stream().anyMatch(s ->
                        s.playerId() == cid && opp.equals(s.teamSide()));
                if (carrierIsOpp && snap.position() != null
                    && (snap.position() == DEF || snap.position() == MID)) {
                    if (distToBall < 15) {
                        if ("HOME".equals(snap.teamSide())) homeDefendersCloseToCarrier[0] = true;
                        else awayDefendersCloseToCarrier[0] = true;
                    }
                }

                // Provera: da li su napadači ispred lopte?
                if (snap.position() == ATT || snap.position() == WNG) {
                    if ("HOME".equals(snap.teamSide()) && snap.x() > bx) {
                        homeAttackersAhead[0] = true;
                    } else if ("AWAY".equals(snap.teamSide()) && snap.x() < bx) {
                        awayAttackersAhead[0] = true;
                    }
                }

                // Provera: da li je GK u poziciji (blizu gola)?
                if (snap.position() == GK) {
                    if ("HOME".equals(snap.teamSide()) && snap.x() <= 15) gkInPositionOccurred[0] = true;
                    if ("AWAY".equals(snap.teamSide()) && snap.x() >= 85) gkInPositionOccurred[0] = true;
                }

                // Prosečno kretanje između tikova
                if (ti + SAMPLE_INTERVAL < ticks.size()) {
                    var tick2 = ticks.get(ti + SAMPLE_INTERVAL);
                    tick2.players().stream()
                        .filter(s -> s.playerId() == snap.playerId())
                        .findFirst().ifPresent(snap2 -> {
                            double dist = Math.sqrt(
                                Math.pow(snap2.x() - snap.x(), 2) + Math.pow(snap2.y() - snap.y(), 2));
                            totalMovement[0] += dist;
                            movementSamples[0]++;
                        });
                }
                playerCountSampled[0]++;
            }
        }

        System.out.printf("  Uzorkovano: %d pozicija igrača%n", playerCountSampled[0]);
        if (movementSamples[0] > 0) {
            double avgMovePerSample = totalMovement[0] / movementSamples[0];
            double movePerTick = avgMovePerSample / SAMPLE_INTERVAL;
            System.out.printf("  Prosečno pomeranje po tiku: %.3f jedinica (max pace=0.33)%n", movePerTick);
            System.out.printf("  Kretanje je unutar pace limita: %s%n",
                movePerTick < 0.35 ? "✓ DA" : "⚠️ NE");
        }

        if (homeDefendersCloseToCarrier[0]) {
            System.out.println("  ✓ HOME defanzivci se približavaju nosiocu lopte (reaktivno)");
        }
        if (awayDefendersCloseToCarrier[0]) {
            System.out.println("  ✓ AWAY defanzivci se približavaju nosiocu lopte (reaktivno)");
        }
        if (homeAttackersAhead[0]) {
            System.out.println("  ✓ HOME napadači su ispred lopte (ofanzivno kretanje)");
        }
        if (awayAttackersAhead[0]) {
            System.out.println("  ✓ AWAY napadači su ispred lopte (ofanzivno kretanje)");
        }
        if (gkInPositionOccurred[0]) {
            System.out.println("  ✓ Golmani ostaju blizu gola (posizione pozicije)");
        }

        // Provera rasporeda po trećinama terena
        printZoneDistribution(ticks, SAMPLE_INTERVAL);
    }

    private void printZoneDistribution(List<TickSnapshot> ticks, int interval) {
        int[] homeDef = {0}, homeMid = {0}, homeAtt = {0};
        int[] awayDef = {0}, awayMid = {0}, awayAtt = {0};
        int samples = 0;

        for (int ti = 0; ti < ticks.size(); ti += interval) {
            var tick = ticks.get(ti);
            double bx = tick.ball() != null ? tick.ball().x() : 50;
            for (var snap : tick.players()) {
                if (snap.teamSide() == null || snap.position() == null) continue;
                String zone;
                if (snap.x() < 33) zone = "def";
                else if (snap.x() < 66) zone = "mid";
                else zone = "att";

                if ("HOME".equals(snap.teamSide())) {
                    if (snap.position() == DEF || snap.position() == GK) {
                        if ("def".equals(zone)) homeDef[0]++;
                        else if ("mid".equals(zone)) homeMid[0]++;
                        else homeAtt[0]++;
                    }
                } else {
                    if (snap.position() == DEF || snap.position() == GK) {
                        if ("def".equals(zone)) awayDef[0]++;
                        else if ("mid".equals(zone)) awayMid[0]++;
                        else awayAtt[0]++;
                    }
                }
                samples++;
            }
        }

        if (samples > 0) {
            System.out.println("\n  ZONSKA DISTRIBUCIJA (defanzivci u trećinama):");
            int hTotal = homeDef[0] + homeMid[0] + homeAtt[0];
            int aTotal = awayDef[0] + awayMid[0] + awayAtt[0];
            if (hTotal > 0) {
                System.out.printf("    HOME def: def=%.1f%% mid=%.1f%% att=%.1f%%%n",
                    homeDef[0] * 100.0 / hTotal, homeMid[0] * 100.0 / hTotal, homeAtt[0] * 100.0 / hTotal);
            }
            if (aTotal > 0) {
                System.out.printf("    AWAY def: def=%.1f%% mid=%.1f%% att=%.1f%%%n",
                    awayDef[0] * 100.0 / aTotal, awayMid[0] * 100.0 / aTotal, awayAtt[0] * 100.0 / aTotal);
            }
            System.out.print("    Zaključak: ");
            if (hTotal > 0 && homeDef[0] > homeAtt[0]) {
                System.out.println("HOME defanzivci su uglavnom u svojoj polovini ✓");
            } else {
                System.out.println("HOME defanzivci izlaze visoko (ofanzivni stil)");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 4. OFSAJD KRETANJE
    // ═══════════════════════════════════════════════════════════

    private void printOffsideMovement(MatchResult r) {
        var ticks = r.tickHistory();
        if (ticks.isEmpty()) return;

        // Proveravamo prvih 2000 tickova da vidimo da li napadači ulaze u ofsajd
        int checkTicks = Math.min(2000, ticks.size());
        int interval = Math.max(1, checkTicks / 50);

        System.out.println("\n▶ OFSAJD ANALIZA (prvih " + (checkTicks / 120) + " min):");
        int offsideMomentCount = 0;
        int offsideEventCount = (int) countByType(r.events(), e -> e instanceof OffsideEvent);

        for (int ti = 0; ti < checkTicks; ti += interval) {
            var tick = ticks.get(ti);

            // Za HOME napadače: da li su iza AWAY zadnje linije?
            var homeAttackers = tick.players().stream()
                .filter(s -> "HOME".equals(s.teamSide())
                    && (s.position() == ATT || s.position() == WNG))
                .toList();
            var awayDefenders = tick.players().stream()
                .filter(s -> "AWAY".equals(s.teamSide())
                    && (s.position() == DEF || s.position() == GK))
                .mapToDouble(PlayerSnapshot::x)
                .sorted()
                .toArray();

            if (awayDefenders.length >= 2 && !homeAttackers.isEmpty()) {
                double offsideLine = awayDefenders[awayDefenders.length - 2];
                for (var att : homeAttackers) {
                    if (att.x() > offsideLine + 2 && att.x() > 50) {
                        offsideMomentCount++;
                        break;
                    }
                }
            }

            // Za AWAY napadače
            var awayAttackers = tick.players().stream()
                .filter(s -> "AWAY".equals(s.teamSide())
                    && (s.position() == ATT || s.position() == WNG))
                .toList();
            var homeDefenders = tick.players().stream()
                .filter(s -> "HOME".equals(s.teamSide())
                    && (s.position() == DEF || s.position() == GK))
                .mapToDouble(PlayerSnapshot::x)
                .sorted()
                .toArray();

            if (homeDefenders.length >= 2 && !awayAttackers.isEmpty()) {
                double offsideLine = homeDefenders[1];
                for (var att : awayAttackers) {
                    if (att.x() < offsideLine - 2 && att.x() < 50) {
                        offsideMomentCount++;
                        break;
                    }
                }
            }
        }

        System.out.printf("  Putovanja u ofsajd poziciju (iznad zadnje linije): ~%d puta%n", offsideMomentCount);
        if (offsideEventCount > 0) {
            System.out.printf("  Dosuđenih ofsajda: %d%n", offsideEventCount);
            System.out.println("  ✓ Ofsajd logika je aktivna");
        } else {
            System.out.println("  ⚠️ Nema dosuđenih ofsajda u ovom meču");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 5. EVENT DISTRIBUCIJA
    // ═══════════════════════════════════════════════════════════

    private void printEventDistribution(List<MatchEvent> events, List<TickSnapshot> tickHistory) {
        Map<String, Long> dist = events.stream()
            .collect(Collectors.groupingBy(e -> e.getClass().getSimpleName(), Collectors.counting()));

        System.out.println("\n▶ DISTRIBUCIJA DOGAĐAJA:");
        dist.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> System.out.printf("  %-30s: %3d%n", e.getKey(), e.getValue()));
        System.out.printf("  %-30s: %3d%n", "UKUPNO DOGAĐAJA", events.size());

        // Lopta bez nosioca, clearances, deflections
        long noCarrierTicks = tickHistory.stream()
            .filter(t -> t.carrierId() == null && !t.ballInTransit()).count();
        long transitTicks = tickHistory.stream()
            .filter(t -> t.ballInTransit()).count();
        long clearances = countByType(events, e -> e instanceof ClearanceEvent);
        long interceptions = countByType(events, e -> e instanceof PassEvent p && p.intercepted());
        long gkSaves = countByType(events, e -> e instanceof GkSaveEvent);
        long gkCatches = countByType(events, e -> e instanceof GkCatchEvent);
        long deflections = interceptions + gkSaves + gkCatches;

        System.out.println("\n▶ LOPTA BEZ NOSIOCA I DEFLEKCIJE:");
        System.out.printf("  Tikova bez nosioca (čista lopta): %d (%.1f sekundi)%n",
            noCarrierTicks, noCarrierTicks / 120.0);
        System.out.printf("  Tikova u tranzitu (pas u vazduhu): %d (%.1f sekundi)%n",
            transitTicks, transitTicks / 120.0);
        System.out.printf("  Clearance: %d%n", clearances);
        System.out.printf("  Interceptions: %d%n", interceptions);
        System.out.printf("  GK saves: %d%n", gkSaves);
        System.out.printf("  GK catches: %d%n", gkCatches);
        System.out.printf("  Ukupno defleksija/slično: %d%n", deflections);
    }

    // ═══════════════════════════════════════════════════════════
    // 6. AGREGAT
    // ═══════════════════════════════════════════════════════════

    private void printAggregate(List<MatchResult> results) {
        int matches = results.size();
        int totalGoals = 0, totalShots = 0, totalSOT = 0;
        int totalPasses = 0, totalCompletedPasses = 0;
        int totalDuels = 0, totalFouls = 0;
        int totalCorners = 0, totalThrowIns = 0;
        int totalYellows = 0, totalReds = 0;
        int totalOffsides = 0, totalPenalties = 0;
        int totalSubs = 0, totalInjuries = 0;
        int totalDribbles = 0, totalCrosses = 0;
        double totalPossession = 0;

        for (var r : results) {
            var events = r.events();
            totalGoals += r.homeGoals() + r.awayGoals();
            totalShots += (int) countByType(events, e -> e instanceof ShotEvent);
            totalSOT += r.homeShotsOnTarget() + r.awayShotsOnTarget();
            totalCompletedPasses += (int) countByType(events, e -> e instanceof PassEvent p && p.completed());
            totalPasses += (int) countByType(events, e -> e instanceof PassEvent);
            totalDuels += (int) countByType(events, e -> e instanceof DuelEvent);
            totalFouls += (int) countByType(events, e -> e instanceof FoulEvent);
            totalCorners += (int) countByType(events, e -> e instanceof SetPieceEvent sp
                && sp.setPieceType() == SetPieceEvent.SetPieceType.CORNER);
            totalThrowIns += (int) countByType(events, e -> e instanceof MatchEvent me && me.type() == MatchEvent.MatchEventType.THROW_IN);
            totalYellows += (int) countByType(events, e -> e instanceof CardEvent c
                && c.cardType() == CardEvent.CardType.YELLOW);
            totalReds += (int) countByType(events, e -> e instanceof CardEvent c
                && c.cardType() == CardEvent.CardType.RED);
            totalOffsides += (int) countByType(events, e -> e instanceof OffsideEvent);
            totalPenalties += (int) countByType(events, e -> e instanceof FoulEvent f && f.penaltyFoul());
            totalSubs += (int) countByType(events, e -> e instanceof SubstitutionEvent);
            totalInjuries += (int) countByType(events, e -> e instanceof InjuryEvent);
            totalDribbles += (int) countByType(events, e -> e instanceof DribbleEvent);
            totalCrosses += (int) countByType(events, e -> e instanceof CrossEvent);
            totalPossession += r.homePossession();
        }

        double totalClearances = 0, totalGkSaves = 0, totalGkCatches = 0;
        double totalNoCarrierTicks = 0, totalTransitTicks = 0;
        for (var r : results) {
            totalClearances += countByType(r.events(), e -> e instanceof ClearanceEvent);
            totalGkSaves += countByType(r.events(), e -> e instanceof GkSaveEvent);
            totalGkCatches += countByType(r.events(), e -> e instanceof GkCatchEvent);
            totalNoCarrierTicks += r.tickHistory().stream()
                .filter(t -> t.carrierId() == null && !t.ballInTransit()).count();
            totalTransitTicks += r.tickHistory().stream()
                .filter(t -> t.ballInTransit()).count();
        }

        System.out.println("\nProsek po meču (" + matches + " meča):");
        System.out.printf("  Golovi: %.1f%n", totalGoals * 1.0 / matches);
        System.out.printf("  Šutevi (ukupno): %.1f (u okvir: %.1f)%n",
            totalShots * 1.0 / matches, totalSOT * 1.0 / matches);
        System.out.printf("  Pasovi: %.1f (uspešnih: %.1f, %.1f%%)%n",
            totalPasses * 1.0 / matches, totalCompletedPasses * 1.0 / matches,
            totalPasses > 0 ? totalCompletedPasses * 100.0 / totalPasses : 0);
        System.out.printf("  Duele: %.1f%n", totalDuels * 1.0 / matches);
        System.out.printf("  Driblinzi: %.1f%n", totalDribbles * 1.0 / matches);
        System.out.printf("  Centaršutevi: %.1f%n", totalCrosses * 1.0 / matches);
        System.out.printf("  Korneri: %.1f, Auti: %.1f%n", totalCorners * 1.0 / matches,
            totalThrowIns * 1.0 / matches);
        System.out.printf("  Faulovi: %.1f, Penali: %.1f%n",
            totalFouls * 1.0 / matches, totalPenalties * 1.0 / matches);
        System.out.printf("  Žuti kartoni: %.1f, Crveni: %.1f%n",
            totalYellows * 1.0 / matches, totalReds * 1.0 / matches);
        System.out.printf("  Ofsajdi: %.1f%n", totalOffsides * 1.0 / matches);
        System.out.printf("  Izmene: %.1f, Povrede: %.1f%n",
            totalSubs * 1.0 / matches, totalInjuries * 1.0 / matches);
        System.out.printf("  Poseda (HOME avg): %.1f%%%n", totalPossession / matches);

        // Realizam provera
        System.out.println("\n--- PROVERA REALIZMA ---");
        double avgShots = totalShots * 1.0 / matches;
        double avgSOT = totalSOT * 1.0 / matches;
        double avgGoals = totalGoals * 1.0 / matches;
        double avgPassComp = totalPasses > 0 ? totalCompletedPasses * 100.0 / totalPasses : 0;

        System.out.printf("  Šuteva/meč: %.1f (realno ~25): %s%n",
            avgShots, avgShots >= 15 && avgShots <= 35 ? "✓" : "⚠️");
        System.out.printf("  SOT/meč: %.1f (realno ~8): %s%n",
            avgSOT, avgSOT >= 5 && avgSOT <= 18 ? "✓" : "⚠️");
        System.out.printf("  Golova/meč: %.1f (realno ~2.5): %s%n",
            avgGoals, avgGoals >= 1 && avgGoals <= 5 ? "✓" : "⚠️");
        System.out.printf("  Preciznost pasova: %.1f%% (realno ~80%%): %s%n",
            avgPassComp, avgPassComp >= 60 && avgPassComp <= 95 ? "✓" : "⚠️");
        System.out.printf("  Faulova/meč: %.1f (realno ~20): %s%n",
            totalFouls * 1.0 / matches, totalFouls * 1.0 / matches >= 5 ? "✓" : "⚠️");
        System.out.printf("  Kornera/meč: %.1f (realno ~10): %s%n",
            totalCorners * 1.0 / matches, totalCorners * 1.0 / matches >= 2 ? "✓" : "⚠️");
        System.out.printf("  Žutih/meč: %.1f (realno ~3-4): %s%n",
            totalYellows * 1.0 / matches, totalYellows * 1.0 / matches >= 1 ? "✓" : "⚠️");
        System.out.printf("  Ofsajda/meč: %.1f (realno ~3): %s%n",
            totalOffsides * 1.0 / matches, totalOffsides * 1.0 / matches >= 1 ? "✓" : "⚠️");
    }

    // ═══════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════
    // 9. POZICIONIRANJE — DETALJNA PROVERA (PRVIH 3 MINUTA)
    // ═══════════════════════════════════════════════════════════

    @Test
    void positioningAndSlotsVerification() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("HOME", "AWAY");
        MatchResult result = orchestrator.simulate(matchId);

        var ticks = result.tickHistory();
        int lastMinute3 = ticks.stream().filter(t -> t.minute() <= 3)
            .mapToInt(TickSnapshot::tick).max().orElse(360);

        System.out.println("\n" + "#".repeat(120));
        System.out.println(" VERIFIKACIJA POZICIONIRANJA — prva 3 minuta (tick 1 do " + lastMinute3 + ")");
        System.out.println("#".repeat(120));

        // Uzorkovanje na svakih ~120 tickova (svaki minut)
        int SAMPLE = 120;
        for (int ti = 0; ti < ticks.size() && ti <= lastMinute3; ti += SAMPLE) {
            var tick = ticks.get(ti);
            double bx = tick.ball() != null ? tick.ball().x() : 50;
            double by = tick.ball() != null ? tick.ball().y() : 50;
            int[] ballZone = ZonePositionCalculator.ballZone(bx, by);
            Long carrier = tick.carrierId();
            boolean inTransit = tick.ballInTransit();
            String carrierStatus = carrier != null ? "nosioc_ID=" + carrier : (inTransit ? "u_transitu" : "BEZ_NOSIOCA");
            String stoppageStatus = tick.activeEventType() != null ? " [" + tick.activeEventType() + "]" : "";

            // Prosečna X pozicija po poziciji
            double[] homeAvgX = new double[5], awayAvgX = new double[5];
            int[] homeCount = new int[5], awayCount = new int[5];
            java.util.Map<Position, Integer> posIndex = new java.util.HashMap<>();
            posIndex.put(Position.GK, 0); posIndex.put(Position.DEF, 1);
            posIndex.put(Position.MID, 2); posIndex.put(Position.WNG, 3);
            posIndex.put(Position.ATT, 4);

            for (var snap : tick.players()) {
                var pi = posIndex.get(snap.position());
                if (pi == null) continue;
                if ("HOME".equals(snap.teamSide())) {
                    homeAvgX[pi] += snap.x(); homeCount[pi]++;
                } else {
                    awayAvgX[pi] += snap.x(); awayCount[pi]++;
                }
            }
            for (int i = 0; i < 5; i++) {
                if (homeCount[i] > 0) homeAvgX[i] /= homeCount[i];
                if (awayCount[i] > 0) awayAvgX[i] /= awayCount[i];
            }

            // Uzmi GK pitanje
            OptionalDouble homeGkX = tick.players().stream()
                .filter(s -> "HOME".equals(s.teamSide()) && s.position() == GK)
                .mapToDouble(PlayerSnapshot::x).findFirst();
            OptionalDouble awayGkX = tick.players().stream()
                .filter(s -> "AWAY".equals(s.teamSide()) && s.position() == GK)
                .mapToDouble(PlayerSnapshot::x).findFirst();

            System.out.printf("%n[tick %d min %d] lopta=(%.0f,%.0f) zona=[%d,%d] | %s%s%n",
                tick.tick(), tick.minute(), bx, by, ballZone[0], ballZone[1],
                carrierStatus, stoppageStatus);
            System.out.printf("  GK:     HOME=%.0f AWAY=%.0f (goal line HOME=4 AWAY=96)%n",
                homeGkX.orElse(-1), awayGkX.orElse(-1));
            System.out.printf("  DEF:    HOME=%.0f AWAY=%.0f | MID: HOME=%.0f AWAY=%.0f | WNG: HOME=%.0f AWAY=%.0f | ATT: HOME=%.0f AWAY=%.0f%n",
                homeAvgX[1], awayAvgX[1], homeAvgX[2], awayAvgX[2],
                homeAvgX[3], awayAvgX[3], homeAvgX[4], awayAvgX[4]);

            // Provera da li su pozicije u odgovarajućem redosledu (HOME: GK < DEF < MID < ATT)
            String orderCheck = "";
            if (homeAvgX[1] < homeAvgX[4]) orderCheck += "DEF<ATT ✓ ";
            if (homeGkX.isPresent() && homeGkX.getAsDouble() < homeAvgX[1]) orderCheck += "GK<DEF ✓ ";
            // AWAY: obrnuto
            if (awayAvgX[1] > awayAvgX[4]) orderCheck += "AWAY DEF>ATT ✓ ";
            if (awayGkX.isPresent() && awayGkX.getAsDouble() > awayAvgX[1]) orderCheck += "AWAY GK>DEF ✓ ";
            if (!orderCheck.isEmpty()) System.out.println("  REDOSLED: " + orderCheck);

            // Ispiši 2 ključna igrača (def+att) sa njihovim X pozicijama
            for (String side : List.of("HOME", "AWAY")) {
                var defOpt = tick.players().stream()
                    .filter(s -> side.equals(s.teamSide()) && s.position() == DEF).findFirst();
                var attOpt = tick.players().stream()
                    .filter(s -> side.equals(s.teamSide()) && s.position() == ATT).findFirst();
                if (defOpt.isPresent() && attOpt.isPresent()) {
                    System.out.printf("  %s: %s(x=%.0f) → %s(x=%.0f) | lopta(x=%.0f)%n",
                        side, defOpt.get().name(), defOpt.get().x(),
                        attOpt.get().name(), attOpt.get().x(), bx);
                }
            }
        }

        // Provera: postoji li barem jedan tick bez carrierId (čista lopta)
        boolean hasLooseBall = ticks.stream().anyMatch(t -> t.carrierId() == null && !t.ballInTransit()
            && t.activeEventType() == null);
        long noCarrierCount = ticks.stream().filter(t -> t.carrierId() == null && !t.ballInTransit()
            && t.activeEventType() == null).count();
        long transitCount = ticks.stream().filter(t -> t.ballInTransit()).count();

        System.out.printf("%n▶ LOPTA BEZ NOSIOCA (čista lopta): %s — %d tikova (%.1f sec)%n",
            hasLooseBall ? "✓ DA" : "⚠ NE RETKO", noCarrierCount, noCarrierCount / 120.0);
        System.out.printf("▶ BAL U TRANZITU: %d tikova (%.1f sec)%n", transitCount, transitCount / 120.0);

        // Provera clearance/deflection
        var events = result.events();
        long clearances = countByType(events, e -> e instanceof ClearanceEvent);
        long saves = countByType(events, e -> e instanceof GkSaveEvent);
        long catches = countByType(events, e -> e instanceof GkCatchEvent);
        long interceptions = countByType(events, e -> e instanceof PassEvent p && p.intercepted());
        System.out.printf("▶ CLEARANCE: %d puta%n", clearances);
        System.out.printf("▶ DEFLECTION/SLIČNO: GK_SAVE=%d, GK_CATCH=%d, INTERCEPTION=%d%n",
            saves, catches, interceptions);
        System.out.printf("▶ Rezultat: %d-%d%n", result.homeGoals(), result.awayGoals());

        // Provera da li su se igrači pomerili od starta
        if (ticks.size() > 1) {
            var firstTick = ticks.getFirst();
            var lastSample = ticks.get(Math.min(ticks.size() - 1, lastMinute3));
            double totalMovement = 0;
            int count = 0;
            for (var s1 : firstTick.players()) {
                var s2Opt = lastSample.players().stream()
                    .filter(s -> s.playerId() == s1.playerId()).findFirst();
                if (s2Opt.isPresent()) {
                    var s2 = s2Opt.get();
                    totalMovement += Math.sqrt(Math.pow(s2.x() - s1.x(), 2) + Math.pow(s2.y() - s1.y(), 2));
                    count++;
                }
            }
            double avgMove = count > 0 ? totalMovement / count : 0;
            System.out.printf("▶ Pomeranje igrača od starta do tick %d: prosek %.1f jedinica (max očekivano ~%.0f)%n",
                lastMinute3, avgMove, lastMinute3 * 0.33);
            System.out.printf("  ✓ Igrači su se pomerili sa startnih pozicija: %s%n",
                avgMove > 3.0 ? "DA (značajno)" : avgMove > 1.0 ? "DA (minimalno)" : "NE");
        }

        System.out.println("#".repeat(120));
    }

    // POMOĆNE FUNKCIJE
    // ═══════════════════════════════════════════════════════════

    private long countByType(List<MatchEvent> events, java.util.function.Predicate<MatchEvent> pred) {
        return events.stream().filter(pred).count();
    }

    private long[] countShots(List<MatchEvent> events) {
        long onTarget = 0, offTarget = 0, totalShots = 0;
        double homeXg = 0, awayXg = 0;

        for (var e : events) {
            if (e instanceof ShotEvent s) {
                totalShots++;
                if (s.isGoal() || s.onTarget()) onTarget++;
                else offTarget++;
                if ("HOME".equals(s.teamSide())) homeXg += s.xG();
                else awayXg += s.xG();
            }
        }

        return new long[]{totalShots, onTarget, offTarget,
            Double.doubleToLongBits(homeXg), Double.doubleToLongBits(awayXg)};
    }

    private long[] countCrosses(List<MatchEvent> events) {
        long crosses = 0, cleared = 0, headers = 0;
        for (var e : events) {
            if (e instanceof CrossEvent) crosses++;
            if (e instanceof CrossClearedEvent) cleared++;
            if (e instanceof CrossHeaderEvent) headers++;
        }
        return new long[]{crosses, cleared, headers};
    }

    private long countNonDribbleCarries(List<MatchEvent> events) {
        long carries = 0;
        for (var e : events) {
            if (e instanceof PassEvent) carries++;
            if (e instanceof DribbleEvent) carries++;
        }
        return Math.max(1, carries / 2);
    }

    // ═══════════════════════════════════════════════════════════
    // SILA
    // ═══════════════════════════════════════════════════════════

    public static void main(String[] args) {
        new FootballIntegrationTest().comprehensiveFootballAnalysis();
    }
}
