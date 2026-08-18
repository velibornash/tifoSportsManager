package org.example.footballmanager.demo;

import java.util.Random;

/**
 * Izracunavanje kvaliteta izvodjenja PASS i SHOT akcija.
 *
 * Podržava tipove pasa:
 * - Dužina: SHORT (precizniji), LONG (daleko), THRU (u prostor ispred igrača)
 * - Visina: GROUND (može intercept), AIR (ne može intercept u letu, samo deflection na startu)
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

    /** Bazna devijacija pasa po poenu skill-a (cells). */
    private static final double PASS_DEVIATION_PER_SKILL_POINT = 0.15;

    /** Maksimalna devijacija suta po poenu skill-a (cells). */
    private static final double SHOT_DEVIATION_PER_SKILL_POINT = 0.12;

    /** Prag za uspesan pass: udaljenost od primaoca manja od ovoga = RECEIVED. */
    private static final double PASS_SUCCESS_THRESHOLD = 1.5;

    /** Prag za THRU pass: lopta stigne u zonu ispred igrača. */
    private static final double THRU_SUCCESS_THRESHOLD = 2.0;

    /** Prag za gol: udaljenost od centra gola manja od ovoga = GOAL. */
    private static final double SHOT_GOAL_THRESHOLD = 1.0;

    private final Random random;

    public ExecutionQuality(Random random) {
        this.random = random;
    }

    /**
     * Evaluacija kvaliteta pasa sa punom specifikacijom (dužina + visina).
     *
     * @param origin pozicija dodavaca
     * @param intendedTarget zamišljena meta (primaoc za SHORT/LONG, zona za THRU)
     * @param receiver primaoc (za SHORT/LONG) ili trkač (za THRU)
     * @param passLength SHORT / LONG / THRU
     * @param passHeight GROUND / AIR
     * @return PassResult sa skill-om, odstupnom metom i ishodom
     */
    public PassResult evaluatePass(Position origin, Position intendedTarget, Player receiver,
                                   Action.PassLength passLength, Action.PassHeight passHeight) {
        int skill = random.nextInt(20) + 1;
        double dx = intendedTarget.getColumn() - origin.getColumn();
        double dy = intendedTarget.getRow() - origin.getRow();
        double length = Math.hypot(dx, dy);

        // Dužina pasa utiče na maksimalnu devijaciju
        double lengthMultiplier = switch (passLength) {
            case SHORT -> 0.6;   // kraći pas = precizniji
            case LONG -> 1.3;    // duži pas = manje precizan
            case THRU -> 0.9;    // thru između
        };

        double maxDeviation = Math.min(
                (20 - skill) * PASS_DEVIATION_PER_SKILL_POINT * lengthMultiplier,
                Math.max(0.15, length * 0.65));

        double dirRow = length < 1e-9 ? 0 : dy / length;
        double dirCol = length < 1e-9 ? 1 : dx / length;
        double sideRow = -dirCol;
        double sideCol = dirRow;

        // AIR passes have slightly more lateral deviation (harder to control)
        double lateralMultiplier = passHeight == Action.PassHeight.AIR ? 1.4 : 1.0;

        double longitudinal = (random.nextDouble() * 2 - 1) * maxDeviation;
        double lateral = (random.nextDouble() * 2 - 1) * maxDeviation * 0.35 * lateralMultiplier;

        double actualRow = intendedTarget.getRow() + dirRow * longitudinal + sideRow * lateral;
        double actualCol = intendedTarget.getColumn() + dirCol * longitudinal + sideCol * lateral;
        Position actualTarget = new Position(
                MovementEngine.clamp(actualRow, 0, 8),
                MovementEngine.clamp(actualCol, 0, 7));

        boolean received;
        if (passLength == Action.PassLength.THRU) {
            // THRU: success if ball lands in zone ahead of runner
            double distance = MovementEngine.distance(actualTarget, receiver.getPosition());
            received = distance < THRU_SUCCESS_THRESHOLD;
        } else {
            // SHORT/LONG: success if ball reaches receiver
            double distance = MovementEngine.distance(actualTarget, receiver.getPosition());
            received = distance < PASS_SUCCESS_THRESHOLD;
        }

        return new PassResult(skill, actualTarget, received, passLength, passHeight);
    }

    /** Legacy overload for backward compatibility. */
    public PassResult evaluatePass(Position intendedTarget, Player receiver) {
        return evaluatePass(
                new Position(intendedTarget.getRow() - 100, intendedTarget.getColumn()),
                intendedTarget, receiver,
                Action.PassLength.SHORT, Action.PassHeight.GROUND);
    }

    public PassResult evaluatePass(Position origin, Position intendedTarget, Player receiver) {
        return evaluatePass(origin, intendedTarget, receiver,
                Action.PassLength.SHORT, Action.PassHeight.GROUND);
    }

    /**
     * Evaluacija kvaliteta suta.
     */
    public ShotResult evaluateShot(Position goalPosition) {
        int skill = random.nextInt(20) + 1;
        double maxDeviation = (20 - skill) * SHOT_DEVIATION_PER_SKILL_POINT;
        double deviation = random.nextDouble() * maxDeviation;
        double angle = random.nextDouble() * 2 * Math.PI;

        double actualRow = goalPosition.getRow() + Math.sin(angle) * deviation;
        double actualCol = goalPosition.getColumn() + Math.cos(angle) * deviation;
        Position actualTarget = new Position(
                MovementEngine.clamp(actualRow, 0, 8),
                MovementEngine.clamp(actualCol, 1, 6));

        double distance = MovementEngine.distance(actualTarget, goalPosition);
        boolean goal = distance < SHOT_GOAL_THRESHOLD;

        return new ShotResult(skill, actualTarget, goal);
    }

    public record PassResult(
            int skill,
            Position actualTarget,
            boolean received,
            Action.PassLength passLength,
            Action.PassHeight passHeight
    ) {
        public PassResult(int skill, Position actualTarget, boolean received) {
            this(skill, actualTarget, received, Action.PassLength.SHORT, Action.PassHeight.GROUND);
        }
    }

    public record ShotResult(int skill, Position actualTarget, boolean goal) {}
}
