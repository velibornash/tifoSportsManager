package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

public class DebugTeleportTest {

    @Test
    void debugLargeMoves() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        var th = result.tickHistory();
        System.out.println("=== LARGE MOVES (dist > 0.45 per tick) ===");
        for (int t = 0; t < Math.min(th.size() - 1, 5000); t++) {
            var tickA = th.get(t);
            var tickB = th.get(t + 1);
            for (var snapA : tickA.players()) {
                long pid = snapA.playerId();
                var snapB = tickB.players().stream()
                    .filter(s -> s.playerId() == pid)
                    .findFirst().orElse(null);
                if (snapB == null) continue;
                double dist = snapA.distanceTo(snapB);
                if (dist > 0.45) {
                    System.out.printf("  tick=%d %-24s pid=%d pos %-4s (%6.2f,%6.2f) -> (%6.2f,%6.2f) dist=%.3f carrierTickA=%s carrierTickB=%s ballA=(%.2f,%.2f)%n",
                        tickA.tick(), snapA.name(), pid, snapA.position(),
                        snapA.x(), snapA.y(), snapB.x(), snapB.y(), dist,
                        tickA.carrierId(), tickB.carrierId(),
                        tickA.ball().x(), tickA.ball().y());
                }
            }
        }
        System.out.println("Total ticks: " + th.size());
    }
}
