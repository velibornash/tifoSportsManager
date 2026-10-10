package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.tactics.MatchTacticPlan;
import org.example.footballmanager.newLogic.sim.tactics.MatchTacticsResolver;
import org.example.footballmanager.newLogic.sim.tactics.TacticMatchCondition;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A saved instruction reaches the match that must obey it (T0-BE-2).
 *
 * <p><b>This is the test that was missing while the feature looked finished.</b> {@code SimMatchRunner.run}
 * simulates inside itself, so an instruction has to be attached to the orchestrator before the first tick.
 * Before this test, the entity, the condition logic, the resolver, the tick hook and the engine setter all
 * existed, ten tests were green — and <b>nothing in production handed any of them an assignment</b>. The
 * substitution plan had exactly this shape once: ten green unit tests and a feature no manager could
 * reach.
 *
 * <p>It builds a real orchestrator through the real seam and asks what it was given. Running ninety minutes
 * and inspecting player positions afterwards would prove more; it would also be slow, and the question
 * here is narrower and prior: did the thing get there at all.
 */
class MatchTacticsReachTheMatchTest {

    private static TacticsRules rules(String label) {
        return new TacticsRules(Map.of(), Map.of(), Map.of(), label);
    }

    /** Eleven real players, because {@code SimMatchRunner.build} only trusts a squad of eleven or more. */
    private static List<Player> eleven() {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            players.add(new Player(
                    String.valueOf(i), "Home " + i, "HOME", "MID",
                    new Position(2.0 + i, 2.0 + i),
                    new Position(3.0 + i, 3.0 + i),
                    new PlayerSkills(60,60,60,60,60,60,60,60)));
        }
        return players;
    }

    private static MatchOrchestrator aBuiltMatch(MatchTacticsResolver resolver) {
        return SimMatchRunner.build("Home", "Away", eleven(), eleven(), List.of(), List.of(),
                new org.example.footballmanager.newLogic.sim.tactics.SideTactics(
                        rules("home default"), rules("away default")),
                resolver);
    }

    @Test
    @DisplayName("a fixture's instructions are attached to the match before it runs")
    void theInstructionsReachTheOrchestrator() {
        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 1L, 1, TacticMatchCondition.LEADING_BY_THREE, 0)),
                List.of(),
                Map.of(1L, rules("home 4-3-3"))::get,
                id -> null,
                rules("home default"), rules("away default"));

        MatchOrchestrator orchestrator = aBuiltMatch(resolver);

        assertNotNull(orchestrator.getLiveTactics(),
                "the resolver was not attached, so a saved instruction would never be read and the match "
                        + "would play one shape for ninety minutes");
        assertSame(resolver, orchestrator.getLiveTactics(),
                "and the attached resolver must be the one the caller built, not a copy or a stand-in");
    }

    @Test
    @DisplayName("a fixture nobody planned carries no resolver, and costs nothing per tick")
    void anUnplannedFixtureCarriesNothing() {
        assertNull(aBuiltMatch(null).getLiveTactics(),
                "the overwhelming majority of fixtures have no instructions; they must not pay a per-tick "
                        + "resolution for a feature they do not use");
    }

    @Test
    @DisplayName("the attached resolver changes the shape as the score moves")
    void theAttachedResolverDrivesTheShape() {
        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 1L, 1, TacticMatchCondition.LEADING_BY_THREE, 0)),
                List.of(),
                Map.of(1L, rules("home 4-3-3"))::get,
                id -> null,
                rules("home default"), rules("away default"));

        MatchOrchestrator orchestrator = aBuiltMatch(resolver);
        var attached = orchestrator.getLiveTactics();

        assertSame(rules("home default").getSource(), attached.resolve(0, 0, 10).home().getSource());
        assertSame("home 4-3-3", attached.resolve(3, 0, 40).home().getSource(),
                "three goals up, and the shape the manager asked for is the shape the match is holding");
    }
}