package org.example.footballmanager.demo.service.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone exporter — runs a headless {@link MatchSimulator} and writes the
 * replay payload (matchId, events, snapshots) to a static JSON file so the
 * web viewer at {@code static/demo/service/ui/index.html} can render it.
 *
 * No Spring context required: just {@code java -cp ... MatchSnapshotExporter [seed]}.
 * Writes to {@code src/main/resources/static/demo/service/ui/match.json}.
 */
public class MatchSnapshotExporter {

    public static void main(String[] args) throws Exception {
        long seed = (args.length > 0) ? Long.parseLong(args[0]) : System.nanoTime();

        List<Player> home = MatchSimulationController.generateTeam("HOME", "Home FC");
        List<Player> away = MatchSimulationController.generateTeam("AWAY", "Away United");

        MatchResult result = new MatchSimulator(seed).simulate(home, away, "Home FC", "Away United");

        ObjectMapper om = new ObjectMapper();
        om.findAndRegisterModules();

        String outPath = "src/main/resources/static/demo/service/ui/match.json";
        File out = new File(outPath);
        out.getParentFile().mkdirs();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("matchId", result.matchId());
        view.put("homeTeamName", result.homeTeamName());
        view.put("awayTeamName", result.awayTeamName());
        view.put("homeGoals", result.homeGoals());
        view.put("awayGoals", result.awayGoals());
        view.put("finalScore", result.finalScore());
        view.put("events", result.events());
        view.put("snapshots", result.snapshots());
        om.writerWithDefaultPrettyPrinter().writeValue(out, view);

        System.out.println("Wrote " + out.getAbsolutePath()
                + " | matchId=" + result.matchId()
                + " events=" + result.events().size()
                + " snapshots=" + result.snapshots().size()
                + " score=" + result.finalScore());
    }
}
