package org.example.footballmanager.demo;

import java.util.Random;

/**
 * Izracunavanje kvaliteta izvodjenja PASS i SHOT akcija.
 *
 * Za svaku PASS/SHOT akciju se generise demo skill (1-20) koji
 * odredjuje koliko odstvarna meta odstaje od zamišljene.
 * Visok skill → mala devijacija → veca sansa za normalan ishod.
 * Nizak skill → velika devijacija → veca sansa za loose ball / miss.
 *
 * Ova klasa ce se kasnije zameniti pravim skillom na Player modelu.
 * Tada ce se skill citati iz PlayerSkills umesto da se generise random.
 */
public class ExecutionQuality {

    /** Maksimalna devijacija pasa po poenu skill-a (cells). */
    private static final double PASS_DEVIATION_PER_SKILL_POINT = 0.15;

    /** Maksimalna devijacija suta po poenu skill-a (cells). */
    private static final double SHOT_DEVIATION_PER_SKILL_POINT = 0.12;

    /** Prag za uspesan pass: udaljenost od primaoca manja od ovoga = RECEIVED. */
    private static final double PASS_SUCCESS_THRESHOLD = 1.5;

    /** Prag za gol: udaljenost od centra gola manja od ovoga = GOAL. */
    private static final double SHOT_GOAL_THRESHOLD = 1.0;

    private final Random random;

    public ExecutionQuality(Random random) {
        this.random = random;
    }

    /**
     * Evaluacija kvaliteta pasa.
     *
     * @param intendedTarget pozicija primaoca (zamišljena meta)
     * @param receiver primaoc pasa (za proveru udaljenosti)
     * @return PassResult sa skill-om, odstupnom metom i ishodom
     */
    public PassResult evaluatePass(Position intendedTarget, Player receiver) {
        int skill = random.nextInt(20) + 1;
        double maxDeviation = (20 - skill) * PASS_DEVIATION_PER_SKILL_POINT;
        double deviation = random.nextDouble() * maxDeviation;
        double angle = random.nextDouble() * 2 * Math.PI;

        double actualRow = intendedTarget.getRow() + Math.sin(angle) * deviation;
        double actualCol = intendedTarget.getColumn() + Math.cos(angle) * deviation;
        Position actualTarget = new Position(
                MovementEngine.clamp(actualRow, 1, 7),
                MovementEngine.clamp(actualCol, 1, 6));

        double distance = MovementEngine.distance(actualTarget, receiver.getPosition());
        boolean received = distance < PASS_SUCCESS_THRESHOLD;

        return new PassResult(skill, actualTarget, received);
    }

    /**
     * Evaluacija kvaliteta suta.
     *
     * @param goalPosition pozicija gola (zamišljena meta)
     * @return ShotResult sa skill-om, odstupnom metom i ishodom
     */
    public ShotResult evaluateShot(Position goalPosition) {
        int skill = random.nextInt(20) + 1;
        double maxDeviation = (20 - skill) * SHOT_DEVIATION_PER_SKILL_POINT;
        double deviation = random.nextDouble() * maxDeviation;
        double angle = random.nextDouble() * 2 * Math.PI;

        double actualRow = goalPosition.getRow() + Math.sin(angle) * deviation;
        double actualCol = goalPosition.getColumn() + Math.cos(angle) * deviation;
        Position actualTarget = new Position(
                MovementEngine.clamp(actualRow, 1, 7),
                MovementEngine.clamp(actualCol, 1, 6));

        double distance = MovementEngine.distance(actualTarget, goalPosition);
        boolean goal = distance < SHOT_GOAL_THRESHOLD;

        return new ShotResult(skill, actualTarget, goal);
    }

    public record PassResult(int skill, Position actualTarget, boolean received) {}
    public record ShotResult(int skill, Position actualTarget, boolean goal) {}
}
