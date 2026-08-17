package org.example.footballmanager.demo;

/**
 * Odgovornost: KRETANJE IGRACA.
 *
 * Igraci se glatko krece ka svom targetu (max 1 celija po akciji).
 * Igraci se ponasaju kao zidovi — ne mogu da prodju jedan kroz drugog.
 * Kad naidje na prepreku, pokusava da je obidje pomeranjem u stranu
 * (perpendicularno na smer kretanja), pa nastavi ka cilju.
 */
public class MovementEngine {

    public static final double PLAYER_SPEED = 0.03;
    private static final double MIN_PLAYER_DISTANCE = 0.35;

    private final SimulationState state;

    public MovementEngine(SimulationState state) {
        this.state = state;
    }

    public void moveAllTowardTargets() {
        for (Player p : state.getPlayers()) {
            if (!SimulationState.TEAM_HOME.equals(p.getTeam())
                    && p != state.getReturningPlayer()
                    && !isActiveChase(p)
                    && p != state.getBall().getCarrier()) {
                continue;
            }
            if (p.isLocked()) {
                continue;
            }
            if (state.isBlockedAfterDuel(p) && p != state.getBall().getCarrier()) {
                continue;
            }
            Position target = p.getTarget();
            if (target == null) {
                continue;
            }
            // Only the player who actually HAS the ball (picked up) bypasses pace limit.
            // A player CHASING the ball is limited by their pace skill.
            boolean isCarrier = p == state.getBall().getCarrier();
            if (!isCarrier && !isActiveChase(p)) {
                Position roundStart = state.getRoundStartPosition(p);
                int pace = state.getRoundPaceSkill(p);
                double maxDistance = pace / 20.0;
                if (roundStart != null) {
                    double alreadyMoved = Math.max(
                            Math.abs(p.getPosition().getRow() - roundStart.getRow()),
                            Math.abs(p.getPosition().getColumn() - roundStart.getColumn()));
                    if (alreadyMoved >= maxDistance - 1e-6) {
                        // Target se ne sme obrisati samo zato sto je igrac
                        // potrosio kretanje ove runde. Sledeci tick/runda mora
                        // da nastavi isti Chase ka lopti.
                        continue;
                    }
                }
            }
            Position current = p.getPosition();
            Position proposed = moveProposal(p, target, PLAYER_SPEED);
            Position safe = findSafePosition(p, proposed, target);
            p.setPosition(safe);
            if (distance(safe, target) < 1e-6) {
                p.setTarget(null);
            } else if (distance(safe, current) < 1e-6) {
                // Igrac je trenutno blokiran. Target ostaje sacuvan da bi
                // mogao da se obidje prepreka; nikakav "stuck" ishod se ne
                // proizvodi iz jednog neuspesnog tick-a.
            }
        }
    }

    private boolean isActiveChase(Player player) {
        Action action = state.getAction();
        return action != null
                && action.getType() == Action.Type.CHASE
                && action.getActingPlayer() == player;
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

    /**
     * Predlaze novu poziciju: glatko pomera igraca ka targetu za fiksni korak.
     */
    private static Position moveProposal(Player p, Position target, double speed) {
        Position pos = p.getPosition();
        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist <= speed) {
            return target;
        }
        double minRow = target.getRow() < 1 ? 0 : 1;
        double maxRow = target.getRow() > 7 ? 8 : 7;
        double minCol = target.getColumn() < 1 ? 0 : 1;
        double maxCol = target.getColumn() > 6 ? 7 : 6;
        return new Position(
                clamp(pos.getRow() + dy / dist * speed, minRow, maxRow),
                clamp(pos.getColumn() + dx / dist * speed, minCol, maxCol));
    }

    /**
     * Ako bi se igrac preklopio sa nekim na proposed poziciji, pokusava
     * da nadje sigurnu poziciju: prvo perpendicularno u jednu pa drugu
     * stranu, zatim samo X ili samo Y komponentu. Ako nista ne prolazi —
     * igrac ostaje gde jeste (glatko staje).
     */
    private Position findSafePosition(Player p, Position proposed, Position target) {
        if (!wouldOverlap(p, proposed)) {
            return proposed;
        }
        Position current = p.getPosition();
        double dx = proposed.getColumn() - current.getColumn();
        double dy = proposed.getRow() - current.getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) {
            return current;
        }

        double step = Math.min(PLAYER_SPEED, len);
        double perpX = -dy / len * step;
        double perpY = dx / len * step;

        Position best = null;
        double bestScore = Double.MAX_VALUE;

        Position[] candidates = {
            clampPos(current.getRow() + perpY, current.getColumn() + perpX),
            clampPos(current.getRow() - perpY, current.getColumn() - perpX),
            clampPos(current.getRow() + perpY * 0.5, current.getColumn() + perpX * 0.5),
            clampPos(current.getRow() - perpY * 0.5, current.getColumn() - perpX * 0.5),
            clampPos(current.getRow(), current.getColumn() + dx),
            clampPos(current.getRow() + dy, current.getColumn()),
            // Dodatni radijalni koraci daju igracu prostor da izadje iz
            // uskog prolaza kada su obe direktne bočne putanje zauzete.
            clampPos(current.getRow() + step, current.getColumn()),
            clampPos(current.getRow() - step, current.getColumn()),
            clampPos(current.getRow(), current.getColumn() + step),
            clampPos(current.getRow(), current.getColumn() - step),
            clampPos(current.getRow() + step, current.getColumn() + step),
            clampPos(current.getRow() + step, current.getColumn() - step),
            clampPos(current.getRow() - step, current.getColumn() + step),
            clampPos(current.getRow() - step, current.getColumn() - step),
        };

        for (Position alt : candidates) {
            if (!wouldOverlap(p, alt)) {
                double score = distance(alt, target);
                if (score < bestScore) {
                    best = alt;
                    bestScore = score;
                }
            }
        }
        return best != null ? best : current;
    }

    private boolean wouldOverlap(Player p, Position candidate) {
        for (Player other : state.getPlayers()) {
            if (other == p) {
                continue;
            }
            if (distance(candidate, other.getPosition()) < MIN_PLAYER_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private static Position clampPos(double row, double col) {
    // Allow row 8 for goal celebrations (ball moves through goal mouth)
    // Allow row 0 for misses (ball goes out of bounds)
    if (row == 8.0) {
        return new Position(8.0, clamp(col, 1, 6));
    }
    if (row == 0.0) {
        return new Position(0.0, clamp(col, 1, 6));
    }
    return new Position(clamp(row, 1, 7), clamp(col, 1, 6));
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
