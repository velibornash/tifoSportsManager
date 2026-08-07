package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.MovementEngine;
import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;

public class DebugWngTraceTest {

    @Test
    void traceAwayWng() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        Match match = store.getMatch(matchId);
        MatchResult result = orchestrator.simulate(matchId);

        // Build slot map like the test does
        Map<Long, String> homeSlots = buildSlotMap(match.homeTeam());
        Map<Long, String> awaySlots = buildSlotMap(match.awayTeam());

        // find away WNG
        Player awayWng = null;
        for (Player p : match.awayTeam().startingXI()) {
            if (p.getPosition() == Position.WNG) { awayWng = p; break; }
        }
        if (awayWng == null) { System.out.println("no away WNG"); return; }
        String slotKey = awaySlots.get(awayWng.getId());
        System.out.println("Away WNG: " + awayWng.getName() + " pid=" + awayWng.getId() + " slot=" + slotKey);

        var th = result.tickHistory();
        for (var tick : th) {
            if (tick.minute() > 5) break;
            if (tick.tick() % 40 != 0) continue;
            for (var snap : tick.players()) {
                if (snap.playerId() != awayWng.getId()) continue;
                boolean inPoss = isTeamInPossession(tick, "AWAY");
                int[] bz = ZonePositionCalculator.ballZone(tick.ball().x(), tick.ball().y());
                double[] target = ZonePositionCalculator.tacticalTarget(
                    awayWng, "AWAY", inPoss, bz[0], bz[1], slotKey, match.awayTeam().tacticRules());
                double d = Math.hypot(snap.x() - target[0], snap.y() - target[1]);
                double[] dp = snap.desiredPosition() != null ? snap.desiredPosition() : new double[]{-1,-1};
                boolean blend = MovementEngine.hasActiveBlend(snap.playerId());
                double[] tp = snap.tacticalPosition() != null ? snap.tacticalPosition() : new double[]{-1,-1};
                System.out.printf("tick=%4d min=%d pos=(%6.2f,%6.2f) intent=%-15s dp=(%5.1f,%5.1f) tac=(%5.1f,%5.1f) blend=%-5s carrier=%s inPoss=%s ball=(%5.1f,%5.1f) target=(%5.1f,%5.1f) dist=%5.1f%n",
                    tick.tick(), tick.minute(), snap.x(), snap.y(), snap.intent(), dp[0], dp[1], tp[0], tp[1], blend, tick.carrierId(), inPoss,
                    tick.ball().x(), tick.ball().y(), target[0], target[1], d);
            }
        }
    }

    private Map<Long, String> buildSlotMap(Team team) {
        if (team == null) return Map.of();
        List<String> slotOrder = team.slotKeys() != null && team.slotKeys().size() == team.startingXI().size()
            ? team.slotKeys()
            : ZonePositionCalculator.buildSlotKeys(
                team.getFormation() != null ? team.getFormation() : "4-3-3", team.startingXI());
        Map<Long, String> slots = new LinkedHashMap<>();
        var starters = team.startingXI();
        for (int i = 0; i < Math.min(slotOrder.size(), starters.size()); i++) {
            slots.put(starters.get(i).getId(), slotOrder.get(i));
        }
        return slots;
    }

    private boolean isTeamInPossession(TickSnapshot tick, String teamSide) {
        if (tick.carrierId() == null) return false;
        return tick.players().stream()
            .anyMatch(p -> p.playerId() == tick.carrierId() && teamSide.equals(p.teamSide()));
    }
}
