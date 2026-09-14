package org.example.footballmanager.demo.service.proposal.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher;
import org.example.footballmanager.demo.service.proposal.engine.MatchOrchestrator;
import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * One-click proposal match viewer launcher.
 *
 * <ol>
 *   <li>Starts a lightweight HTTP server (no Spring Boot needed).</li>
 *   <li>Serves the static viewer files from {@code static/demo/service/ui/proposal/}.</li>
 *   <li>Exposes {@code POST /api/generate} to simulate a match with the proposal engine
 *       and write {@code match.json}.</li>
 *   <li>Opens the browser — click <b>Generate</b>, then <b>Play</b>.</li>
 * </ol>
 *
 * Usage:
 * <pre>
 *   mvn exec:java \
 *       -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.ui.ProposalViewerLauncher
 * </pre>
 */
public class ProposalViewerLauncher {

    private static final int PORT = 8766; // different from demo/service (8765)
    private static final Path STATIC_DIR = Path.of(
            "src/main/resources/static/demo/service/ui/proposal");
    private static final Path MATCH_JSON = STATIC_DIR.resolve("match.json");
    private static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // ── API: simulate match with proposal engine ──
        server.createContext("/proposal/api/generate", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "POST only");
                return;
            }
            try {
                long seed = System.nanoTime();

                MatchState state = new MatchState();
                MatchSimulationLauncher.addTeam(state, "HOME");
                MatchSimulationLauncher.addTeam(state, "AWAY");

                MatchOrchestrator orchestrator = new MatchOrchestrator(state);
                orchestrator.getRestartManager().handleKickoff(state, "HOME");

                for (Player p : state.getPlayers()) {
                    state.setRoundStartPosition(p.getId(), p.getPosition());
                    state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
                }

                // Full 90 min match
                orchestrator.simulate(3600);

                Map<String, Object> view = new LinkedHashMap<>();
                view.put("matchId", state.getMatchId());
                view.put("homeTeamName", "Home FC");
                view.put("awayTeamName", "Away United");
                view.put("homeGoals", state.getHomeGoals());
                view.put("awayGoals", state.getAwayGoals());
                view.put("finalScore", state.getHomeGoals() + "-" + state.getAwayGoals());
                view.put("recorder", orchestrator.getRecorder().buildRecording(state.getMatchId()));
                view.put("events", orchestrator.getRecorder().getEvents());
                view.put("snapshots", orchestrator.getRecorder().getSnapshots());
                view.put("logs", orchestrator.getEventLog());
                view.put("stats", buildStats(orchestrator));

                Files.createDirectories(MATCH_JSON.getParent());
                OM.writerWithDefaultPrettyPrinter().writeValue(MATCH_JSON.toFile(), view);

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", true);
                resp.put("score", state.getHomeGoals() + "-" + state.getAwayGoals());
                resp.put("events", orchestrator.getEventLog().size());
                send(exchange, 200, "application/json", OM.writeValueAsString(resp));
                System.out.printf("✅ Generated match: %s  (events=%d)%n",
                        state.getHomeGoals() + "-" + state.getAwayGoals(), orchestrator.getEventLog().size());
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "text/plain", e.getMessage());
            }
        });

        // ── Static file server ──
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";

            File file = STATIC_DIR.resolve(path.substring(1)).toFile();
            if (!file.exists() || !file.isFile()) {
                send(exchange, 404, "text/plain", "Not found: " + path);
                return;
            }

            String ct = "text/plain";
            String lower = path.toLowerCase();
            if (path.endsWith(".html")) ct = "text/html";
            else if (path.endsWith(".css")) ct = "text/css";
            else if (path.endsWith(".js")) ct = "application/javascript";
            else if (path.endsWith(".json")) ct = "application/json";
            else if (lower.endsWith(".glb")) ct = "model/gltf-binary";
            else if (path.endsWith(".png")) ct = "image/png";
            else if (path.endsWith(".svg")) ct = "image/svg+xml";

            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        String url = "http://localhost:" + PORT + "/";
        System.out.println("⚽ Proposal Match Viewer running at " + url);
        System.out.println("   1. Click 'Generate Match' to simulate (proposal engine)");
        System.out.println("   2. Click 'Play Match' to watch replay");
        System.out.println("   Press Ctrl+C to stop.\n");

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(new java.net.URI(url));
        }
    }

    private static Map<String, Object> buildStats(MatchOrchestrator orchestrator) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("teams", orchestrator.getStats().toTeamJson());
        stats.put("players", orchestrator.getStats().toPlayersJson());
        return stats;
    }

    private static void send(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}