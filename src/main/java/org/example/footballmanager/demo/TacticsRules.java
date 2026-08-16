package org.example.footballmanager.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.newLogic.dto.TacticsRuleDTO;
import org.example.footballmanager.newLogic.dto.TacticsSlotDTO;
import org.example.footballmanager.newLogic.service.FormationSlotCatalog;

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
 * Taktička pravila (desired pozicije) za DEMO simulaciju.
 *
 * Pravila se ucitavaju iz produkcijske baze (tabela team_tactics_profile,
 * pravila iz tactice editora tima 1: formation 4-4-2, WE_HAVE_BALL).
 * Ukoliko baza nije dostupna, koristi se fallback — default pravila iz
 * {@link FormationSlotCatalog} (anchor celije formacije 4-4-2).
 *
 * DB koordinate su 0-based "progress" red (0 = najblize sopstvenom golu)
 * i 0-based kolona; demo koordinate su model (row, col) sa row 1 = donji red.
 * Konverzija: CELL_r_c  →  Position(r + 1, c + 1).
 *
 * Kljuc stanja lopte se izvodi iz pozicije lopte: CELL_rState_cState gde je
 * rState = round(row)-1, cState = round(col)-1 (clamp na 0..6 / 0..5).
 */
public class TacticsRules {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/sokker_db";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "stojke";
    private static final long TEAM_ID = 1;
    private static final String FORMATION = "4-4-2";
    private static final String WE_HAVE_BALL = FormationSlotCatalog.WE_HAVE_BALL;

    private static final Pattern CELL_PATTERN = Pattern.compile("CELL_(\\d)_(\\d)");

    /** role → ballStateKey → desired Position (demo model koordinate). */
    private final Map<String, Map<String, Position>> desiredByRoleByState;

    /** role → anchor Position (iz FormationSlotCatalog, fallback za nepoznate state). */
    private final Map<String, Position> anchorByRole;

    private final String source;
    private final int ruleCount;

    /** Ucitava iz baze; ako baza nije dostupna, koristi catalog fallback. */
    public TacticsRules() {
        Map<String, Map<String, Position>> loaded = loadFromDb();
        if (loaded != null) {
            this.desiredByRoleByState = loaded;
            this.anchorByRole = anchorsFromCatalog();
            this.source = "DB (team " + TEAM_ID + ", " + FORMATION + ")";
            this.ruleCount = countRules(loaded);
        } else {
            this.desiredByRoleByState = new LinkedHashMap<>();
            this.anchorByRole = anchorsFromCatalog();
            this.source = "fallback (FormationSlotCatalog)";
            this.ruleCount = 0;
        }
        System.out.println("[TacticsRules] loaded " + ruleCount + " WE_HAVE_BALL rules from " + source);
    }

    /** Direktna izgradnja (za testove). */
    public TacticsRules(Map<String, Map<String, Position>> rules, Map<String, Position> anchors) {
        this.desiredByRoleByState = rules;
        this.anchorByRole = anchors;
        this.source = "direct";
        this.ruleCount = countRules(rules);
    }

    /** Fallback pravila bez baze — samo anchor celije iz catalog-a. */
    public static TacticsRules defaults() {
        return new TacticsRules(new LinkedHashMap<>(), anchorsFromCatalog());
    }

    public String getSource() {
        return source;
    }

    public int getRuleCount() {
        return ruleCount;
    }

    /**
     * Desired celija za igraca date role kada je lopta na datoj poziciji.
     * Koristi DB pravilo za (role, ballState); inace anchor poziciju role;
     * na kraju GK default. Vracena pozicija je u demo model koordinatama.
     */
    public Position desiredCell(String role, Position ball) {
        String state = ballStateKey(ball);
        Position fromRules = lookup(role, state);
        if (fromRules != null) {
            return fromRules;
        }
        Position fromAnchor = anchorByRole.get(role);
        if (fromAnchor != null) {
            return fromAnchor;
        }
        return new Position(1, 3.5);
    }

    private Position lookup(String role, String ballStateKey) {
        Map<String, Position> byState = desiredByRoleByState.get(role);
        return byState == null ? null : byState.get(ballStateKey);
    }

    /** Kljuc stanja lopte za demo poziciju lopte (model koordinate). */
    public static String ballStateKey(Position ball) {
        int r = clamp((int) Math.round(ball.getRow()) - 1, 0, 6);
        int c = clamp((int) Math.round(ball.getColumn()) - 1, 0, 5);
        return "CELL_" + r + "_" + c;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Parsira "CELL_r_c" (0-based) u demo Position(r+1, c+1); null ako ne parsira. */
    private static Position parseCell(String cellKey) {
        if (cellKey == null) {
            return null;
        }
        Matcher m = CELL_PATTERN.matcher(cellKey);
        if (!m.matches()) {
            return null;
        }
        return new Position(Integer.parseInt(m.group(1)) + 1, Integer.parseInt(m.group(2)) + 1);
    }

    /** Anchor pozicije svih rola formacije 4-4-2 iz catalog-a. */
    private static Map<String, Position> anchorsFromCatalog() {
        Map<String, Position> anchors = new LinkedHashMap<>();
        List<TacticsSlotDTO> slots = new FormationSlotCatalog().getSlots(FORMATION);
        for (TacticsSlotDTO slot : slots) {
            Position pos = parseCell(slot.getAnchorCellKey());
            if (pos != null) {
                anchors.put(slot.getSlotKey(), pos);
            }
        }
        return anchors;
    }

    /** Ucitava WE_HAVE_BALL pravila iz team_tactics_profile; null ako baza nije dostupna. */
    private Map<String, Map<String, Position>> loadFromDb() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            System.out.println("[TacticsRules] PG driver nije na classpath-u, koristi fallback: " + e.getMessage());
            return null;
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT rules_json FROM team_tactics_profile " +
                 "WHERE team_id = " + TEAM_ID + " AND formation = '" + FORMATION + "' " +
                 "ORDER BY version DESC LIMIT 1")) {
            if (!rs.next()) {
                System.out.println("[TacticsRules] nema profila za team " + TEAM_ID + "/" + FORMATION);
                return null;
            }
            String json = rs.getString(1);
            if (json == null || json.isBlank()) {
                System.out.println("[TacticsRules] rules_json je prazan za team " + TEAM_ID);
                return null;
            }
            List<TacticsRuleDTO> allRules = new ObjectMapper()
                .readValue(json, new TypeReference<List<TacticsRuleDTO>>() {});
            Map<String, Map<String, Position>> byRole = new LinkedHashMap<>();
            for (TacticsRuleDTO rule : allRules) {
                if (!WE_HAVE_BALL.equals(rule.getPossessionContext())) {
                    continue;
                }
                Position target = parseCell(rule.getTargetCellKey());
                if (target == null) {
                    continue;
                }
                byRole.computeIfAbsent(rule.getSlotKey(), k -> new LinkedHashMap<>())
                    .put(rule.getBallStateKey(), target);
            }
            return byRole;
        } catch (Exception e) {
            System.out.println("[TacticsRules] baza nije dostupna, koristi fallback: " + e.getMessage());
            return null;
        }
    }

    private static int countRules(Map<String, Map<String, Position>> rules) {
        int n = 0;
        for (Map<String, Position> byState : rules.values()) {
            n += byState.size();
        }
        return n;
    }
}
