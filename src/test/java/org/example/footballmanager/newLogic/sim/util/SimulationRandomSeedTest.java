package org.example.footballmanager.newLogic.sim.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first draw after seeding (owner, 2026-09-30).
 *
 * <p>This is a property of {@link java.util.Random}, not of anything in this codebase, and it is worth a
 * test because the fix is invisible once made: {@code seed()} discards a value, nothing in the code
 * says why, and the natural "simplification" is to delete the line and put the bias back.
 *
 * <p>The engine seeds from {@code fixture.getId()}, so the seeds in question are small consecutive
 * numbers — the worst case, and the only case that matters here.
 */
class SimulationRandomSeedTest {

    @Test
    @DisplayName("the first narrow draw after seeding is balanced across consecutive seeds")
    void theFirstDrawIsUsable() {
        int low = 0;
        int high = 0;
        for (long id = 1; id <= 500; id++) {
            SimulationRandom.seed(id);
            if (SimulationRandom.rng().nextInt(2) == 0) {
                low++;
            } else {
                high++;
            }
        }
        // Before the fix this was 0 / 500, every time, for every seed.
        assertTrue(low > 200 && low < 300,
                "the first nextInt(2) was " + low + " one way and " + high + " the other across seeds "
                        + "1..500; a freshly seeded Random does not mix its first output, so seed() has "
                        + "to discard one");
    }

    @Test
    @DisplayName("a boolean asked first is not always the same answer")
    void aFirstBooleanIsNotAConstant() {
        int trues = 0;
        for (long id = 1; id <= 500; id++) {
            SimulationRandom.seed(id);
            if (SimulationRandom.rng().nextBoolean()) {
                trues++;
            }
        }
        assertTrue(trues > 200 && trues < 300,
                "the first nextBoolean() returned true " + trues + " times out of 500, so something that "
                        + "asks a yes/no question first is getting a constant");
    }

    @Test
    @DisplayName("seeding is still deterministic — the same seed gives the same match")
    void determinismIsUnbroken() {
        // The point of discarding a value is to fix a bias, not to make the engine unpredictable. A replay
        // regenerated from a seed has to reproduce.
        SimulationRandom.seed(1234);
        int[] firstRun = new int[8];
        for (int i = 0; i < firstRun.length; i++) {
            firstRun[i] = SimulationRandom.rng().nextInt(1000);
        }

        SimulationRandom.seed(9999);
        for (int i = 0; i < 4; i++) {
            SimulationRandom.rng().nextInt(1000);
        }

        SimulationRandom.seed(1234);
        for (int i = 0; i < firstRun.length; i++) {
            assertEquals(firstRun[i], SimulationRandom.rng().nextInt(1000),
                    "draw " + i + " differed after re-seeding with the same value");
        }
    }

    @Test
    @DisplayName("different seeds still give different sequences")
    void differentSeedsDiverge() {
        SimulationRandom.seed(1);
        int[] one = new int[4];
        for (int i = 0; i < one.length; i++) {
            one[i] = SimulationRandom.rng().nextInt(10_000);
        }

        SimulationRandom.seed(2);
        boolean identical = true;
        for (int i = 0; i < one.length; i++) {
            if (SimulationRandom.rng().nextInt(10_000) != one[i]) {
                identical = false;
                break;
            }
        }
        assertTrue(!identical, "two different seeds produced the same sequence, so the seed is not being "
                + "used at all");
    }

    @Test
    @DisplayName("this is a property of java.util.Random, so the guard documents the platform")
    void theUnderlyingBehaviourIsThePlatforms() {
        // Proves the fix is a real mitigation and not a coincidence of our own generator: a bare
        // java.util.Random, untouched, shows the same constant first draw.
        int allTrue = 0;
        for (long seed = 1; seed <= 500; seed++) {
            if (new Random(seed).nextBoolean()) {
                allTrue++;
            }
        }
        // Asserted as *still* constant, which is the platform's documented-in-behaviour quirk and the
        // reason seed() discards a value. If this ever fails, the JDK changed the mixing and the
        // workaround should be re-measured rather than kept on trust.
        assertTrue(allTrue > 450,
                "java.util.Random's first nextBoolean() was true only " + allTrue + " times out of 500, so "
                        + "the platform is no longer the one this workaround was written for. Re-measure it "
                        + "before trusting or deleting the discarded value in seed()");
    }
}
