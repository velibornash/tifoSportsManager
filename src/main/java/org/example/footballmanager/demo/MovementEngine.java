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

    public static final double PLAYER_SPEED = 0.04; // celija po tick-u simulacije (SIM logika — ne menja se)

    private final SimulationState state;

    public MovementEngine(SimulationState state) {
        this.state = state;
    }

    /** Pomera sve HOME igrace (osim zakljucanih) ka njihovim ciljevima. */
    public void moveAllTowardTargets() {
        for (Player p : state.getPlayers()) {
            if (!SimulationState.TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            if (p.isLocked()) {
                continue; // zakljucani igrac (npr. primaoc pasa) ne sme da se pomera
            }
            Position target = p.getTarget();
            if (target == null) {
                continue;
            }
            moveToward(p, target, PLAYER_SPEED);
            if (distance(p.getPosition(), target) < 1e-6) {
                p.setTarget(null);
            }
        }
    }

    /** Cilj udaljen najvise 1 celiju (8 smerova) od trenutne pozicije ka desired. */
    public static Position oneCellToward(Position from, Position to) {
        double dr = Math.signum(to.getRow() - from.getRow());
        double dc = Math.signum(to.getColumn() - from.getColumn());
        double nr = clamp(from.getRow() + dr, 1, 7);
        double nc = clamp(from.getColumn() + dc, 1, 6);
        return new Position(nr, nc);
    }

    /** Glatko pomeranje igraca ka cilju za fiksni korak (bez teleporta). */
    public static void moveToward(Player p, Position target, double speed) {
        Position pos = p.getPosition();
        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist <= speed) {
            p.setPosition(new Position(target.getRow(), target.getColumn()));
        } else {
            p.setPosition(new Position(pos.getRow() + dy / dist * speed,
                                        pos.getColumn() + dx / dist * speed));
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
