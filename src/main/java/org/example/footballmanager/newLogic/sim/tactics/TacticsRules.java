package org.example.footballmanager.newLogic.sim.tactics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.newLogic.sim.model.Position;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tactical rules loader — 3-tier: DB (team_tactics_profile) → bundled JSON
 * (classpath /tactics_fallback.json) → FormationSlotCatalog anchors.
 * Self-contained within the proposal package.
 *
 * DATA TRANSFORMATION (critical): the initial tactical editor indexes ROWS AND
 * COLUMNS FROM 0. A saved target "CELL_1_3" means editor cell row 1 (physical
 * cell row 2, centre 2.5) and editor col 3 (physical cell col 4, centre 4.5).
 * parseCell() performs the 0→1 conversion: CELL_r_c → position (r + 1.5, c + 1.5).
 * The lookup key for the BALL uses the same 0-based grid: ballStateKey() maps a
 * physical ball position back to CELL_round(row)-1_round(col)-1 so the keys match
 * the stored rules. AWAY is handled by mirroring ball and target through
 * TacticalPerspectiveTransformer (9-row / 7-col), so rules are always interpreted
 * from HOME perspective.
 */
public class TacticsRules {

    public static final String FORMATION = "4-4-2";
    private static final String WE_HAVE_BALL = FormationSlotCatalog.WE_HAVE_BALL;
    private static final String OPPONENT_HAS_BALL = "OPPONENT_HAS_BALL";
    private static final Pattern CELL_PATTERN = Pattern.compile("CELL_(\\d)_(\\d)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // DB defaults for the standalone launcher (overridable via system properties).
    static final String DEFAULT_DB_URL = System.getProperty("tactics.db.url",
            "jdbc:postgresql://localhost:5432/sokker_db?connectTimeout=3");
    static final String DEFAULT_DB_USER = System.getProperty("tactics.db.user", "postgres");
    static final String DEFAULT_DB_PASSWORD = System.getProperty("tactics.db.password", "postgres");
    static final long DEFAULT_TEAM_ID = Long.getLong("tactics.db.teamId", 1L);

    private final Map<String, Map<String, Position>> desiredByRoleByState;
    private final Map<String, Map<String, Position>> opponentByRoleByState;
    private final Map<String, Position> anchorByRole;
    private final String source;
    private final int ruleCount;

    public TacticsRules() {
        this(DEFAULT_DB_URL, DEFAULT_DB_USER, DEFAULT_DB_PASSWORD, DEFAULT_TEAM_ID);
    }

    /** 3-tier: DB → bundled JSON → catalog anchors. DB config passed as params. */
    public TacticsRules(String dbUrl, String dbUser, String dbPassword, long teamId) {
        LoadedRules loaded = loadFromDb(dbUrl, dbUser, dbPassword, teamId);
        if (loaded != null) {
            this.desiredByRoleByState = loaded.haveBall();
            this.opponentByRoleByState = loaded.opponentHasBall();
            this.anchorByRole = anchorsFromCatalog();
            this.source = "DB (team " + teamId + ", " + FORMATION + ")";
            this.ruleCount = countRules(loaded.haveBall()) + countRules(loaded.opponentHasBall());
        } else {
            loaded = loadFromBundledJson();
            if (loaded != null) {
                this.desiredByRoleByState = loaded.haveBall();
                this.opponentByRoleByState = loaded.opponentHasBall();
                this.anchorByRole = anchorsFromCatalog();
                this.source = "bundled tactics_fallback.json";
                this.ruleCount = countRules(loaded.haveBall()) + countRules(loaded.opponentHasBall());
            } else {
                this.desiredByRoleByState = new LinkedHashMap<>();
                this.opponentByRoleByState = new LinkedHashMap<>();
                this.anchorByRole = anchorsFromCatalog();
                this.source = "fallback (FormationSlotCatalog anchors only)";
                this.ruleCount = 0;
            }
        }
    }

    /** Direct construction for tests. */
    public TacticsRules(Map<String, Map<String, Position>> rules, Map<String, Position> anchors) {
        this(rules, new LinkedHashMap<>(), anchors);
    }

    public TacticsRules(Map<String, Map<String, Position>> rules,
                        Map<String, Map<String, Position>> opponentRules,
                        Map<String, Position> anchors) {
        this.desiredByRoleByState = rules;
        this.opponentByRoleByState = opponentRules;
        this.anchorByRole = anchors;
        this.source = "direct";
        this.ruleCount = countRules(rules) + countRules(opponentRules);
    }

    /** Fallback rules — only anchor cells. */
    public static TacticsRules defaults() {
        return new TacticsRules(new LinkedHashMap<>(), anchorsFromCatalog());
    }

    private record LoadedRules(
            Map<String, Map<String, Position>> haveBall,
            Map<String, Map<String, Position>> opponentHasBall) {
    }

    public String getSource() { return source; }
    public int getRuleCount() { return ruleCount; }

    /**
     * Desired physical position for a player of the given role/team when the
     * ball is at {@code ball}. Rules are stored from HOME perspective; AWAY is
     * mirrored through TacticalPerspectiveTransformer.
     */
    public Position desiredCell(String role, Position ball, String team) {
        return desiredCell(role, ball, team, team);
    }

    public Position desiredCell(String role, Position ball, String team, String possessionTeam) {
        return desiredCell(role, ball, team, possessionTeam, true);
    }

    /**
     * As above, with the derived defensive block switchable.
     *
     * <p>The kickoff hold passes false, because a kickoff is an arrangement the players are
     * <i>placed</i> into rather than a shape they walk to, and the derived block sits close enough
     * to the half-way line to pull a player who was standing safely in his own half back towards it
     * — leaving him in transit across the boundary at the moment the pass is struck.
     */
    public Position desiredCell(String role, Position ball, String team, String possessionTeam,
                                boolean deriveDefensiveShape) {
        Position ballInEditorPerspective =
                TacticalPerspectiveTransformer.toHomePerspective(ball, team);
        String context = possessionTeam != null && possessionTeam.equals(team)
                ? WE_HAVE_BALL : OPPONENT_HAS_BALL;
        Position targetInEditorPerspective = desiredCellFromContext(
                role, ballInEditorPerspective, context, deriveDefensiveShape);
        Position physical = TacticalPerspectiveTransformer.toPhysical(targetInEditorPerspective, team);
        return clampToField(physical);
    }

    /** Desired position for a role from HOME perspective (used for lookups). */
    public Position desiredCell(String role, Position ball) {
        return desiredCell(role, ball, WE_HAVE_BALL);
    }

    /**
     * Where a player stands in one tactical phase.
     *
     * <p>The out-of-possession case is <b>derived</b> from the in-possession shape rather than looked
     * up, and that is the whole point. Both rule sets used to be shipped and both were consulted, but
     * all 506 out-of-possession rules were copies of their in-possession twins, so the possession
     * context changed nothing and a team defended in exactly the shape it attacked. Deriving the
     * defensive block means the two shapes differ by construction and cannot drift back into
     * agreement at the next data edit. See {@link DefensiveShape}.
     */
    private Position desiredCellFromContext(String role, Position ball, String context,
                                            boolean deriveDefensiveShape) {
        String state = ballStateKey(ball);
        if (!OPPONENT_HAS_BALL.equals(context)) {
            Position raw = firstNonNull(lookup(role, state, context), anchor(role), new Position(1.5, 3.5));
            return clampToField(raw);
        }

        Position attacking = firstNonNull(lookup(role, state, WE_HAVE_BALL), anchor(role),
                new Position(1.5, 3.5));
        Position authored = lookup(role, state, OPPONENT_HAS_BALL);

        // An explicitly authored out-of-possession rule wins, because somebody wrote it on purpose.
        // It is only ignored when it is missing or when it is a copy of the attacking rule — and a
        // copy is precisely the shipped state: all 506 out-of-possession rules were duplicates of
        // their in-posposition twins, so defending looked identical to attacking and the possession
        // context did nothing at all. Deriving in that case makes the two shapes differ by
        // construction, and the next data edit cannot quietly put them back in agreement.
        if (authored != null && !DefensiveShape.sameShape(authored, attacking)) {
            return clampToField(authored);
        }
        if (!deriveDefensiveShape) {
            return clampToField(authored != null ? authored : attacking);
        }
        Position derived = DefensiveShape.derive(attacking, ball, role);
        return clampToField(derived != null ? derived : attacking);
    }

    private static Position firstNonNull(Position... candidates) {
        for (Position candidate : candidates) {
            if (candidate != null) return candidate;
        }
        return null;
    }

    /** Formation anchor for a role, in physical coordinates for the team. */
    public Position anchorCell(String role, String team) {
        Position raw = anchor(role);
        if (raw == null) raw = new Position(1.5, 3.5);
        return clampToField(TacticalPerspectiveTransformer.toPhysical(raw, team));
    }

    private Position anchor(String role) {
        return anchorByRole.get(role);
    }

    private Position lookup(String role, String ballStateKey, String context) {
        Map<String, Map<String, Position>> source = OPPONENT_HAS_BALL.equals(context)
                ? opponentByRoleByState : desiredByRoleByState;
        Map<String, Position> byState = source.get(role);
        return byState == null ? null : byState.get(ballStateKey);
    }

    /** Ball state key (0-based grid) for a physical ball position. This is the
     *  inverse of parseCell(): editor index i holds the physical span [i+1, i+2)
     *  whose centre is i+1.5, so a physical position P maps to editor index
     *  round(P - 1.5). (Plain round(P)-1 shifts centre rows 1.5/4.5/… one cell
     *  toward AWAY, collapsing defenders onto the ball.) */
    public static String ballStateKey(Position ball) {
        int r = clamp((int) Math.round(ball.getRow() - 1.5), 0, 6);
        int c = clamp((int) Math.round(ball.getColumn() - 1.5), 0, 5);
        return "CELL_" + r + "_" + c;
    }

    /** Clamp to the proposal field: rows 1-7 playable (goal lines 1.0/8.0),
     *  cols 1-6 playable (touchlines 1.0/7.0). */
    private static Position clampToField(Position pos) {
        return new Position(
                Math.max(1.0, Math.min(7.5, pos.getRow())),
                Math.max(0.9, Math.min(7.0, pos.getColumn())));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Parse "CELL_r_c" (0-BASED editor index) into a physical Position centre:
     *  CELL_r_c → (r + 1.5, c + 1.5). This is the editor-index-0 → field-1 conversion. */
    private static Position parseCell(String cellKey) {
        if (cellKey == null) return null;
        Matcher m = CELL_PATTERN.matcher(cellKey);
        if (!m.matches()) return null;
        double rowCenter = Integer.parseInt(m.group(1)) + 1.5;
        double colCenter = Integer.parseInt(m.group(2)) + 1.5;
        return clampToField(new Position(rowCenter, colCenter));
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

    private LoadedRules loadFromDb(String dbUrl, String dbUser, String dbPassword, long teamId) {
        if (dbUrl == null || dbUrl.isBlank()) return null;
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            return null;
        }
        String sql = "SELECT rules_json FROM team_tactics_profile "
                + "WHERE team_id = ? AND formation = ? ORDER BY version DESC LIMIT 1";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, teamId);
            ps.setString(2, FORMATION);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String json = rs.getString(1);
                if (json == null || json.isBlank()) return null;
                return parseRulesJson(json);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private LoadedRules loadFromBundledJson() {
        try (InputStream is = getClass().getResourceAsStream("/tactics_fallback.json")) {
            if (is == null) return null;
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (json.isBlank()) return null;
            return parseRulesJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    private LoadedRules parseRulesJson(String json) throws Exception {
        List<TacticsRuleDTO> allRules = MAPPER
            .readValue(json, new TypeReference<List<TacticsRuleDTO>>() {});
        Map<String, Map<String, Position>> haveBall = new LinkedHashMap<>();
        Map<String, Map<String, Position>> opponentHasBall = new LinkedHashMap<>();
        for (TacticsRuleDTO rule : allRules) {
            if (rule == null || rule.getSlotKey() == null) continue;
            boolean opponentContext = OPPONENT_HAS_BALL.equals(rule.getPossessionContext());
            if (!WE_HAVE_BALL.equals(rule.getPossessionContext()) && !opponentContext) continue;
            Position target = parseCell(rule.getTargetCellKey());
            if (target == null) continue;
            Map<String, Map<String, Position>> destination = opponentContext
                    ? opponentHasBall : haveBall;
            destination.computeIfAbsent(rule.getSlotKey(), k -> new LinkedHashMap<>())
                .put(Objects.requireNonNull(rule.getBallStateKey()), target);
        }
        return new LoadedRules(haveBall, opponentHasBall);
    }

    private static int countRules(Map<String, Map<String, Position>> rules) {
        int n = 0;
        for (Map<String, Position> byState : rules.values()) n += byState.size();
        return n;
    }

    /** Dump loaded rules as JSON for debugging — writes to a file, returns string. */
    public String dumpLoadedRules() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"source\":\"").append(source).append("\",\"ruleCount\":").append(ruleCount)
          .append(",\"rules\":{");
        boolean firstRole = true;
        for (var roleEntry : desiredByRoleByState.entrySet()) {
            if (!firstRole) sb.append(",");
            firstRole = false;
            sb.append("\"").append(roleEntry.getKey()).append("\":{");
            boolean firstState = true;
            for (var stateEntry : roleEntry.getValue().entrySet()) {
                if (!firstState) sb.append(",");
                firstState = false;
                Position pos = stateEntry.getValue();
                sb.append("\"").append(stateEntry.getKey()).append("\":{\"row\":")
                  .append(String.format(java.util.Locale.US, "%.4f", pos.getRow()))
                  .append(",\"column\":")
                  .append(String.format(java.util.Locale.US, "%.4f", pos.getColumn()))
                  .append("}");
            }
            sb.append("}");
        }
        return sb.append("}}").toString();
    }
}