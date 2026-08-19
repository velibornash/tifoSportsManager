package org.example.footballmanager.demo.service.tactics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.demo.service.model.Position;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tactical rules loader — 3-tier: DB → bundled JSON → FormationSlotCatalog anchors.
 * Self-contained within the service package; no dependency on newLogic.
 *
 * DB config is passed via constructor parameters (from Spring/application-dev.properties).
 */
public class TacticsRules {

    private static final String FORMATION = "4-4-2";
    private static final String WE_HAVE_BALL = FormationSlotCatalog.WE_HAVE_BALL;
    private static final Pattern CELL_PATTERN = Pattern.compile("CELL_(\\d)_(\\d)");

    private final Map<String, Map<String, Position>> desiredByRoleByState;
    private final Map<String, Position> anchorByRole;
    private final String source;
    private final int ruleCount;

    /** 3-tier: DB → bundled JSON → catalog anchors. DB config passed as params. */
    public TacticsRules(String dbUrl, String dbUser, String dbPassword, long teamId) {
        Map<String, Map<String, Position>> loaded = loadFromDb(dbUrl, dbUser, dbPassword, teamId);
        if (loaded != null) {
            this.desiredByRoleByState = loaded;
            this.anchorByRole = anchorsFromCatalog();
            this.source = "DB (team " + teamId + ", " + FORMATION + ")";
            this.ruleCount = countRules(loaded);
        } else {
            loaded = loadFromBundledJson();
            if (loaded != null) {
                this.desiredByRoleByState = loaded;
                this.anchorByRole = anchorsFromCatalog();
                this.source = "bundled tactics_fallback.json";
                this.ruleCount = countRules(loaded);
            } else {
                this.desiredByRoleByState = new LinkedHashMap<>();
                this.anchorByRole = anchorsFromCatalog();
                this.source = "fallback (FormationSlotCatalog anchors only)";
                this.ruleCount = 0;
            }
        }
    }

    /** Constructor with DB URL, user, password and default team ID = 1. */
    public TacticsRules(String dbUrl, String dbUser, String dbPassword) {
        this(dbUrl, dbUser, dbPassword, 1L);
    }

    /** No-DB constructor — loads from bundled JSON only. */
    public TacticsRules() {
        Map<String, Map<String, Position>> loaded = loadFromBundledJson();
        if (loaded != null) {
            this.desiredByRoleByState = loaded;
            this.anchorByRole = anchorsFromCatalog();
            this.source = "bundled tactics_fallback.json";
            this.ruleCount = countRules(loaded);
        } else {
            this.desiredByRoleByState = new LinkedHashMap<>();
            this.anchorByRole = anchorsFromCatalog();
            this.source = "fallback (FormationSlotCatalog anchors only)";
            this.ruleCount = 0;
        }
    }

    /** Direct construction for tests. */
    public TacticsRules(Map<String, Map<String, Position>> rules, Map<String, Position> anchors) {
        this.desiredByRoleByState = rules;
        this.anchorByRole = anchors;
        this.source = "direct";
        this.ruleCount = countRules(rules);
    }

    /** Fallback rules — only anchor cells. */
    public static TacticsRules defaults() {
        return new TacticsRules(new LinkedHashMap<>(), anchorsFromCatalog());
    }

    public String getSource() { return source; }
    public int getRuleCount() { return ruleCount; }

    /**
     * Desired cell for a player role given the ball position.
     * Returns demo-model coordinates (row 1-7, col 1-6).
     */
    public Position desiredCell(String role, Position ball) {
        String state = ballStateKey(ball);
        Position fromRules = lookup(role, state);
        if (fromRules != null) return fromRules;
        Position fromAnchor = anchorByRole.get(role);
        if (fromAnchor != null) return fromAnchor;
        return new Position(1, 3.5);
    }

    /** Same rules, interpreted from the selected team's perspective. */
    public Position desiredCell(String role, Position ball, String team) {
        Position ballInEditorPerspective =
                TacticalPerspectiveTransformer.toHomePerspective(ball, team);
        Position targetInEditorPerspective = desiredCell(role, ballInEditorPerspective);
        return TacticalPerspectiveTransformer.toPhysical(targetInEditorPerspective, team);
    }

    /** Tactical-editor position for corner contexts. */
    public Position cornerCell(String role, String cornerContext, String team) {
        Position target = lookup(role, cornerContext);
        if (target == null) target = anchorByRole.get(role);
        if (target == null) target = new Position(1, 3.5);
        return TacticalPerspectiveTransformer.toPhysical(target, team);
    }

    private Position lookup(String role, String ballStateKey) {
        Map<String, Position> byState = desiredByRoleByState.get(role);
        return byState == null ? null : byState.get(ballStateKey);
    }

    /** Ball state key for demo-model position. */
    public static String ballStateKey(Position ball) {
        int r = clamp((int) Math.round(ball.getRow()) - 1, 0, 6);
        int c = clamp((int) Math.round(ball.getColumn()) - 1, 0, 5);
        return "CELL_" + r + "_" + c;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Parse "CELL_r_c" (0-based) into demo Position(r+1, c+1). */
    private static Position parseCell(String cellKey) {
        if (cellKey == null) return null;
        Matcher m = CELL_PATTERN.matcher(cellKey);
        if (!m.matches()) return null;
        return new Position(Integer.parseInt(m.group(1)) + 1, Integer.parseInt(m.group(2)) + 1);
    }

    private static Map<String, Position> anchorsFromCatalog() {
        Map<String, Position> anchors = new LinkedHashMap<>();
        List<TacticsSlotDTO> slots = new FormationSlotCatalog().getSlots(FORMATION);
        for (TacticsSlotDTO slot : slots) {
            Position pos = parseCell(slot.getAnchorCellKey());
            if (pos != null) anchors.put(slot.getSlotKey(), pos);
        }
        return anchors;
    }

    private Map<String, Map<String, Position>> loadFromDb(String dbUrl, String dbUser, String dbPassword, long teamId) {
        if (dbUrl == null || dbUrl.isBlank()) return null;
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            return null;
        }
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT rules_json FROM team_tactics_profile " +
                 "WHERE team_id = " + teamId + " AND formation = '" + FORMATION + "' " +
                 "ORDER BY version DESC LIMIT 1")) {
            if (!rs.next()) return null;
            String json = rs.getString(1);
            if (json == null || json.isBlank()) return null;
            return parseRulesJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Map<String, Position>> loadFromBundledJson() {
        try (InputStream is = getClass().getResourceAsStream("/tactics_fallback.json")) {
            if (is == null) return null;
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (json.isBlank()) return null;
            return parseRulesJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Map<String, Position>> parseRulesJson(String json) throws Exception {
        List<TacticsRuleDTO> allRules = new ObjectMapper()
            .readValue(json, new TypeReference<List<TacticsRuleDTO>>() {});
        Map<String, Map<String, Position>> byRole = new LinkedHashMap<>();
        for (TacticsRuleDTO rule : allRules) {
            if (!WE_HAVE_BALL.equals(rule.getPossessionContext())) continue;
            Position target = parseCell(rule.getTargetCellKey());
            if (target == null) continue;
            byRole.computeIfAbsent(rule.getSlotKey(), k -> new LinkedHashMap<>())
                .put(rule.getBallStateKey(), target);
        }
        return byRole;
    }

    private static int countRules(Map<String, Map<String, Position>> rules) {
        int n = 0;
        for (Map<String, Position> byState : rules.values()) n += byState.size();
        return n;
    }
}
