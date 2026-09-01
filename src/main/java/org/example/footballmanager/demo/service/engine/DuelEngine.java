package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;

import java.util.List;

/**
 * Duel detection, lifecycle, and resolution coordination.
 * Identical logic to demo/DuelEngine but using service model.
 */
public class DuelEngine {

    // Duel radii — 1 cell ≈ 14m × 10m (full field = 98m × 60m).
// DRIBBLE_DUEL_RADIUS 0.5 cells (~7 m) — a defender alongside the carrier
// triggers the DRIBBLE duel. The user overrode the previous 0.15 (~2 m):
// at 0.15 the defender had to be virtually on top of the carrier, so the
// carrier and defender just ran overlapped in the same cell with no tackle,
// and the carrier visually "stopped"/crowded. With 0.5 the defender who has
// closed the gap via the TYPE A press override engages the carrier as soon as
// they come alongside, and snapPlayersForDuel pulls both to the contest point.
    public static final double DEFAULT_DUEL_RADIUS = 0.2;
    public static final double DRIBBLE_DUEL_RADIUS = 0.5;
    public static final double RECEIVE_PASS_RADIUS = 0.2;
    // Aerial (cross/center/header) challenges use a wider radius — a defender
    // that is marking the landing attacker (0.45 cells goal-side) must contest
    // the incoming ball. Per user: corner set-piece players get a 1v1 marking
    // override so cross deliveries generate aerial duels (not uncontested catches).
    public static final double AERIAL_DUEL_RADIUS = 0.5;
    private static final int DUEL_COOLDOWN_TICKS = 10;
    // DRIBBLE cooldown 8 → 7 ticks so a defender can re-press within ~2 seconds
    // after losing a tackle (matches the user rule "6-8 ticks cooldown" for the
    // losing duelist — engaged within ~1.5 s of losing the ball).
    private static final int DRIBBLE_DUEL_COOLDOWN_TICKS = 7;

    private final MatchState state;
    private final DuelResolver resolver;
    private final MatchRecorder recorder;
    private final double duelRadius;

    private Player activeDuelAttacker;
    private Player activeDuelDefender;
    private DuelType activeDuelType;
    private Position activeDuelPosition;
    private boolean activeDuelResolved;
    private int lastDuelTick = -DUEL_COOLDOWN_TICKS;

    public DuelEngine(MatchState state, DuelResolver resolver, MatchRecorder recorder) {
        this(state, resolver, recorder, DEFAULT_DUEL_RADIUS);
    }

    public DuelEngine(MatchState state, DuelResolver resolver, MatchRecorder recorder, double duelRadius) {
        this.state = state;
        this.resolver = resolver;
        this.recorder = recorder;
        this.duelRadius = duelRadius;
    }

    public void update(Action action) {
        if (action == null) {
            closeActiveDuel();
            return;
        }
        if (action.getType() == ActionType.CHASE && state.isAwayRestartPending()) {
            closeActiveDuel();
            return;
        }

        Player attacker = (action.getType() == ActionType.PASS
                || action.getType() == ActionType.CROSS
                || action.getType() == ActionType.CENTER)
                ? action.getTargetPlayer() : action.getActingPlayer();
        if (action.getType() == ActionType.CHASE) {
            attacker = closestActiveChaserToBall();
            if (attacker == null) attacker = action.getActingPlayer();
        }
        if (attacker == null) { closeActiveDuel(); return; }
        if (action.getType() == ActionType.CHASE
                && SimUtils.distance(attacker.getPosition(), state.getBall().getPosition())
                        > ActionEngine.POSSESSION_RADIUS) {
            closeActiveDuel(); return;
        }
        if (state.isBlockedAfterDuel(attacker)) { closeActiveDuel(); return; }

        Player contestTarget = contestTarget(action, attacker);
        if (contestTarget == null) { closeActiveDuel(); return; }

        Position contestPosition = contestPosition(action, attacker, contestTarget);
        Player defender = closestOpponentTo(action, contestTarget, attacker, contestPosition);
        DuelType type = typeFor(action);
        double radiusForType = switch (type) {
            case RECEIVE_PASS -> RECEIVE_PASS_RADIUS;
            case DRIBBLE -> DRIBBLE_DUEL_RADIUS;
            case SHOT -> 0.3;  // tight block — defender must be right next to shooter
            case AERIAL -> AERIAL_DUEL_RADIUS;
            default -> duelRadius;
        };
        if (defender == null || SimUtils.distance(contestPosition, defender.getPosition()) > radiusForType) {
            closeActiveDuel(); return;
        }

        if (activeDuelAttacker == attacker && activeDuelDefender == defender && activeDuelType == type) {
            return;
        }

        // Duel cooldown: prevent duels from re-triggering too quickly
        // (e.g., same pair dueling every tick during carry).
        // DRIBBLE duels use a shorter cooldown so that a carrier entering a crowd
        // of defenders triggers multiple tackle attempts during a single CARRY action
        // instead of being locked out for 20 ticks (~30s of silent play).
        int cooldownForType = (type == DuelType.DRIBBLE) ? DRIBBLE_DUEL_COOLDOWN_TICKS : DUEL_COOLDOWN_TICKS;
        if (state.getSimulationTick() - lastDuelTick < cooldownForType) {
            closeActiveDuel();
            return;
        }

        closeActiveDuel();
        activeDuelAttacker = attacker;
        activeDuelDefender = defender;
        activeDuelType = type;
        activeDuelPosition = contestPosition;
        activeDuelResolved = false;
        lastDuelTick = Math.toIntExact(state.getSimulationTick());

        // Visually engage the two contestants: pull each (non-carrier) player
        // 60% of the way toward the contest point so they appear to collide on
        // the canvas at the moment of the tackle. This is their NEW position for
        // the duel — they must NOT be reset/clamped back to where they came from
        // after resolution (that would look like teleporting back).
        snapPlayersForDuel(attacker, defender, contestPosition);

        recorder.appendEvent(state.getSimulationTick(), state.getRound(), action.getActionId(),
                "DUEL_START", type + " " + attacker.getLabel() + " vs " + defender.getLabel(),
                state);
    }

    /** Resolve the active duel using skill-weighted probability. */
    public DuelResolver.DuelResult resolveActiveDuel(Action action) {
        if (activeDuelAttacker == null || activeDuelResolved) return null;
        activeDuelResolved = true;
        DuelResolver.DuelResult result = resolver.resolve(
                activeDuelAttacker, activeDuelDefender, activeDuelType, action);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                action != null ? action.getActionId() : null,
                "DUEL_RESOLVED",
                activeDuelType + " " + activeDuelAttacker.getLabel() + " vs "
                        + activeDuelDefender.getLabel() + " | winner=" + result.winner().getLabel()
                        + " (att=" + result.attackerPower() + " def=" + result.defenderPower() + ")",
                state);
        return result;
    }

    public void closeAfterResolution() {
        if (activeDuelAttacker != null && activeDuelResolved) closeActiveDuel();
    }

    public Player getActiveDuelAttacker() { return activeDuelAttacker; }
    public Player getActiveDuelDefender() { return activeDuelDefender; }
    public DuelType getActiveDuelType() { return activeDuelType; }

    private void closeActiveDuel() {
        activeDuelAttacker = null;
        activeDuelDefender = null;
        activeDuelType = null;
        activeDuelPosition = null;
        activeDuelResolved = false;
    }

    /**
     * Snap both duel participants toward the contest position so they appear
     * physically engaged in a tackle/challenge on the viewer canvas.
     * Each player moves 60% of the way from their current position to the
     * contest point. Per corePrinciples §13, the tugged position IS their new
     * current position — the simulation must never reset/clamp them back toward
     * the position they came from (that reads as a teleport back).
     */
    private void snapPlayersForDuel(Player attacker, Player defender, Position contestPos) {
        double snapFactor = 0.60;
        for (Player p : new Player[]{attacker, defender}) {
            if (p == null || p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (p == state.getCarrier()) continue;
            double dx = contestPos.getColumn() - p.getPosition().getColumn();
            double dy = contestPos.getRow() - p.getPosition().getRow();
            double dist = Math.hypot(dx, dy);
            if (dist < 0.05) continue;
            double moveDist = Math.min(dist, dist * snapFactor);
            Position newPos = new Position(
                    SimUtils.clamp(p.getPosition().getRow() + dy / dist * moveDist, 0.5, 7.5),
                    SimUtils.clamp(p.getPosition().getColumn() + dx / dist * moveDist, 0.5, 6.5));
            // A goalkeeper must never be dragged upfield by a duel snap — keep
            // them right in front of their own goal (user rule).
            if ("GK".equals(p.getRole())) {
                double row = GoalkeeperMovementEngine.clampGkToZone(
                        p, newPos.getRow());
                newPos = new Position(row, newPos.getColumn());
            }
            p.setPosition(newPos);
        }
    }

    private Player contestTarget(Action action, Player attacker) {
        return switch (action.getType()) {
            case PASS, CROSS, CENTER -> action.getTargetPlayer();
            case CHASE, CARRY, SHOT, AERIAL -> attacker;
        };
    }

    private Position contestPosition(Action action, Player attacker, Player target) {
        if (action.getType() == ActionType.CHASE) return state.getBall().getPosition();
        if (action.getType() == ActionType.PASS) {
            if (action.getPassHeight() == PassHeight.AIR) {
                return action.getActualTarget() != null ? action.getActualTarget() : target.getPosition();
            }
            return target.getPosition();
        }
        if (action.getType() == ActionType.CROSS || action.getType() == ActionType.CENTER) {
            return action.getActualTarget() != null ? action.getActualTarget() : target.getPosition();
        }
        if (action.getType() == ActionType.SHOT) {
            return attacker.getPosition();  // block happens near the shooter, not the goal
        }
        return attacker.getPosition();
    }

    private DuelType typeFor(Action action) {
        return switch (action.getType()) {
            case CHASE -> DuelType.CHASE_BALL;
            case CARRY -> DuelType.DRIBBLE;
            case PASS -> action.getPassHeight() == PassHeight.AIR ? DuelType.AERIAL : DuelType.RECEIVE_PASS;
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
            if (candidate.isSentOff() || candidate.isInjured()) continue;
            if (action.getType() == ActionType.CHASE && !state.isActiveChaser(candidate)) continue;
            if (state.isBlockedAfterDuel(candidate)) continue;
            // Goalkeepers must stay in their defensive goal area during CHASE.
            // HOME GK defends goal at row 1 — must stay in rows 0-2.0.
            // AWAY GK defends goal at row 7 — must stay in rows 6.0-8.
            // This prevents GKs from roaming too far upfield during loose-ball chases.
            // Bounds must match TacticalIntentEngine.applyGKAnchor().
            if (action.getType() == ActionType.CHASE && "GK".equals(candidate.getRole())) {
                double row = candidate.getPosition().getRow();
                String team = candidate.getTeam();
                if ("HOME".equals(team) && row > 2.0) continue;
                if ("AWAY".equals(team) && row < 6.0) continue;
            }
            // SHOT: allow DEF/MID within 0.3 cells (tight block). The GK is only
            // allowed to contest a SHOT when it is genuinely near its own goal
            // (within ~1.5 cells of the goal line) — a keeper must NOT be dragged
            // upfield to "block" a 30m+ shot, it stays right in front of goal.
            if (action.getType() == ActionType.SHOT) {
                if ("GK".equals(candidate.getRole())) {
                    double upfieldDist = "HOME".equals(candidate.getTeam())
                            ? candidate.getPosition().getRow() - 1.0
                            : 8.0 - candidate.getPosition().getRow();
                    if (upfieldDist > 1.5) continue; // too far from own goal to contest
                } else {
                    double distToAttacker = SimUtils.distance(candidate.getPosition(), attacker.getPosition());
                    if (distToAttacker > 0.3) continue; // outfield too far to challenge
                }
            }
            if (action.getType() == ActionType.AERIAL && "GK".equals(candidate.getRole())) continue;
            double distance = SimUtils.distance(candidate.getPosition(), position);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Player closestActiveChaserToBall() {
        Position ballPos = state.getBall().getPosition();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player chaser : state.getActiveChasers()) {
            if (chaser.isLocked() || chaser.isSentOff() || chaser.isInjured() || state.isBlockedAfterDuel(chaser)) continue;
            // GK positioning constraints: HOME GK must stay near row 1 (rows 0-2.0),
            // AWAY GK must stay near row 7 (rows 6.0-8). Bounds match TacticalIntentEngine.applyGKAnchor().
            if ("GK".equals(chaser.getRole())) {
                double row = chaser.getPosition().getRow();
                String team = chaser.getTeam();
                if ("HOME".equals(team) && row > 2.0) continue;
                if ("AWAY".equals(team) && row < 6.0) continue;
            }
            double distance = SimUtils.distance(chaser.getPosition(), ballPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chaser;
            }
        }
        return best;
    }
}
