package org.example.footballmanager.newLogic.sim.controller;

import org.example.footballmanager.newLogic.sim.MatchSimulationLauncher;
import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proposal match generation API.
 *
 * Mapped under BOTH prefixes on purpose: the viewer, the docs and the
 * standalone {@code ProposalViewerLauncher} all use {@code /proposal/api/*},
 * while the original Spring mapping was {@code /api/proposal/*}. With only one
 * prefix the browser's Generate button 404'd and silently fell back to the
 * stale static match.json — i.e. the UI showed a completely different match
 * than the one in the log.
 */
@RestController
@RequestMapping({"/api/proposal", "/proposal/api"})
public class ProposalMatchController {

    /**
     * The last generated match, kept in memory so the viewer can reload it
     * (page refresh, "Play Match", deep link) WITHOUT depending on a file.
     *
     * This matters because Spring Boot serves static files from the CLASSPATH
     * ({@code target/classes/static/...}), not from {@code src/main/resources}.
     * Writing match.json into the source tree therefore never changed the file
     * the browser actually downloads — the UI kept showing the match from the
     * last build.
     */
    private static volatile Map<String, Object> latestMatch;

    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> generateMatch(
            @RequestParam(value = "seed", required = false) Long requestedSeed) throws Exception {
        long seed = requestedSeed != null ? requestedSeed : System.nanoTime();
        SimulationRandom.seed(seed);

        // Use the launcher logic
        MatchState state = new MatchState();
        MatchSimulationLauncher.addTeam(state, "HOME");
        MatchSimulationLauncher.addTeam(state, "AWAY");

        MatchOrchestrator orchestrator = new MatchOrchestrator(state);
        orchestrator.getRestartManager().handleKickoff(state, "HOME");

        for (Player p : state.getPlayers()) {
            state.setRoundStartPosition(p.getId(), p.getPosition());
            state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
        }

        // Run full match
        orchestrator.simulate(3600);

        // Build response
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("matchId", state.getMatchId());
        view.put("seed", seed);
        view.put("homeTeamName", "Home FC");
        view.put("awayTeamName", "Away United");
        view.put("homeGoals", state.getHomeGoals());
        view.put("awayGoals", state.getAwayGoals());
        view.put("finalScore", state.getHomeGoals() + "-" + state.getAwayGoals());
        view.put("events", orchestrator.getRecorder().getEvents());
        view.put("snapshots", orchestrator.getRecorder().getSnapshots());
        view.put("logs", orchestrator.getEventLog());
        view.put("stats", buildStats(orchestrator));

        // Match identity, printed so the log line can be matched against the
        // seed/matchId the viewer shows. Without this a stale UI was
        // indistinguishable from a correct one.
        System.out.println("=== PROPOSAL MATCH GENERATED === seed=" + seed
                + " matchId=" + state.getMatchId()
                + " score=" + view.get("finalScore")
                + " events=" + orchestrator.getRecorder().getEvents().size()
                + " snapshots=" + orchestrator.getRecorder().getSnapshots().size());

        latestMatch = view;

        // Also write the source-tree copy for the standalone viewer server and
        // for manual "Load JSON" — NOT as the transport for the app UI.
        writeMatchFile(view);

        return view;
    }

    /**
     * The most recently generated match. The viewer calls this on load and on
     * seek, so the replay always corresponds to the match that was just
     * generated in this running app.
     */
    @GetMapping(value = "/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> latest() {
        Map<String, Object> match = latestMatch;
        if (match == null) {
            throw new NoMatchGeneratedException();
        }
        return match;
    }

    /** 404 instead of a null body, so the viewer can tell "no match" from "broken". */
    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public static class NoMatchGeneratedException extends RuntimeException {
        public NoMatchGeneratedException() {
            super("No match generated in this session yet — press Generate Match first");
        }
    }

    private Map<String, Object> buildStats(MatchOrchestrator orchestrator) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("teams", orchestrator.getStats().toTeamJson());
        stats.put("players", orchestrator.getStats().toPlayersJson());
        stats.put("possessionChains", orchestrator.getStats().getPossessionChains());
        return stats;
    }

    private void writeMatchFile(Map<String, Object> view) {
        try {
            File out = new File("src/main/resources/static/demo/service/ui/proposal/match.json");
            out.getParentFile().mkdirs();
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            om.findAndRegisterModules();
            om.writerWithDefaultPrettyPrinter().writeValue(out, view);
        } catch (Exception e) {
            System.err.println("Failed to write match.json: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("engine", "proposal");
        Map<String, Object> latestMatch = this.latestMatch;
        out.put("hasMatch", latestMatch != null);
        if (latestMatch != null) {
            out.put("seed", latestMatch.get("seed"));
            out.put("matchId", latestMatch.get("matchId"));
            out.put("finalScore", latestMatch.get("finalScore"));
        }
        return out;
    }
}
