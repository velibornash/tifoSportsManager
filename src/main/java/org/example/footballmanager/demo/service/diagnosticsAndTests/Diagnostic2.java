package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;
import org.example.footballmanager.demo.service.result.LogEntry;

import java.util.*;

public class Diagnostic2 {
    public static void main(String[] args) {
        long seed = 1000L;
        MatchSimulator sim = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Home", 14);
        var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Away", 14);
        MatchResult result = sim.simulate(homePlayers, awayPlayers, "Home", "Away");

        Map<String, Integer> channelCount = new TreeMap<>();
        Map<String, Set<String>> channelToEntryTypes = new TreeMap<>();
        Set<String> sampleActionExec = new TreeSet<>();
        Set<String> sampleActionOutcome = new TreeSet<>();
        Set<String> sampleGoalSource = new TreeSet<>();
        Set<String> sampleOffsideRetreat = new TreeSet<>();
        int offsideRetreatCount = 0;

        for (LogEntry e : result.logs()) {
            String ch = e.getChannel();
            channelCount.merge(ch == null ? "null" : ch, 1, Integer::sum);

            String key = (ch == null ? "null" : ch) + " / " + e.getType();
            channelToEntryTypes.computeIfAbsent(key, k -> new TreeSet<>()).add(e.getDescription().substring(0, Math.min(60, e.getDescription().length())));

            String desc = e.getDescription();
            if (desc.contains("ACTION: CENTER") || desc.contains("ACTION: CROSS") || desc.contains("THRU") || (desc.startsWith("ACTION:") && !desc.contains("PASS") && !desc.contains("CARRY") && !desc.contains("SHOT"))) {
                if (sampleGoalSource.size() < 30) sampleGoalSource.add("[" + ch + "] " + desc.substring(0, Math.min(80, desc.length())));
            }
            if (desc.contains("OFFSIDE_RETREAT") || desc.contains("offside retreat") || desc.contains("RETREAT")) {
                offsideRetreatCount++;
                if (sampleOffsideRetreat.size() < 30) sampleOffsideRetreat.add("[" + ch + "] " + desc);
            }
            if ("ACTION_EXECUTION".equals(e.getType().toString()) || "ACTION".equals(ch)) {
                if (sampleActionExec.size() < 10) sampleActionExec.add("[" + ch + "] " + desc.substring(0, Math.min(80, desc.length())));
            }
            if ("ACTION_OUTCOME".equals(e.getType().toString()) || "OUTCOME".equals(ch)) {
                if (sampleActionOutcome.size() < 10) sampleActionOutcome.add("[" + ch + "] " + desc.substring(0, Math.min(80, desc.length())));
            }
        }

        System.out.println("=== ALL CHANNELS ===");
        channelCount.forEach((k, v) -> System.out.printf("  %-20s: %d%n", k, v));

        System.out.println("\n=== CHANNEL + ENTRY TYPE combos ===");
        channelToEntryTypes.forEach((k, v) -> {
            System.out.println("  " + k + ":");
            v.forEach(s -> System.out.println("    " + s));
        });

        System.out.println("\n=== ACTION_EXECUTION samples ===");
        sampleActionExec.forEach(System.out::println);

        System.out.println("\n=== ACTION_OUTCOME samples ===");
        sampleActionOutcome.forEach(System.out::println);

        System.out.println("\n=== GOAL SOURCE candidates ===");
        sampleGoalSource.forEach(System.out::println);

        System.out.println("\n=== OFFSIDE RETREAT ===");
        System.out.println("  Count: " + offsideRetreatCount);
        sampleOffsideRetreat.forEach(System.out::println);

        // Also check events
        System.out.println("\n=== MatchEvent types ===");
        Map<String, Integer> eventTypes = new TreeMap<>();
        for (var ev : result.events()) {
            eventTypes.merge(ev.type() == null ? "null" : ev.type(), 1, Integer::sum);
        }
        eventTypes.forEach((k, v) -> System.out.printf("  %-30s: %d%n", k, v));
    }
}
