package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.model.event.PassEvent;
import org.example.footballmanager.newLogic.model.event.OffsideEvent;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class MultiSimCsvGeneratorTest {

    @Test
    void generateCsvs() throws IOException {
        int N = 50; // number of simulations
        File outDir = new File("target/sim-csv");
        if (!outDir.exists()) outDir.mkdirs();

        File matchesCsv = new File(outDir, "matches.csv");
        File passesCsv = new File(outDir, "passes.csv");

        try (BufferedWriter mOut = new BufferedWriter(new FileWriter(matchesCsv));
             BufferedWriter pOut = new BufferedWriter(new FileWriter(passesCsv))) {

            mOut.write("matchId,homeGoals,awayGoals,totalGoals,homeShots,awayShots,totalShots,offsides,avgPassLength\n");
            pOut.write("matchId,tick,passerId,receiverId,length\n");

            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            AtomicInteger mid = new AtomicInteger(1);

            for (int i = 0; i < N; i++) {
                String home = "HOME_" + (i + 1);
                String away = "AWAY_" + (i + 1);
                long matchId = orchestrator.startMatch(home, away);
                MatchResult result = orchestrator.simulate(matchId);

                int homeGoals = result.homeGoals();
                int awayGoals = result.awayGoals();
                int totalGoals = homeGoals + awayGoals;
                int homeShots = result.homeShots();
                int awayShots = result.awayShots();
                int totalShots = homeShots + awayShots;

                // Count offsides
                long offsides = result.events().stream().filter(e -> e.type() == org.example.footballmanager.newLogic.model.event.MatchEvent.MatchEventType.OFFSIDE).count();

                // Pass lengths: correlate PassEvent with tick snapshots
                List<PassEvent> passes = result.events().stream()
                        .filter(e -> e instanceof PassEvent)
                        .map(e -> (PassEvent) e)
                        .collect(Collectors.toList());

                double totalPassLength = 0.0;
                int passCount = 0;

                for (PassEvent pass : passes) {
                    int tick = pass.tick();
                    // find snapshot with same tick (or nearest)
                    TickSnapshot snap = result.tickHistory().stream()
                            .filter(ts -> ts.tick() == tick)
                            .findFirst()
                            .orElse(null);
                    if (snap == null) {
                        // try nearest by small window
                        snap = result.tickHistory().stream()
                                .filter(ts -> Math.abs(ts.tick() - tick) <= 1)
                                .findFirst().orElse(null);
                    }
                    double length = -1.0;
                    if (snap != null && pass.receiverId() != null) {
                        PlayerSnapshot passer = snap.players().stream()
                                .filter(p -> p.playerId() == pass.passerId())
                                .findFirst().orElse(null);
                        PlayerSnapshot receiver = snap.players().stream()
                                .filter(p -> p.playerId() == pass.receiverId())
                                .findFirst().orElse(null);
                        if (passer != null && receiver != null) {
                            length = passer.distanceTo(receiver);
                            totalPassLength += length;
                            passCount++;
                        }
                    }

                    // write pass row if length found
                    if (length >= 0) {
                        pOut.write(String.format("%d,%d,%d,%d,%.3f\n", matchId, pass.tick(), pass.passerId(), pass.receiverId(), length));
                    } else {
                        pOut.write(String.format("%d,%d,%d,%d,\n", matchId, pass.tick(), pass.passerId(), pass.receiverId()));
                    }
                }

                double avgPassLen = passCount > 0 ? (totalPassLength / passCount) : 0.0;

                mOut.write(String.format("%d,%d,%d,%d,%d,%d,%d,%d,%.3f\n",
                        matchId, homeGoals, awayGoals, totalGoals, homeShots, awayShots, totalShots, offsides, avgPassLen));

                // flush periodically
                if (i % 10 == 0) {
                    mOut.flush();
                    pOut.flush();
                }
            }

            mOut.flush();
            pOut.flush();
        }

        System.out.println("CSV files generated in: " + outDir.getAbsolutePath());
    }
}
