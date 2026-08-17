package org.example.footballmanager.demo;

/**
 * Odgovornost: KRETANJE IGRACA.
 *
 * Pokriva postojece ponasanje kretanja:
 *  - jedno-celijski takticki korak ({@link #oneCellToward} — 8 smerova, max 1 celija)
 *  - glatko pomeranje ka cilju konstantnom brzinom ({@link #moveToward})
 *  - pomeranje svih HOME igraca ka ciljevima na svakom tick-u
 *    ({@link #moveAllTowardTargets})
 *
 * Semantika kretanja je IDENTICNA kao pre refaktora: bez ubrzanja, stamina,
 * brzinskih atributa, kolizija i pathfinding-a. {@link #PLAYER_SPEED} se ne menja.
 *
 * Sadrzi i staticke geometrijske pomocnike ({@link #distance}, {@link #clamp})
 * koje koriste ostale komponente.
 */
public class MovementEngine {

    public static final double PLAYER_SPEED = 0.04;
    private static final double TURN_RATE = 0.25;

    private final SimulationState state;

    public MovementEngine(SimulationState state) {
        this.state = state;
    }

    public void moveAllTowardTargets() {
        for (Player p : state.getPlayers()) {
            if (!SimulationState.TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            if (p.isLocked()) {
                continue;
            }
            Position target = p.getTarget();
            if (target == null) {
                p.setVelX(0);
                p.setVelY(0);
                continue;
            }
            boolean isCarrier = p == state.getCarrier();
            if (!isCarrier) {
                Position roundStart = state.getRoundStartPosition(p);
                if (roundStart != null) {
                    double alreadyMoved = Math.max(
                            Math.abs(p.getPosition().getRow() - roundStart.getRow()),
                            Math.abs(p.getPosition().getColumn() - roundStart.getColumn()));
                    if (alreadyMoved >= 1.0 - 1e-6) {
                        p.setTarget(null);
                        p.setVelX(0);
                        p.setVelY(0);
                        continue;
                    }
                }
            }
            if (isCarrier) {
                moveDirectly(p, target, PLAYER_SPEED);
            } else {
                moveWithInertia(p, target, PLAYER_SPEED);
            }
            if (distance(p.getPosition(), target) < 1e-6) {
                p.setTarget(null);
                p.setVelX(0);
                p.setVelY(0);
            }
        }
    }

    public MovementProfile profileFor(Player player) {
        return MovementProfile.standard();
    }

    public static Position oneCellToward(Position from, Position to) {
        double dr = Math.signum(to.getRow() - from.getRow());
        double dc = Math.signum(to.getColumn() - from.getColumn());
        double nr = clamp(from.getRow() + dr, 1, 7);
        double nc = clamp(from.getColumn() + dc, 1, 6);
        return new Position(nr, nc);
    }

    private static void moveWithInertia(Player p, Position target, double speed) {
        Position pos = p.getPosition();
        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-6) {
            return;
        }
        double desDirX = dx / dist;
        double desDirY = dy / dist;
        double newVX = p.getVelX() + (desDirX - p.getVelX()) * TURN_RATE;
        double newVY = p.getVelY() + (desDirY - p.getVelY()) * TURN_RATE;
        double vLen = Math.hypot(newVX, newVY);
        if (vLen > 1e-6) {
            newVX /= vLen;
            newVY /= vLen;
        }
        p.setVelX(newVX);
        p.setVelY(newVY);
        double step = Math.min(speed, dist);
        double nr = clamp(pos.getRow() + newVY * step, 1, 7);
        double nc = clamp(pos.getColumn() + newVX * step, 1, 6);
        p.setPosition(new Position(nr, nc));
    }

    private static void moveDirectly(Player p, Position target, double speed) {
        Position pos = p.getPosition();
        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist <= speed) {
            p.setPosition(new Position(target.getRow(), target.getColumn()));
            p.setVelX(dist > 1e-6 ? dx / dist : 0);
            p.setVelY(dist > 1e-6 ? dy / dist : 0);
        } else {
            p.setPosition(new Position(pos.getRow() + dy / dist * speed,
                                        pos.getColumn() + dx / dist * speed));
            p.setVelX(dx / dist);
            p.setVelY(dy / dist);
        }
    }

    public static double distance(Position a, Position b) {
        double dr = a.getRow() - b.getRow();
        double dc = a.getColumn() - b.getColumn();
        return Math.hypot(dr, dc);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
