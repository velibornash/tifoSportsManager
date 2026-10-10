package org.example.footballmanager.newLogic.sim.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orchestrator asks the resolver every tick (T0-BE-2).
 *
 * <p><b>This is a structural guard and is deliberately described as one.</b> It reads the orchestrator's
 * source rather than running a match, because exercising this for real needs a populated {@code MatchState}
 * and a full tick. It is weaker than the behavioural checks beside it, and it is here because the wiring
 * is a single call in a loop that fails <em>silently</em>: removing it leaves a match that keeps the shape
 * it was built with, and every other test in this task stays green. That was not a guess — the call was
 * deleted and all 27 tests passed.
 *
 * <p>That is the same failure as the {@code fixture-view.js} mount that hid the whole substitution feature,
 * and the reason it gets a guard at all: a call in a hot loop that nobody exercises is not a call.
 */
class OrchestratorConsultsTheResolverTest {

    private static final Path ORCHESTRATOR =
            Path.of("src/main/java/org/example/footballmanager/newLogic/sim/engine/MatchOrchestrator.java");

    @Test
    @DisplayName("the tick loop resolves the tactics in force before the engine that uses them")
    void theTickLoopResolvesEachTick() throws IOException {
        String source = Files.readString(ORCHESTRATOR, StandardCharsets.UTF_8);

        assertTrue(source.contains("liveTactics.resolve("),
                "the orchestrator must resolve the tactics in force, or a fixture's instructions are never "
                        + "read and every match keeps the shape it was built with");
        assertTrue(source.contains("setLiveTactics("),
                "there must be a way to give a match its instructions at all");

        // The three things the conditions are read from. A resolution that passed anything else would
        // compile, run, and decide on stale or invented numbers.
        assertTrue(source.contains("state.getHomeGoals()") && source.contains("state.getAwayGoals()"),
                "the resolver must be given the actual score; a resolution from anything else compiles and "
                        + "quietly decides the wrong tactic");
        assertTrue(source.contains("currentMinute()"),
                "and the actual minute, or 'from minute 60' would never mean anything");

        // Resolved before refreshTargets, not after: the movement engine follows the targets the intent
        // engine sets on this same pass, so resolving afterwards would apply a shape change one tick late
        // and, on a goal, one tick after the goal the manager reacted to.
        int resolve = source.indexOf("liveTactics.resolve(");
        int refresh = source.indexOf("tacticalEngine.refreshTargets(state)");
        assertTrue(resolve > 0 && refresh > 0 && resolve < refresh,
                "the tactics must be resolved BEFORE refreshTargets on the same tick; afterwards, the "
                        + "movement engine follows targets computed from the previous shape");
    }

    @Test
    @DisplayName("a match with no instructions never asks")
    void anUnplannedMatchPaysNothing() throws IOException {
        String source = Files.readString(ORCHESTRATOR, StandardCharsets.UTF_8);

        assertTrue(source.contains("if (liveTactics != null)"),
                "the resolution must be guarded. A match with no instructions is the overwhelming majority, "
                        + "and it must not pay a per-tick call for a feature it does not use");
    }

    @Test
    @DisplayName("the guard is reading the real orchestrator")
    void theGuardReadsTheRealOrchestrator() throws IOException {
        assertTrue(Files.exists(ORCHESTRATOR),
                "the orchestrator is not where this test looks for it, so every check above is vacuous");
        String source = Files.readString(ORCHESTRATOR, StandardCharsets.UTF_8);
        assertTrue(source.contains("public class MatchOrchestrator"),
                "the file no longer holds the orchestrator this test guards");
    }
}