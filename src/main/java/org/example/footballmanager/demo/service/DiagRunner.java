package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.MatchSimulator;
import org.example.footballmanager.demo.service.result.LogEntry;
import java.util.*;

public class DiagRunner {
    public static void main(String[] args) {
        int minutes = 10;
        long seed = 42L;
        if (args.length > 0) {
            try { seed = Long.parseLong(args[0]); } catch (NumberFormatException ignored) {}
        }
        if (args.length > 1) {
            try { minutes = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        int cutoffTicks = minutes * 40;

        MatchSimulator sim = new MatchSimulator(seed);
        sim.setVerbose(true);
        sim.setDiagnosticCutoffTicks(cutoffTicks);
        var home = MatchSimulationController.generateTeam("HOME", "Home");
        var away = MatchSimulationController.generateTeam("AWAY", "Away");
        var result = sim.simulate(home, away, "Home", "Away");

        System.out.println("\n============================================================");
        System.out.println("=== FULL MATCH TIMELINE (PLAY-BY-PLAY EXCLUDING TICK SNAPS) ===");
        System.out.println("============================================================");
        int logCount = 0;
        int printedCount = 0;
        for (LogEntry log : result.logs()) {
            logCount++;
            if (!log.getChannel().equals("DECISION_OPTS")) {
                System.out.printf("%s%n [%s] %s%n",
                    log.getMatchClock(), log.getChannel(), log.getDescription());
                printedCount++;
            }
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.println("FINAL SCORE: " + result.finalScore());
        System.out.println("Total log entries: " + logCount);
        System.out.println("Shots: " + (result.homeStats().shots() + result.awayStats().shots()));
        System.out.println("Fouls: " + (result.homeStats().fouls() + result.awayStats().fouls()));
        System.out.println("Offsides: " + (result.homeStats().offsides() + result.awayStats().offsides()));
        System.out.println("Corners: " + (result.homeStats().corners() + result.awayStats().corners()));
        System.out.println("Decisions analyzed up to: " + minutes + " minutes");
    }

    private static int parseMinute(String matchClock) {
        if (matchClock == null || matchClock.isEmpty()) return 0;
        // Format: "M:SS" or "90+X:SS"
        int plusIdx = matchClock.indexOf('+');
        String minutePart;
        if (plusIdx >= 0) {
            minutePart = matchClock.substring(0, plusIdx);
        } else {
            int colonIdx = matchClock.indexOf(':');
            minutePart = matchClock.substring(0, colonIdx >= 0 ? colonIdx : matchClock.length());
        }
        try {
            return Integer.parseInt(minutePart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
