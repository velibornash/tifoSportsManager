package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.tactics.TacticsRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for goalkeeper chaser bounds — verifies that goalkeepers
 * outside their defensive zone are excluded from loose-ball chaser selection
 * and duel defense.
 *
 * Coordinate system (per user specification):
 * - HOME goal is at row 1, HOME GK starts at (1, 3.5), stays in rows 0–2.0
 * - AWAY goal is at row 7, AWAY GK starts at (7, 3.5), stays in rows 6.0–8
 * - HOME attacks toward row 7, AWAY attacks toward row 1
 */
class TestGoalkeeperChaserBounds {

    /**
     * Build a minimal match state with both GKs in position and
     * outfield players placed near midfield.
     */
    private MatchState buildState() {
        List<Player> players = new ArrayList<>();
        PlayerSkills skills = PlayerSkills.neutral();
        // HOME GK at (1, 3.5) — in defensive zone (row ≤ 2.0)
        players.add(new Player("h_gk", "Home GK", "HOME", "GK",
                new Position(1, 3.5), new Position(1, 3.5), skills));
        // HOME outfield at (3, 3.5) — near midfield
        players.add(new Player("h_df", "Home DEF", "HOME", "DCL",
                new Position(3, 3.5), new Position(3, 3.5), skills));
        // AWAY GK at (7, 3.5) — in defensive zone (row ≥ 6.0)
        players.add(new Player("a_gk", "Away GK", "AWAY", "GK",
                new Position(7, 3.5), new Position(7, 3.5), skills));
        // AWAY outfield at (5, 3.5) — near midfield
        players.add(new Player("a_df", "Away DEF", "AWAY", "DCR",
                new Position(5, 3.5), new Position(5, 3.5), skills));

        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        TacticsRules tactics = new TacticsRules();
        Random random = new Random(42);
        MatchRecorder recorder = new MatchRecorder();
        return new MatchState(players, ball, tactics, random, recorder);
    }

    private Player getPlayer(MatchState state, String id) {
        for (Player p : state.getPlayers()) {
            if (p.getId().equals(id)) return p;
        }
        throw new AssertionError("Player not found: " + id);
    }

    @Test
    void gkInZoneCanBeSelectedAsChaser() {
        // HOME GK at (1, 3.5) — in defensive zone. Ball at (1.5, 3.5) — close to GK.
        // GK should be selected as closest chaser because the outfield player is farther.
        MatchState state = buildState();
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);
        // Move ball near HOME goal
        state.getBall().setPosition(new Position(1.5, 3.5));

        Player closest = selection.closestChaserHomeTo(state.getBall().getPosition());
        assertNotNull(closest, "Should find a HOME chaser");
        assertEquals("GK", closest.getRole(),
                "HOME GK in defensive zone should be selectable as chaser when ball is near goal");
    }

    @Test
    void gkOutsideZoneIsExcludedFromChaser() {
        // Move HOME GK to (4, 3.5) — midfield, OUTSIDE defensive zone (row > 2.0)
        // Ball at (4, 3.5) — at midfield. GK is now "closest" but should be excluded.
        MatchState state = buildState();
        getPlayer(state, "h_gk").setPosition(new Position(4, 3.5));
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);

        Player closest = selection.closestChaserHomeTo(state.getBall().getPosition());
        assertNotNull(closest, "Should find a HOME chaser (the outfield player)");
        assertNotEquals("GK", closest.getRole(),
                "HOME GK outside defensive zone must NOT be selected as chaser");
        assertEquals("DCL", closest.getRole(),
                "Should fall back to the outfield HOME player");
    }

    @Test
    void awayGkOutsideZoneIsExcludedFromChaser() {
        // Move AWAY GK to (4, 3.5) — midfield, OUTSIDE defensive zone (row < 6.0)
        MatchState state = buildState();
        getPlayer(state, "a_gk").setPosition(new Position(4, 3.5));
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);

        Player closest = selection.closestChaserTeamTo(state.getBall().getPosition(), "AWAY");
        assertNotNull(closest, "Should find an AWAY chaser (the outfield player)");
        assertNotEquals("GK", closest.getRole(),
                "AWAY GK outside defensive zone must NOT be selected as chaser");
    }

    @Test
    void gkRoamingToMidfieldIsExcludedFromBothSides() {
        // Both GKs wander to midfield — neither should be a chaser.
        // Ball at center (4, 3.5).
        MatchState state = buildState();
        getPlayer(state, "h_gk").setPosition(new Position(3.5, 3.5)); // row > 2.0 → out of zone
        getPlayer(state, "a_gk").setPosition(new Position(4.5, 3.5)); // row < 6.0 → out of zone
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);

        Player homeChaser = selection.closestChaserHomeTo(state.getBall().getPosition());
        Player awayChaser = selection.closestChaserTeamTo(state.getBall().getPosition(), "AWAY");

        assertNotEquals("GK", homeChaser.getRole(),
                "HOME GK roaming to midfield must not chase");
        assertNotEquals("GK", awayChaser.getRole(),
                "AWAY GK roaming to midfield must not chase");
    }

    @Test
    void gkAtBoundaryRow2StaysEligible() {
        // HOME GK exactly at row 2.0 — boundary, should still be eligible
        MatchState state = buildState();
        getPlayer(state, "h_gk").setPosition(new Position(2.0, 3.5));
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);
        state.getBall().setPosition(new Position(2.0, 3.5));

        Player closest = selection.closestChaserHomeTo(state.getBall().getPosition());
        assertNotNull(closest);
        assertEquals("GK", closest.getRole(),
                "HOME GK at boundary row 2.0 should still be eligible");
    }

    @Test
    void gkAtRow2_5IsExcluded() {
        // HOME GK at row 2.5 — just past the 2.0 boundary, should be excluded
        MatchState state = buildState();
        getPlayer(state, "h_gk").setPosition(new Position(2.5, 3.5));
        getPlayer(state, "h_df").setPosition(new Position(2.6, 3.5)); // farther than GK but is outfield
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);
        state.getBall().setPosition(new Position(2.5, 3.5));

        Player closest = selection.closestChaserHomeTo(state.getBall().getPosition());
        assertNotNull(closest);
        assertNotEquals("GK", closest.getRole(),
                "HOME GK at row 2.5 (just past boundary) should be excluded");
    }
}
