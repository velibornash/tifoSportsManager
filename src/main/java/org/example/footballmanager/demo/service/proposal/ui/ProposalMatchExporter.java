package org.example.footballmanager.demo.service.proposal.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher;
import org.example.footballmanager.demo.service.proposal.engine.MatchOrchestrator;
import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.tactics.TacticsRules;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone exporter — runs a headless {@link MatchSimulationLauncher} and writes the
 * replay payload (matchId, events, snapshots) to a static JSON file so the
 * web viewer at {@code static/demo/service/ui/proposal/index.html} can render it.
 *
 * No Spring context required: just {@code java -cp ... ProposalMatchExporter [seed]}.
 * Writes to {@code src/main/resources/static/demo/service/ui/proposal/match.json}.
 */
public class ProposalMatchExporter {

    public static void main(String[] args) throws Exception {
        long seed = (args.length > 0) ? Long.parseLong(args[0]) : System.nanoTime();

        // Build match state using the launcher's logic
        MatchState state = new MatchState();
        MatchSimulationLauncher.addTeam(state, "HOME");
        MatchSimulationLauncher.addTeam(state, "AWAY");

        // Set deterministic skills using seed
        for (Player p : state.getPlayers()) {
            // The addTeam already sets skills based on hash, but we can make it seed-based if needed
        }

        MatchOrchestrator orchestrator = new MatchOrchestrator(state);
        orchestrator.getRestartManager().handleKickoff(state, "HOME");

        // Record round-start positions for pace-based movement
        for (Player p : state.getPlayers()) {
            state.setRoundStartPosition(p.getId(), p.getPosition());
            state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
        }

        // Run full match (90 min = 3600 ticks)
        orchestrator.simulate(3600);

        // Build view model
        ObjectMapper om = new ObjectMapper();
        om.findAndRegisterModules();

        String outPath = "src/main/resources/static/demo/service/ui/proposal/match.json";
        File out = new File(outPath);
        out.getParentFile().mkdirs();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("matchId", state.getMatchId());
        view.put("homeTeamName", "Home FC");
        view.put("awayTeamName", "Away United");
        view.put("homeGoals", state.getHomeGoals());
        view.put("awayGoals", state.getAwayGoals());
        view.put("finalScore", state.getHomeGoals() + "-" + state.getAwayGoals());
        view.put("events", orchestrator.getEventLog());
        view.put("snapshots", buildSnapshots(state, orchestrator.getEventLog()));
        view.put("logs", orchestrator.getEventLog());

        om.writerWithDefaultPrettyPrinter().writeValue(out, view);

        System.out.println("Wrote " + out.getAbsolutePath()
                + " | matchId=" + state.getMatchId()
                + " events=" + orchestrator.getEventLog().size()
                + " snapshots=" + ((List<?>) view.get("snapshots")).size()
                + " score=" + state.getHomeGoals() + "-" + state.getAwayGoals());
    }

    private static List<Map<String, Object>> buildSnapshots(MatchState state, List<String> eventLog) {
        // For the proposal engine, we don't have per-tick snapshots yet.
        // We'll create a minimal snapshot from the final state.
        // TODO: add snapshot recording during simulation.
        return List.of();
    }
}