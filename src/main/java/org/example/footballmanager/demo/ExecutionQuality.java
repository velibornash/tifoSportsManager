package org.example.footballmanager.demo;

import java.util.Random;

/**
 * Izracunavanje kvaliteta izvodjenja PASS i SHOT akcija.
 *
 * Podržava tipove pasa:
 * - Dužina: SHORT (precizniji), LONG (daleko), THRU (u prostor ispred igrača)
 * - Visina: GROUND (može intercept), AIR (ne može intercept u letu, samo deflection na startu)
 *
 * PASS skill se čita iz passer.getSkills().passing(). Legacy overloade koji
 * ne prime passer koriste random skill (1-20) isključivo radi testova.
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
     * Glavna evaluacija — čita skill iz passer.getSkills().passing().
     *
     * @param passer igrač koji izvodi pas (za skill)
     * @param origin pozicija dodavača
     * @param intendedTarget zamišljena meta
     * @param receiver primaoc (SHORT/LONG) ili trkač (THRU)
     * @param passLength SHORT / LONG / THRU
     * @param passHeight GROUND / AIR
     */
    public PassResult evaluatePass(Player passer, Position origin, Position intendedTarget, Player receiver,
                                   Action.PassLength passLength, Action.PassHeight passHeight) {
        int skill = Math.max(1, Math.min(20, (int) Math.round(passer.getSkills().passing())));
        return evaluatePassWithSkill(skill, origin, intendedTarget, receiver, passLength, passHeight);
    }

    /** Preko passer-a, default SHORT/GROUND (za CROSS/CENTER). */
    public PassResult evaluatePass(Player passer, Position origin, Position intendedTarget, Player receiver) {
        return evaluatePass(passer, origin, intendedTarget, receiver,
                Action.PassLength.SHORT, Action.PassHeight.GROUND);
    }

    /** Legacy: random skill, za testove. */
    public PassResult evaluatePass(Position origin, Position intendedTarget, Player receiver) {
        int skill = random.nextInt(20) + 1;
        return evaluatePassWithSkill(skill, origin, intendedTarget, receiver,
                Action.PassLength.SHORT, Action.PassHeight.GROUND);
    }

    /** Legacy: random skill, za testove. */
    public PassResult evaluatePass(Position intendedTarget, Player receiver) {
        return evaluatePass(
                new Position(intendedTarget.getRow() - 100, intendedTarget.getColumn()),
                intendedTarget, receiver);
    }

    /** Privatno: sva logika devijacije, prima skill kao int. */
    private PassResult evaluatePassWithSkill(int skill, Position origin, Position intendedTarget, Player receiver,
                                             Action.PassLength passLength, Action.PassHeight passHeight) {
        double dx = intendedTarget.getColumn() - origin.getColumn();
        double dy = intendedTarget.getRow() - origin.getRow();
        double length = Math.hypot(dx, dy);

        double lengthMultiplier = switch (passLength) {
            case SHORT -> 0.6;
            case LONG -> 1.3;
            case THRU -> 0.9;
        };

        double maxDeviation = Math.min(
                (20 - skill) * PASS_DEVIATION_PER_SKILL_POINT * lengthMultiplier,
                Math.max(0.15, length * 0.65));

        double dirRow = length < 1e-9 ? 0 : dy / length;
        double dirCol = length < 1e-9 ? 1 : dx / length;
        double sideRow = -dirCol;
        double sideCol = dirRow;

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
            received = MovementEngine.distance(actualTarget, receiver.getPosition()) < THRU_SUCCESS_THRESHOLD;
        } else {
            received = MovementEngine.distance(actualTarget, receiver.getPosition()) < PASS_SUCCESS_THRESHOLD;
        }

        return new PassResult(skill, actualTarget, received, passLength, passHeight);
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
