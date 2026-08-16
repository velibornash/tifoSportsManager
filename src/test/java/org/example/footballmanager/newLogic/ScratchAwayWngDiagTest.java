package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;

public class ScratchAwayWngDiagTest {

    @Test
    void diag() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        Match match = store.getMatch(matchId);
        MatchResult result = orchestrator.simulate(matchId);

        Map<Long, String> homeSlots = buildSlotMap(match.homeTeam());
        Map<Long, String> awaySlots = buildSlotMap(match.awayTeam());

        int printed = 0;
        for (TickSnapshot tick : result.tickHistory()) {
            if (tick.minute() > 5) break;
            if (tick.tick() % 120 != 0) continue;
            boolean homeInPoss = tick.carrierId() != null && tick.players().stream()
                .anyMatch(p -> p.playerId() == tick.carrierId() && "HOME".equals(p.teamSide()));
            boolean awayInPoss = tick.carrierId() != null && tick.players().stream()
                .anyMatch(p -> p.playerId() == tick.carrierId() && "AWAY".equals(p.teamSide()));
            int[] bz = ZonePositionCalculator.ballZone(tick.ball().x(), tick.ball().y());

            for (PlayerSnapshot ps : tick.players()) {
                if (ps.position() != Position.WNG) continue;
                Team team = "HOME".equals(ps.teamSide()) ? match.homeTeam() : match.awayTeam();
                Map<Long, String> slots = "HOME".equals(ps.teamSide()) ? homeSlots : awaySlots;
                String slotKey = slots.get(ps.playerId());
                if (slotKey == null) continue;
                Player player = team.startingXI().stream().filter(p -> p.id() == ps.playerId()).findFirst().orElse(null);
                if (player == null) continue;
                boolean inPoss = "HOME".equals(ps.teamSide()) ? homeInPoss : awayInPoss;
                double[] target = ZonePositionCalculator.tacticalTarget(player, ps.teamSide(), inPoss, bz[0], bz[1], slotKey, team.tacticRules());
                System.out.printf("min=%d tick=%d %-10s %-6s x=%6.1f y=%6.1f target=(%6.1f,%6.1f) d=%.1f intent=%-15s carrier=%d ball=(%.1f,%.1f) bz=%d,%d%n",
                    tick.minute(), tick.tick(), ps.name(), ps.teamSide(), ps.x(), ps.y(),
                    target[0], target[1], Math.hypot(ps.x()-target[0], ps.y()-target[1]),
                    ps.intent(), tick.carrierId() == null ? -1 : tick.carrierId(),
                    tick.ball().x(), tick.ball().y(), bz[0], bz[1]);
                printed++;
            }
        }
        System.out.println("printed=" + printed);
    }

    private Map<Long, String> buildSlotMap(Team team) {
        if (team == null) return Map.of();
        List<String> slotOrder = team.slotKeys() != null && team.slotKeys().size() == team.startingXI().size()
            ? team.slotKeys()
            : ZonePositionCalculator.buildSlotKeys(team.getFormation() != null ? team.getFormation() : "4-3-3", team.startingXI());
        Map<Long, String> slots = new LinkedHashMap<>();
        var starters = team.startingXI();
        for (int i = 0; i < Math.min(slotOrder.size(), starters.size()); i++) {
            slots.put(starters.get(i).id(), slotOrder.get(i));
        }
        return slots;
    }
}
