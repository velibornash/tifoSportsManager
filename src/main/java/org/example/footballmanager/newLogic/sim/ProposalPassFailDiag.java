package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.recording.MatchEvent;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P6 diagnostic: WHY do ~23% of passes fail to complete?
 *
 * Pass attempts minus completions is broken down by the terminal event that
 * killed the pass flight, so the calibration target can be attacked with
 * evidence instead of guesses. The event types counted here are the ones the
 * engine already emits, so no engine change is needed to run this.
 *
 * Usage: {@code ProposalPassFailDiag <matches> <baseSeed>}
 */
public class ProposalPassFailDiag {

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        long baseSeed = args.length > 1 ? Long.parseLong(args[1]) : 42L;

        Map<String, Integer> perMatch = new LinkedHashMap<>();
        int attempts = 0, completions = 0;

        for (int i = 0; i < n; i++) {
            long seed = baseSeed + i;
            SimulationRandom.seed(seed);
            MatchState state = new MatchState();
            MatchSimulationLauncher.addTeam(state, "HOME");
            MatchSimulationLauncher.addTeam(state, "AWAY");
            MatchOrchestrator o = new MatchOrchestrator(state);
            o.getRestartManager().handleKickoff(state, "HOME");
            for (Player p : state.getPlayers()) {
                state.setRoundStartPosition(p.getId(), p.getPosition());
                state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
            }
            o.simulate(3600);

            attempts += state.getPassAttempts();
            completions += state.getPassesCompleted();

            // Walk the event stream: every pass flight starts with a PASS event
            // and ends with exactly one terminal outcome.
            List<MatchEvent> events = o.getRecorder().getEvents();
            boolean inFlight = false;
            for (MatchEvent e : events) {
                String type = e.getType();
                if ("PASS".equals(type)) { inFlight = true; continue; }
                if (!inFlight) continue;
                if ("RECEIVE".equals(type)) {
                    tally(perMatch, "completed");
                    inFlight = false;
                } else if (List.of("INTERCEPT", "DEFLECT", "BLOCK", "SHOT_BLOCKED",
                        "SHOT_SAVED", "SHOT_MISSED", "OOB_ENTER", "OFFSIDE",
                        "FOUL", "YELLOW_CARD", "RED_CARD", "PENALTY_AWARDED",
                        "GOAL", "POST_HIT", "DUEL").contains(type)) {
                    tally(perMatch, type);
                    inFlight = false;
                } else if (List.of("PASS_LOOSE", "OOB_CANCEL", "LOOSE_PICKUP").contains(type)) {
                    // loose ball = pass failed, but recovery continues the chain
                    tally(perMatch, type);
                }
            }
        }

        int failed = attempts - completions;
        System.out.printf("=== P6 pass-failure breakdown (%d matches, base seed %d) ===%n", n, baseSeed);
        System.out.printf("attempts %d, completions %d (%.1f%%), failed %d%n",
                attempts, completions, 100.0 * completions / Math.max(1, attempts), failed);
        System.out.println();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(perMatch.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> en : sorted) {
            System.out.printf("  %-20s %6d   %5.1f%% of all failed passes%n",
                    en.getKey(), en.getValue(), 100.0 * en.getValue() / Math.max(1, failed));
        }
    }

    private static void tally(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }
}
