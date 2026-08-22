package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.engine.FootballRulesService;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.tactics.TacticsRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FootballRulesService — offside semantics (>= = level = onside),
 * restart determination, and kickoff-offside-skip behavior.
 *
 * corePrinciples §16: "Offside uses second-to-last defender (excluding GK).
 * Level = onside (>=)."
 */
class TestFootballRulesService {

    @Test
    void isOffsideLevelReceiverIsOnside() {
        // Receiver at same row as last defender — level = onside per FIFA
        MatchState state = buildMinimalState();
        FootballRulesService rules = new FootballRulesService(state);

        Player gk = findPlayer(state, "AWAY", "GK");
        Player def = findPlayer(state, "AWAY", "DCL");

        // Place receiver (HOME STR) level with the non-GK defender
        Player receiver = findPlayer(state, "HOME", "STR");
        receiver.setPosition(new Position(def.getPosition().getRow(), 5.0));

        // Pass from a HOME midfielder behind
        Player passer = findPlayer(state, "HOME", "CML");
        Position passOrigin = passer.getPosition();
        Position ballPos = passer.getPosition();

        assertFalse(rules.isOffside(receiver, passOrigin, ballPos),
                "Receiver level with defender must be onside (FIFA >= rule)");
    }

    @Test
    void isOffsideBehindDefenders() {
        MatchState state = buildMinimalState();
        FootballRulesService rules = new FootballRulesService(state);

        Player receiver = findPlayer(state, "HOME", "STR");
        // Place receiver ahead of ALL non-GK defenders → offside
        Player def = findPlayer(state, "AWAY", "DCL");
        receiver.setPosition(new Position(def.getPosition().getRow() + 1.0, 3.0));

        Player passer = findPlayer(state, "HOME", "CML");
        assertTrue(rules.isOffside(receiver, passer.getPosition(), passer.getPosition()),
                "Receiver ahead of all non-GK defenders should be offside");
    }

    @Test
    void isOffsideNoForwardPass() {
        MatchState state = buildMinimalState();
        FootballRulesService rules = new FootballRulesService(state);

        Player receiver = findPlayer(state, "HOME", "STR");
        // Receiver behind the passer — backward pass, not offside
        Player passer = findPlayer(state, "HOME", "CML");
        receiver.setPosition(new Position(passer.getPosition().getRow() - 1.0, 3.0));

        assertFalse(rules.isOffside(receiver, passer.getPosition(), passer.getPosition()));
    }

    @Test
    void determineRestartThrowIn() {
        MatchState state = buildMinimalState();
        FootballRulesService rules = new FootballRulesService(state);
        // Ball out left sideline
        assertEquals(FootballRulesService.RestartType.THROW_IN,
                rules.determineRestart(new Position(4, 0), "HOME"));
    }

    @Test
    void determineRestartCornerAttacking() {
        MatchState state = buildMinimalState();
        FootballRulesService rules = new FootballRulesService(state);
        // HOME attacking (toward row 7), ball over top line — 45% deflection chance
        // Accept either CORNER or GOAL_KICK
        FootballRulesService.RestartType restart =
                rules.determineRestart(new Position(7.5, 4), "HOME");
        assertTrue(restart == FootballRulesService.RestartType.CORNER
                || restart == FootballRulesService.RestartType.GOAL_KICK,
                "Attacking end-line out can be CORNER or GOAL_KICK (deflection), got: " + restart);
    }

    @Test
    void determineRestartDefendingTouchGoesCorner() {
        MatchState state = buildMinimalState();
        FootballRulesService rules = new FootballRulesService(state);
        // AWAY last touched the ball but it went out at the TOP (row>7, HOME's goal end).
        // AWAY attacks toward row 1, so outTop with AWAY touch = wasAttacking=false → CORNER (deterministic)
        FootballRulesService.RestartType restart =
                rules.determineRestart(new Position(7.5, 4), "AWAY");
        assertEquals(FootballRulesService.RestartType.CORNER, restart,
                "Defending team's touch out over end-line → CORNER (deterministic)");
    }

    // --- Helpers ---

    private MatchState buildMinimalState() {
        List<Player> players = new ArrayList<>();
        PlayerSkills def = PlayerSkills.neutral();
        // Home team (attacks toward row 7)
        players.add(new Player("h1", "Home GK", "HOME", "GK", new Position(4, 3.5), new Position(4, 3.5), def));
        players.add(new Player("h2", "Home DCL", "HOME", "DCL", new Position(3, 3), new Position(3, 3), def));
        players.add(new Player("h3", "Home CML", "HOME", "CML", new Position(4, 3), new Position(4, 3), def));
        players.add(new Player("h4", "Home STR", "HOME", "STR", new Position(5, 3), new Position(5, 3), def));
        // Away team (attacks toward row 1)
        players.add(new Player("a1", "Away GK", "AWAY", "GK", new Position(4, 3.5), new Position(4, 3.5), def));
        players.add(new Player("a2", "Away DCL", "AWAY", "DCL", new Position(5, 3), new Position(5, 3), def));
        players.add(new Player("a3", "Away CML", "AWAY", "CML", new Position(4, 3), new Position(4, 3), def));
        players.add(new Player("a4", "Away STR", "AWAY", "STR", new Position(3, 3), new Position(3, 3), def));

        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        TacticsRules tactics = new TacticsRules();
        Random random = new Random(42);
        MatchRecorder recorder = new MatchRecorder();

        return new MatchState(players, ball, tactics, random, recorder);
    }

    private Player findPlayer(MatchState state, String team, String role) {
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(team) && p.getRole().equals(role)) return p;
        }
        throw new AssertionError("Player not found: " + team + "/" + role);
    }
}
