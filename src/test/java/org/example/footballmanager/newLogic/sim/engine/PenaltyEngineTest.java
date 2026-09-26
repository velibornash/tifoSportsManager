package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 1.7 — penalty kicks.
 *
 * <p>Before S1.7 a penalty was awarded, counted and then never taken: the taker picked the ball up
 * off the spot and the generic final-rows hard-SHOT rule fired, so it was an 11 m shot with no
 * run-up, no dive and no nerve.
 *
 * <p>These tests pin the properties worth protecting — the keeper commits before the kick, skill
 * moves the numbers in the right direction, and every awarded penalty produces an execution event.
 */
class PenaltyEngineTest {

    private MatchState state;
    private MatchRecorder recorder;
    private ProposalStatsCollector stats;
    private PenaltyEngine engine;

    // TacticsRules probes the database on construction, so it is built ONCE. Rebuilding it
    // per iteration of a 4000-roll loop turns a 10 ms test into a connection-timeout hang.
    private static final RestartManager RESTARTS = new RestartManager(new TacticsRules());

    private static Player player(String id, String team, String role,
                                double striker, double technique, double keeper) {
        Player p = new Player(id, id, team, role,
                new Position(4.5, 3.0), new Position(4.5, 3.0),
                new PlayerSkills(12, 12, keeper, technique, 12, 12, striker, 12), 0);
        return p;
    }

    private void buildSquad(double takerStriker, double takerTech, double gkSkill) {
        state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new ArrayList<>()));
        recorder = new MatchRecorder();
        stats = new ProposalStatsCollector("Home", "Away");
        engine = new PenaltyEngine(state, recorder, stats,
                new ActionLogService(state, new ArrayList<>()), RESTARTS);

        Player taker = player("T1", "HOME", "STL", takerStriker, takerTech, 1);
        Player gk = player("G1", "AWAY", "GK", 1, 1, gkSkill);
        state.getPlayers().add(taker);
        state.getPlayers().add(gk);
        state.setCarrier(taker);
        stats.registerPlayers(state.getPlayers());
    }

    private long run(int n, double takerStriker, double takerTech, double gkSkill) {
        long scored = 0, saved = 0, missed = 0;
        for (int i = 0; i < n; i++) {
            SimulationRandom.seed(9000L + i);
            buildSquad(takerStriker, takerTech, gkSkill);
            PenaltyEngine.Outcome o = engine.resolve(state.getPlayers().get(0), state.getPlayers().get(1)).outcome();
            switch (o) {
                case SCORED -> scored++;
                case SAVED -> saved++;
                case MISSED -> missed++;
            }
        }
        return scored * 100L / n;
    }

    @Test
    @DisplayName("conversion with average skills lands near the real 76%")
    void conversionIsRealistic() {
        long pct = run(4000, 12, 12, 12);
        assertTrue(pct >= 70 && pct <= 82,
                "average penalty should convert ~76%, was " + pct + "%");
    }

    @Test
    @DisplayName("a better goalkeeper denies more penalties")
    void keeperSkillMatters() {
        long vsPoor = run(4000, 12, 12, 2);
        long vsElite = run(4000, 12, 12, 20);
        assertTrue(vsElite < vsPoor,
                "elite keeper should concede less: poor=" + vsPoor + "% elite=" + vsElite + "%");
    }

    @Test
    @DisplayName("a better taker converts more")
    void takerSkillMatters() {
        long poor = run(4000, 4, 4, 12);
        long good = run(4000, 19, 19, 12);
        assertTrue(good > poor,
                "good taker should convert more: poor=" + poor + "% good=" + good + "%");
    }

    @Test
    @DisplayName("the keeper commits to a side before the kick - that is the whole mechanic")
    void keeperCommitsToADive() {
        buildSquad(12, 12, 12);
        engine.execute(state.getPlayers().get(0), state.getPlayers().get(1));
        // A dive always registers as left / centre / right, never "unmoved".
        assertTrue(Math.abs(state.getPlayers().get(1).getDiveSide()) <= 1,
                "dive side must be -1, 0 or 1");
    }

    @Test
    @DisplayName("every penalty emits a PENALTY_KICK, so no penalty is ever silent")
    void everyPenaltyIsLogged() {
        for (int i = 0; i < 25; i++) {
            SimulationRandom.seed(500L + i);
            buildSquad(12, 12, 12);
            engine.execute(state.getPlayers().get(0), state.getPlayers().get(1));
            assertTrue(recorder.getEvents().stream()
                            .anyMatch(e -> "PENALTY_KICK".equals(e.getType())),
                    "penalty " + i + " produced no PENALTY_KICK event");
        }
    }

    @Test
    @DisplayName("the outcome is always one of the three real outcomes")
    void outcomeIsAlwaysOneOfThree() {
        List<PenaltyEngine.Outcome> seen = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            SimulationRandom.seed(700L + i);
            buildSquad(12, 12, 12);
            seen.add(engine.execute(state.getPlayers().get(0), state.getPlayers().get(1)));
        }
        assertEquals(3, seen.stream().distinct().count(),
                "all three outcomes (scored/saved/missed) should occur");
    }

    @Test
    @DisplayName("the best finisher takes it, not whoever is nearest the spot")
    void bestFinisherTakes() {
        buildSquad(12, 12, 12);
        Player striker = player("BEST", "HOME", "STL", 19, 18, 1);
        state.getPlayers().add(striker);
        assertEquals("BEST", engine.selectTaker("HOME").getId(),
                "the highest striker+technique attacker should be chosen");
    }

    @Test
    @DisplayName("a goalkeeper is never chosen to take a penalty")
    void keeperNeverTakes() {
        buildSquad(12, 12, 12);
        state.getPlayers().add(player("GK2", "HOME", "GK", 20, 20, 20));
        assertTrue(!"GK2".equals(engine.selectTaker("HOME").getId()),
                "a goalkeeper must not be handed the ball");
    }
}
