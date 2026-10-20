package org.tifo.footballmanager.newLogic.sim.probe;

import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.PlayerSkills;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.tactics.FormationSlotCatalog;
import org.tifo.footballmanager.newLogic.sim.tactics.SideTactics;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticalPerspectiveTransformer;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticsSlotDTO;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Runs a probe against the tactics the owner actually saved, instead of the bundled fallback.
 *
 * <h2>Why this exists (owner, 2026-10-18)</h2>
 *
 * <p>Every probe in this package built its match with {@code new MatchOrchestrator(state)}. That
 * constructor is {@code this(state, new TacticsRules())}, and the no-arg {@code TacticsRules} loads the
 * <b>bundled fallback</b> — 1012 hardcoded rules in a 4-4-2, printed by the app at boot as
 * {@code "Tactics fallback in use: source=bundled tactics_fallback.json rules=1012 formation=4-4-2"}.
 *
 * <p>The production match does not do that. {@code SimMatchService} builds
 * {@code new SideTactics(rules.forTeam(homeId), rules.forTeam(awayId))} from the database.
 *
 * <p><b>So a whole afternoon of defensive analysis measured the fallback, not the game.</b> "The
 * nearest centre-back is 40 m from his own goal", "the box is empty 99.7% of the time", "the
 * centre-backs stand 14 m apart" — every one of those numbers came from a 4-4-2 nobody chose, played by
 * skill-14 robots, and the owner was right to send it back: <i>"nisam siguran da je to po taktici"</i>.
 *
 * <p>Three measurements in this session were wrong about the world rather than about the code. This
 * class exists so that a fourth is not possible by accident: one loader, used by every probe, that
 * prints exactly which rules it loaded so a wrong answer can be traced to its source in the output
 * rather than discovered afterwards.
 *
 * <h2>What it still fakes, honestly</h2>
 *
 * <p><b>The players.</b> It builds both squads from {@link FormationSlotCatalog} at one flat skill, because
 * asking "is this a shape problem or a skill problem" needs the skill held still. The tactics are real;
 * the footballers are not. Anything measured here is about <em>where men stand</em>, not about what a
 * specific player does with the ball.
 */
public final class RealTacticsHarness {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/sokker_db";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "stojke";

    /** One flat skill for both sides, so a difference in the output is a difference in the shape. */
    public static final double SKILL = 14.0;

    private RealTacticsHarness() {
    }

    /** What the loader found, so a report can name its own inputs. */
    public record Loaded(String teamName, Long teamId, String formation, String style,
                         int ruleCount, String source) { }

    /**
     * Reads the world's designated default tactic straight out of the database.
     *
     * <p>Raw JDBC on purpose. This runs from {@code exec:java}, outside Spring, so there is no
     * {@code TacticsRulesProvider} bean to ask — and a harness that quietly fell back to the bundled
     * rules would be the exact bug this class was written to kill.
     *
     * <p>Falls back to the lowest-id profile when nothing is designated, and SAYS SO, because a silent
     * fallback here is indistinguishable from a correct answer.
     */
    public static Loaded loadWorldDefault() {
        String sql = "select p.id, p.formation, p.style, p.world_default, p.rules_json, t.name "
                + "from team_tactics_profile p join team t on t.id = p.team_id "
                + "order by p.world_default desc, p.id asc limit 1";
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (!rs.next()) {
                throw new IllegalStateException(
                        "No tactical editor profile exists in the database at all. Build a tactic in the "
                                + "Tactical Editor, save it, and press Admin > Save Default Tactics.");
            }
            boolean designated = rs.getBoolean("world_default");
            String rules = rs.getString("rules_json");
            int count = 0;
            if (rules != null) {
                for (int i = 0; i < rules.length(); i++) if (rules.charAt(i) == '{') count++;
            }
            return new Loaded(rs.getString("name"), rs.getLong("id"), rs.getString("formation"),
                    rs.getString("style"), count,
                    designated
                            ? "the WORLD DEFAULT saved via Admin > Save Default Tactics"
                            : "NOT designated — the lowest-id profile, because none has been chosen. "
                              + "Press Admin > Save Default Tactics.");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not read the world default tactic from " + DB_URL
                            + ". If the column world_default is missing the app has not been restarted "
                            + "since the schema change.", e);
        }
    }

    /**
     * A squad for one side, built from the tactic's own formation.
     *
     * <p>Building a 4-4-2 and then handing it a 4-3-3's rules would put every unnamed role on
     * "hold your own position", because {@code TacticsRules} answers null for a role it does not
     * mention. The formation has to come from the same row as the rules.
     */
    public static void addTeam(MatchState state, String team, String formation) {
        PlayerSkills skills = new PlayerSkills(SKILL, SKILL, SKILL, SKILL, SKILL, SKILL, SKILL, SKILL);
        for (TacticsSlotDTO slot : new FormationSlotCatalog().getSlots(formation)) {
            String role = slot.getSlotKey();
            String[] p = slot.getAnchorCellKey().split("_");
            Position homeView = new Position(Double.parseDouble(p[1]) + 1.5,
                    Double.parseDouble(p[2]) + 1.5);
            Position start = TacticalPerspectiveTransformer.toPhysical(homeView, team);
            state.getPlayers().add(new Player(
                    team + "-" + role, role, team, role, start, start, skills, 180));
        }
    }

    /**
     * Builds a match with the real rules on both sides.
     *
     * <p>Both sides get the same rules on purpose. This is a probe about whether the shape and the
     * decisions are sound, and two different tactics would confound every number in it. It also means
     * any asymmetry in the output is the engine's and not the tactics'.
     */
    public static MatchOrchestrator match(MatchState state, Loaded loaded) {
        TacticsRules rules = new TacticsRules(DB_URL, DB_USER, DB_PASSWORD, loaded.teamId());
        state.getPlayers().forEach(p -> {
            state.setRoundStartPosition(p.getId(), p.getPosition());
            state.setRoundPaceSkill(p.getId(), (int) SKILL);
        });
        return new MatchOrchestrator(state, new SideTactics(rules, rules));
    }

    /** One line naming every input, printed before any measurement. */
    public static void banner(Loaded loaded) {
        System.out.printf("=== %s x %d · tactic '%s' (%s) from %s · %d rules · club '%s' (id %d) ===%n",
                "REAL TACTICS", 0, loaded.formation(), loaded.style(), loaded.source(),
                loaded.ruleCount(), loaded.teamName(), loaded.teamId());
        if (!loaded.source().startsWith("the WORLD DEFAULT")) {
            System.out.println("!! NOT a designated world default — these numbers do not describe the "
                    + "tactic the owner chose.");
        }
    }
}