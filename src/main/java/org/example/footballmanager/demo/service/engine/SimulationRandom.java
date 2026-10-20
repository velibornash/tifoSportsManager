package org.example.footballmanager.demo.service.engine;

import java.util.Random;

/**
 * Seeded Randomness — corePrinciples Section 10.
 *
 * "Randomness must be seeded and reproducible."
 * "A simulation should be reproducible when supplied with same initial state + seed."
 *
 * The seed must be part of simulation metadata.
 */
public class SimulationRandom {

    private final long seed;
    private final Random random;

    public SimulationRandom(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    /** Create with random seed (for non-reproducible runs). */
    public SimulationRandom() {
        this(System.nanoTime());
    }

    public long getSeed() { return seed; }
    public Random getRandom() { return random; }

    /** Delegates to underlying Random. */
    public double nextDouble() { return random.nextDouble(); }
    public int nextInt(int bound) { return random.nextInt(bound); }
    public int nextInt() { return random.nextInt(); }
    public boolean nextBoolean() { return random.nextBoolean(); }

    /**
     * Return a value in [0, bound) with skill-based weighting.
     * Higher skill = higher average value.
     */
    public double skillWeighted(double skill, double bound) {
        double base = random.nextDouble() * bound;
        double skillFactor = skill / 20.0;
        return base * (0.5 + 0.5 * skillFactor);
    }

    /** Create a new SimulationRandom with a derived seed (for reproducible sub-simulations). */
    public SimulationRandom derive(String context) {
        return new SimulationRandom(seed ^ context.hashCode());
    }
}
