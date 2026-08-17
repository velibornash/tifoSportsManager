package org.example.footballmanager.demo;

/**
 * Odgovornost: KRETANJE LOPTE.
 *
 * Pokriva postojece ponasanje lopte:
 *  - let pasa i suteva ka cilju ({@link #moveBallToward}, {@link #BALL_SPEED})
 *  - glatko pracenje nosioca ({@link #followCarrier}, {@link #CARRIER_FOLLOW_SPEED})
 *  - rastojanje hvatanja ({@link #PICKUP_DISTANCE})
 *
 * Fizika i animacija su IDENTICNE kao pre refaktora — nema novih mehanika.
 */
public class BallMovementEngine {

    public static final double BALL_SPEED = 0.037;          // 50% sporije
    public static final double CARRIER_FOLLOW_SPEED = 0.055; // 50% sporije
    public static final double PICKUP_DISTANCE = 0.5;       // na koliko igrac "hvata" loptu

    private final SimulationState state;

    public BallMovementEngine(SimulationState state) {
        this.state = state;
    }

    /** Pomera loptu ka trenutnom cilju (ball.getTarget()) za BALL_SPEED. */
    public void moveBallTowardCurrentTarget() {
        moveBallToward(state.getBall(), state.getBall().getTarget(), BALL_SPEED);
    }

    /**
     * Lopta glatko prati nosioca (bez teleporta): svaki tick se pomera ka
     * nosiocu za fiksni korak dok se ne stigne. Ako nema nosioca — nista.
     */
    public void followCarrier() {
        Ball ball = state.getBall();
        if (ball.getCarrier() == null) {
            return;
        }
        Position bp = ball.getPosition();
        Position cp = ball.getCarrier().getPosition();
        double dr = cp.getRow() - bp.getRow();
        double dc = cp.getColumn() - bp.getColumn();
        double dist = Math.hypot(dr, dc);
        if (dist <= CARRIER_FOLLOW_SPEED) {
            ball.setPosition(cp);
        } else {
            ball.setPosition(new Position(bp.getRow() + dr / dist * CARRIER_FOLLOW_SPEED,
                                          bp.getColumn() + dc / dist * CARRIER_FOLLOW_SPEED));
        }
    }

    /** Pomera loptu ka zadatom cilju za zadatu brzinu (bez teleporta). */
    public static void moveBallToward(Ball ball, Position target, double speed) {
        if (target == null) {
            return;
        }
        Position pos = ball.getPosition();
        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist <= speed) {
            ball.setPosition(new Position(target.getRow(), target.getColumn()));
        } else {
            ball.setPosition(new Position(pos.getRow() + dy / dist * speed,
                                          pos.getColumn() + dx / dist * speed));
        }
    }
}
