package org.example.footballmanager.demo;

import java.util.List;
import java.util.Locale;

/** Detekcija i lifecycle duela na kontinuiranim koordinatama. */
public final class DuelEngine {
    public static final double DEFAULT_DUEL_RADIUS = 0.5;

    private final SimulationState state;
    private final double duelRadius;
    private Duel activeDuel;

    public DuelEngine(SimulationState state) {
        this(state, DEFAULT_DUEL_RADIUS);
    }

    public DuelEngine(SimulationState state, double duelRadius) {
        this.state = state;
        this.duelRadius = duelRadius;
    }

    /** Osvežava jedan jedini aktivan duel za trenutnu akciju. */
    public void update(Action action) {
        if (action == null) {
            closeActiveDuel();
            return;
        }

        Player attacker = action.getActingPlayer();
        if (attacker == null) {
            closeActiveDuel();
            return;
        }
        Player contestTarget = contestTarget(action, attacker);
        if (contestTarget == null) {
            closeActiveDuel();
            return;
        }

        Position contestPosition = contestPosition(action, attacker, contestTarget);
        Player defender = closestOpponentTo(contestTarget, attacker, contestPosition);
        if (defender == null || MovementEngine.distance(contestPosition,
                defender.getPosition()) > duelRadius) {
            closeActiveDuel();
            return;
        }

        DuelType type = typeFor(action);
        if (activeDuel != null && activeDuel.getAttacker() == attacker
                && activeDuel.getDefender() == defender && activeDuel.getType() == type) {
            return;
        }

        closeActiveDuel();
        activeDuel = new Duel(attacker, defender, contestPosition, type);
        state.log("DUEL START: " + attacker.getLabel() + " vs " + defender.getLabel()
                + " | " + type + " | position " + format(contestPosition));
    }

    public Duel getActiveDuel() {
        return activeDuel;
    }

    /** Rezolucija je eksplicitna; pozivalac bira trenutak primene posledice. */
    public DuelResult resolveActiveDuel(DuelResolver resolver) {
        return activeDuel == null ? null : resolver.resolve(activeDuel);
    }

    private Player contestTarget(Action action, Player attacker) {
        return switch (action.getType()) {
            case PASS -> action.getTargetPlayer();
            case CHASE, CARRY, SHOT -> attacker;
        };
    }

    private Position contestPosition(Action action, Player attacker, Player target) {
        if (action.getType() == Action.Type.CHASE) return state.getBall().getPosition();
        if (action.getType() == Action.Type.PASS) return target.getPosition();
        return attacker.getPosition();
    }

    private DuelType typeFor(Action action) {
        return switch (action.getType()) {
            case CHASE -> DuelType.CHASE_BALL;
            case CARRY -> DuelType.DRIBBLE;
            case PASS -> DuelType.RECEIVE_PASS;
            case SHOT -> DuelType.SHOT;
        };
    }

    private Player closestOpponentTo(Player contestTarget, Player attacker, Position position) {
        List<Player> players = state.getPlayers();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player candidate : players) {
            if (candidate == attacker || candidate.getTeam().equals(contestTarget.getTeam())) continue;
            double distance = MovementEngine.distance(candidate.getPosition(), position);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void closeActiveDuel() {
        if (activeDuel == null) return;
        state.log("DUEL END: " + activeDuel.getAttacker().getLabel() + " vs "
                + activeDuel.getDefender().getLabel() + " | " + activeDuel.getType());
        activeDuel = null;
    }

    private static String format(Position position) {
        return "(" + String.format(Locale.ROOT, "%.2f", position.getRow())
                + "," + String.format(Locale.ROOT, "%.2f", position.getColumn()) + ")";
    }
}
