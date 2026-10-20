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
    /**
     * Seeds the shared generator.
     *
     * <p><b>One value is discarded, and that is the whole point of this method.</b> The first output of
     * a freshly constructed {@link Random} is not usable for a narrow question. Measured over seeds
     * 1..500 — which is exactly the shape of {@code fixture.getId()}, the seed the engine uses — the
     * first draw is a constant:
     *
     * <pre>
     *   first nextInt(2)      0 one way, 500 the other
     *   first nextBoolean()   500 one way,   0 the other
     *   first nextDouble()    0 one way, 500 the other
     *   nextInt(2) after one discarded value: 252 / 248
     * </pre>
     *
     * <p>A narrow first draw takes the top bits of the freshly scrambled seed, and for a small seed
     * those bits are always the same. So anything that seeds and then immediately asks a yes/no question
     * gets the same answer every time — a coin flip in the match engine that is not a coin flip, decided
     * by which number a fixture id happens to be.
     *
     * <p>Found by the penalty shootout, whose toss is the engine's first draw after seeding. It is fixed
     * <i>here</i> rather than at that call site, because the call site is one of however many there are,
     * and the next one added tomorrow would be biased too.
     *
     * <p>Discarding a value changes every seeded run, so a replay regenerated from the same seed will
     * differ from the one that was stored. It is deterministic either way, which is what
     * {@code ProposalMatchExporter} and the viewer launchers actually need.
     */
    public static void seed(long seed) {
        Random random = new Random(seed);
        // Wide, so it is genuinely a different part of the stream and not another narrow read.
        random.nextDouble();
        RNG.set(random);
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