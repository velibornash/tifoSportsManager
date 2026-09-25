package org.example.footballmanager.demo.swingUIDemo;

import java.util.List;
import java.util.Locale;

/** Detekcija, lifecycle i eksplicitna resolution granica duela. */
public final class DuelEngine {
    public static final double DEFAULT_DUEL_RADIUS = 0.5;

    private final SimulationState state;
    private final double duelRadius;
    private Duel activeDuel;
    private boolean activeDuelResolved;

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
        // Restartni prilaz je set-piece kretanje, ne contest. Izvođač mora
        // prvi stići do lopte bez da ga protivnik preuzme u tranzitu.
        if (action.getType() == Action.Type.CHASE && state.isAwayRestartPending()) {
            closeActiveDuel();
            return;
        }

        // Kod RECEIVE_PASS-a/CROSS-a/CENTER-a receiver je duel attacker; passer samo izvodi
        // loptu i ne ucestvuje u contestu prijema.
        Player attacker = (action.getType() == Action.Type.PASS
                || action.getType() == Action.Type.CROSS
                || action.getType() == Action.Type.CENTER)
                ? action.getTargetPlayer() : action.getActingPlayer();
        if (action.getType() == Action.Type.CHASE) {
            attacker = closestActiveChaserToBall();
            if (attacker == null) attacker = action.getActingPlayer();
        }
        if (attacker == null) {
            closeActiveDuel();
            return;
        }
        if (action.getType() == Action.Type.CHASE
                && MovementEngine.distance(attacker.getPosition(), state.getBall().getPosition())
                        > ActionEngine.POSSESSION_RADIUS) {
            closeActiveDuel();
            return;
        }
        if (state.isBlockedAfterDuel(attacker)) {
            closeActiveDuel();
            return;
        }
        Player contestTarget = contestTarget(action, attacker);
        if (contestTarget == null) {
            closeActiveDuel();
            return;
        }

        Position contestPosition = contestPosition(action, attacker, contestTarget);
        Player defender = closestOpponentTo(action, contestTarget, attacker, contestPosition);
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
        activeDuel = new Duel(attacker, defender, contestPosition, type, action.getActionId(), action);
        activeDuelResolved = false;
        state.showDuelVisual(activeDuel);
        state.getEventStore().append(new DuelEvent(
                state.getSimulationTick(), state.getRound(), activeDuel.getActionId(),
                DuelEvent.Phase.STARTED, type, playerId(attacker), playerId(defender),
                contestPosition, null, null, 0, 0));
        state.log("DUEL START: " + attacker.getLabel() + " vs " + defender.getLabel()
                + " | " + type + " | position " + format(contestPosition));
    }

    public Duel getActiveDuel() {
        return activeDuel;
    }

    /** Rezolucija je eksplicitna; pozivalac bira trenutak primene posledice. */
    public DuelResult resolveActiveDuel(DuelResolver resolver) {
        if (activeDuel == null || activeDuelResolved) return null;
        activeDuelResolved = true;
        DuelResult result = resolver.resolve(activeDuel);
        state.getEventStore().append(new DuelEvent(
                state.getSimulationTick(), state.getRound(), activeDuel.getActionId(),
                DuelEvent.Phase.RESOLVED, activeDuel.getType(),
                playerId(activeDuel.getAttacker()), playerId(activeDuel.getDefender()),
                activeDuel.getContestPosition(), playerId(result.winner()),
                result.outcome(), result.attackerPower(), result.defenderPower()));
        return result;
    }

    /** Zatvara duel odmah posle resolution-a da isti događaj ne traje kroz tickove. */
    public void closeAfterResolution() {
        if (activeDuel != null && activeDuelResolved) {
            closeActiveDuel();
        }
    }

    private Player contestTarget(Action action, Player attacker) {
        return switch (action.getType()) {
            case PASS, CROSS, CENTER -> action.getTargetPlayer();
            case CHASE, CARRY, SHOT, AERIAL -> attacker;
        };
    }

    private Position contestPosition(Action action, Player attacker, Player target) {
        if (action.getType() == Action.Type.CHASE) return state.getBall().getPosition();
        if (action.getType() == Action.Type.PASS) {
            // AIR pas: duel na mestu gde lopta pada (actualTarget), ne na primaocu
            if (action.getPassHeight() == Action.PassHeight.AIR) {
                return action.getActualTarget() != null ? action.getActualTarget() : target.getPosition();
            }
            return target.getPosition();
        }
        if (action.getType() == Action.Type.CROSS || action.getType() == Action.Type.CENTER) {
            return action.getActualTarget() != null ? action.getActualTarget() : target.getPosition();
        }
        if (action.getType() == Action.Type.SHOT) {
            return ActionEngine.goalPositionFor(action.getActingPlayer().getTeam());
        }
        return attacker.getPosition();
    }

    private DuelType typeFor(Action action) {
        return switch (action.getType()) {
            case CHASE -> DuelType.CHASE_BALL;
            case CARRY -> DuelType.DRIBBLE;
            case PASS -> action.getPassHeight() == Action.PassHeight.AIR
                    ? DuelType.AERIAL : DuelType.RECEIVE_PASS;
            case CROSS, CENTER -> DuelType.AERIAL;
            case SHOT -> DuelType.SHOT;
            case AERIAL -> DuelType.AERIAL;
        };
    }

    private Player closestOpponentTo(Action action, Player contestTarget,
                                     Player attacker, Position position) {
        List<Player> players = state.getPlayers();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player candidate : players) {
            if (candidate == attacker || candidate.getTeam().equals(contestTarget.getTeam())) continue;
            if (action.getType() == Action.Type.CHASE && !state.isActiveChaser(candidate)) continue;
            if (state.isBlockedAfterDuel(candidate)) continue;
            if (action.getType() == Action.Type.SHOT && !"GK".equals(candidate.getRole())) continue;
            if (action.getType() == Action.Type.AERIAL && "GK".equals(candidate.getRole())) continue;
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
        state.getEventStore().append(new DuelEvent(
                state.getSimulationTick(), state.getRound(), activeDuel.getActionId(),
                DuelEvent.Phase.ENDED, activeDuel.getType(),
                playerId(activeDuel.getAttacker()), playerId(activeDuel.getDefender()),
                activeDuel.getContestPosition(), null, null, 0, 0));
        activeDuel = null;
        activeDuelResolved = false;
    }

    private Player closestActiveChaserToBall() {
        Position ballPos = state.getBall().getPosition();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player chaser : state.getActiveChasers()) {
            if (chaser.isLocked() || state.isBlockedAfterDuel(chaser)) continue;
            double distance = MovementEngine.distance(chaser.getPosition(), ballPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chaser;
            }
        }
        return best;
    }

    private static String format(Position position) {
        return "(" + String.format(Locale.ROOT, "%.2f", position.getRow())
                + "," + String.format(Locale.ROOT, "%.2f", position.getColumn()) + ")";
    }

    private static String playerId(Player player) {
        return player == null ? null : player.getLabel();
    }
}
