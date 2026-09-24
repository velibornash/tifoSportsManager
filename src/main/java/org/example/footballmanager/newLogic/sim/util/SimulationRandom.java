package org.example.footballmanager.newLogic.sim.util;

import java.util.Random;

/**
 * Central random source for the sim engine. Every engine reads randomness from
 * here so a run becomes fully reproducible when a seed is applied before the
 * match starts. {@link #seed(long)} resets the source for the current thread —
 * SimMatchService seeds it from the fixture id, so re-simulating the same
 * fixture reproduces the exact same match. Without an explicit seed the source
 * falls back to an unseeded {@link Random} (current behaviour).
 *
 * Thread-local: parallel matches on different threads never share a source.
 */
public final class SimulationRandom {

    private static final ThreadLocal<Random> RNG = ThreadLocal.withInitial(Random::new);

    private SimulationRandom() {}

    /** Reset the per-thread random source with a fixed seed (reproducible run). */
    public static void seed(long seed) {
        RNG.set(new Random(seed));
    }

    /** The shared per-thread {@link Random}, for engines that need a Random instance. */
    public static Random rng() {
        return RNG.get();
    }

    public static double nextDouble() {
        return RNG.get().nextDouble();
    }

    public static boolean nextBoolean() {
        return RNG.get().nextBoolean();
    }

    public static int nextInt(int bound) {
        return RNG.get().nextInt(bound);
    }
}