package org.example.footballmanager.demo.service.proposal.controller;

import org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher;
import org.example.footballmanager.demo.service.proposal.engine.MatchOrchestrator;
import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/proposal")
public class ProposalMatchController {

    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> generateMatch() throws Exception {
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
        view.put("homeTeamName", "Home FC");
        view.put("awayTeamName", "Away United");
        view.put("homeGoals", state.getHomeGoals());
        view.put("awayGoals", state.getAwayGoals());
        view.put("finalScore", state.getHomeGoals() + "-" + state.getAwayGoals());
        view.put("events", orchestrator.getEventLog());
        view.put("logs", orchestrator.getEventLog());
        // TODO: add snapshots when snapshot recording is implemented

        // Write to static file for viewer
        writeMatchFile(view);

        return view;
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
    public Map<String, String> health() {
        return Map.of("status", "ok", "engine", "proposal");
    }
}