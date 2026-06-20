package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.model.event.PassEvent;
import org.example.footballmanager.newLogic.model.event.ShotEvent;
import org.example.footballmanager.newLogic.model.event.OffsideEvent;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class MatchMetricsTest {

    @Test
    void collectAndWriteMetrics() throws IOException {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        Path outDir = Path.of("target/metrics");
        Files.createDirectories(outDir);
        String stamp = String.valueOf(Instant.now().toEpochMilli());

        // Passes
        Path passes = outDir.resolve("passes_" + stamp + ".csv");
        String headerPass = "minute,tick,passerId,passerName,receiverId,receiverName,team,completed,intercepted,durationTicks" + System.lineSeparator();
        StringBuilder sbPass = new StringBuilder(headerPass);

        List<PassEvent> passEvents = result.events().stream()
            .filter(e -> e instanceof PassEvent)
            .map(e -> (PassEvent) e)
            .collect(Collectors.toList());

        List<TickSnapshot> ticks = result.tickHistory();

        for (PassEvent p : passEvents) {
            int startTick = p.tick();
            int endTick = startTick;
            // scan forward until ballInTransit becomes false or pendingReceiverId is null
            for (int i = 0; i < ticks.size(); i++) {
                TickSnapshot t = ticks.get(i);
                if (t.tick() < startTick) continue;
                if (!t.ballInTransit()) { endTick = t.tick(); break; }
            }
            int duration = Math.max(0, endTick - startTick);
            sbPass.append(String.format("%d,%d,%d,%s,%s,%s,%s,%b,%b,%d%n",
                p.minute(), p.tick(), p.passerId(), sanitize(p.passerName()),
                p.receiverId() != null ? p.receiverId() : -1, sanitize(p.receiverName()),
                sanitize(p.teamSide()), p.completed(), p.intercepted(), duration
            ));
        }
        Files.writeString(passes, sbPass.toString());

        // Shots
        Path shots = outDir.resolve("shots_" + stamp + ".csv");
        String headerShot = "minute,tick,shooterId,shooterName,team,onTarget,saved,xG,x,y" + System.lineSeparator();
        StringBuilder sbShot = new StringBuilder(headerShot);
        List<ShotEvent> shotsList = result.events().stream()
            .filter(e -> e instanceof ShotEvent)
            .map(e -> (ShotEvent) e)
            .collect(Collectors.toList());
        for (ShotEvent s : shotsList) {
            sbShot.append(String.format("%d,%d,%d,%s,%s,%b,%b,%.4f,%.1f,%.1f%n",
                s.minute(), s.tick(), s.shooterId(), sanitize(s.shooterName()), sanitize(s.teamSide()),
                s.onTarget(), s.saved(), s.xG(), s.x(), s.y()
            ));
        }
        Files.writeString(shots, sbShot.toString());

        // Offsides
        Path offs = outDir.resolve("offsides_" + stamp + ".csv");
        String headerOff = "minute,tick,playerId,playerName,team" + System.lineSeparator();
        StringBuilder sbOff = new StringBuilder(headerOff);
        List<OffsideEvent> offsList = result.events().stream()
            .filter(e -> e instanceof OffsideEvent)
            .map(e -> (OffsideEvent) e)
            .collect(Collectors.toList());
        for (OffsideEvent o : offsList) {
            sbOff.append(String.format("%d,%d,%d,%s,%s%n",
                o.minute(), o.tick(), o.playerId(), sanitize(o.playerName()), sanitize(o.teamSide())
            ));
        }
        Files.writeString(offs, sbOff.toString());

        System.out.println("Wrote metrics to: " + outDir.toAbsolutePath());
    }

    private static String sanitize(String s) { return s == null ? "" : s.replaceAll(",", " "); }
}
