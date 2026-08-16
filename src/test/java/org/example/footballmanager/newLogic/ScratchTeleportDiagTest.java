package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.service.MatchOrchestrator;import org.example.footballmanager.newLogic.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ScratchTeleportDiagTest {

    @Test
    void findWorstTeleport() {
        MatchOrchestrator orch = new MatchOrchestrator(new org.example.footballmanager.newLogic.store.MatchStore());
        for (int m = 0; m < 3; m++) {
            long matchId = orch.startMatch("Crvena Zvezda " + m, "Partizan " + m);
            MatchResult r = orch.simulate(matchId);
            List<TickSnapshot> ticks = r.tickHistory();
            int[] histo = new int[11];
            double worst = 0;
            int worstTick = -1;
            long worstPlayer = -1;
            PlayerSnapshot prevSnap = null;
            double worstFromX = 0, worstFromY = 0, worstToX = 0, worstToY = 0;
            int violations = 0;
            java.util.Map<Integer,Integer> violByTick = new java.util.TreeMap<>();
            for (int t = 0; t < ticks.size(); t++) {
                for (PlayerSnapshot ps : ticks.get(t).players()) {
                    PlayerSnapshot prev = null;
                    if (t > 0) {
                        for (PlayerSnapshot q : ticks.get(t - 1).players()) {
                            if (q.playerId() == ps.playerId()) { prev = q; break; }
                        }
                    }
                    if (prev == null) continue;
                    double d = ps.distanceTo(prev);
                    if (d > 0.5) {
                        violations++;
                        violByTick.merge(t / 600, 1, Integer::sum);
                        int bucket = (int) Math.min(10, d / 5.0);
                        histo[bucket]++;
                    }
                    if (d > worst) { worst = d; worstTick = t; worstPlayer = ps.playerId(); prevSnap = prev;
                        worstFromX = prev.x(); worstFromY = prev.y(); worstToX = ps.x(); worstToY = ps.y(); }
                }
            }
            System.out.println("  histo(0-5,5-10,...,45+): " + java.util.Arrays.toString(histo));
            System.out.println("  violByTickBlock(tick/600): " + violByTick);
            System.out.println("Match " + m + " violations=" + violations
                + " worst=" + String.format("%.3f", worst) + " at tick " + worstTick
                + " player " + worstPlayer
                + " activeEvent=" + ticks.get(worstTick).activeEventType()
                + " ballInTransit=" + ticks.get(worstTick).ballInTransit()
                + " carrier=" + ticks.get(worstTick).carrierId());
            final long wp = worstPlayer;
            final PlayerSnapshot worstSnapNow = ticks.get(worstTick).players().stream()
                .filter(p -> p.playerId() == wp).findFirst().get();
            System.out.println("   from=(" + String.format("%.3f", worstFromX) + "," + String.format("%.3f", worstFromY) + ")"
                + " to=(" + String.format("%.3f", worstToX) + "," + String.format("%.3f", worstToY) + ")"
                + " manualDist=" + String.format("%.3f", Math.hypot(worstToX - worstFromX, worstToY - worstFromY)));
            if (worstTick > 0) {
                for (PlayerSnapshot q : ticks.get(worstTick - 1).players()) {
                    if (q.playerId() == worstPlayer) {
                        System.out.println("   prev name=" + q.name() + " side=" + q.teamSide() + " pos=" + q.position()
                            + " hasBall=" + q.hasBall() + " state=" + q.state());
                    }
                }
                for (PlayerSnapshot q : ticks.get(worstTick).players()) {
                    if (q.playerId() == worstPlayer) {
                        System.out.println("   curr name=" + q.name() + " side=" + q.teamSide() + " pos=" + q.position()
                            + " hasBall=" + q.hasBall() + " state=" + q.state());
                    }
                }
            }
        }
    }

    private PlayerSnapshot findSnap(TickSnapshot t, long pid) {
        return t.players().stream().filter(p -> p.playerId() == pid).findFirst().orElse(null);
    }
}
