package org.example.footballmanager.demo.service.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * One-click match viewer launcher.
 *
 * <ol>
 *   <li>Starts a lightweight HTTP server (no Spring Boot needed).</li>
 *   <li>Serves the static viewer files (HTML, CSS, JS).</li>
 *   <li>Exposes {@code POST /api/generate} to simulate a match and write {@code match.json}.</li>
 *   <li>Opens the browser — click <b>Generate</b>, then <b>Play</b>.</li>
 * </ol>
 *
 * Usage:
 * <pre>
 *   mvn exec:java \
 *       -Dexec.mainClass=org.example.footballmanager.demo.service.ui.MatchViewerLauncher
 * </pre>
 */
public class MatchViewerLauncher {

    private static final int PORT = 8765;
    private static final Path STATIC_DIR = Path.of(
            "src/main/resources/static/demo/service/ui");
    private static final Path MATCH_JSON = STATIC_DIR.resolve("match.json");
    private static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // ── API: simulate match ──
        server.createContext("/api/generate", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "POST only");
                return;
            }
            try {
                long seed = System.nanoTime();
                List<Player> home = MatchSimulationController.generateTeam("HOME", "Home FC", seed);
                List<Player> away = MatchSimulationController.generateTeam("AWAY", "Away United", seed);
                MatchResult result = new MatchSimulator(seed).simulate(home, away, "Home FC", "Away United");

                Map<String, Object> view = new LinkedHashMap<>();
                view.put("matchId", result.matchId());
                view.put("homeTeamName", result.homeTeamName());
                view.put("awayTeamName", result.awayTeamName());
                view.put("homeGoals", result.homeGoals());
                view.put("awayGoals", result.awayGoals());
                view.put("finalScore", result.finalScore());
                view.put("events", result.events());
                view.put("snapshots", result.snapshots());
                view.put("logs", result.logs());

                Files.createDirectories(MATCH_JSON.getParent());
                OM.writerWithDefaultPrettyPrinter().writeValue(MATCH_JSON.toFile(), view);

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", true);
                resp.put("score", result.finalScore());
                resp.put("events", result.events().size());
                resp.put("logs", result.logs().size());
                resp.put("snapshots", result.snapshots().size());
                send(exchange, 200, "application/json", OM.writeValueAsString(resp));
                System.out.printf("✅ Generated match: %s  (events=%d, logs=%d, snapshots=%d)%n",
                        result.finalScore(), result.events().size(), result.logs().size(), result.snapshots().size());
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
            if (path.endsWith(".html")) ct = "text/html";
            else if (path.endsWith(".css")) ct = "text/css";
            else if (path.endsWith(".js")) ct = "application/javascript";
            else if (path.endsWith(".json")) ct = "application/json";

            send(exchange, 200, ct, Files.readString(file.toPath()));
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        String url = "http://localhost:" + PORT + "/";
        System.out.println("⚽ Match Viewer running at " + url);
        System.out.println("   1. Click 'Generate Match' to simulate");
        System.out.println("   2. Click 'Play Match' to watch");
        System.out.println("   Press Ctrl+C to stop.\n");

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(new java.net.URI(url));
        }
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
