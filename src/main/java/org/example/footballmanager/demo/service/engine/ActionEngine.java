package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;

import java.util.List;
import java.util.Locale;

/**
 * Action lifecycle — start/complete/execute for all action types.
 * Simplified headless version: no visual rebound sequences, just state transitions.
 */
public class ActionEngine {

    public static final int SHOOT_MIN_ROW = 6; // shots only in last 2 rows (~14-28m from goal)
    public static final Position GOAL_POSITION = new Position(7, 3.5);
    public static final Position GOAL_EXIT_POSITION = new Position(8, 3.5);
    public static final Position PENALTY_SPOT_HOME = new Position(6, 3.5);
    public static final Position PENALTY_SPOT_AWAY = new Position(2, 3.5);
    public static final double POSSESSION_RADIUS = BallMovementEngine.PICKUP_DISTANCE;
    public static final int CHASE_MAX_TICKS = 60;
    public static final int CHASE_NO_PROGRESS_TICKS = 15;
    public static final double CHASE_PROGRESS_EPSILON = MovementEngine.PLAYER_SPEED * 0.25;

    public static Position goalPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_POSITION : new Position(1, 3.5);
    }

    public static Position goalExitPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_EXIT_POSITION : new Position(0, 3.5);
    }

    private final MatchState state;
    private final PlayerSelectionEngine selection;
    private final ExecutionQuality executionQuality;
    private final MatchRecorder recorder;

    public ActionEngine(MatchState state, PlayerSelectionEngine selection,
                        ExecutionQuality executionQuality, MatchRecorder recorder) {
        this.state = state;
        this.selection = selection;
        this.executionQuality = executionQuality;
        this.recorder = recorder;
    }

    public void start(ActionType type, String description) {
        if (type != ActionType.CHASE) state.clearActiveChasers();
        Player actor = state.getCarrier();
        if (actor == null && type == ActionType.CHASE) {
            actor = selection.closestEligibleActiveChaser(state.getBall().getPosition());
        }
        if (actor != null && type != ActionType.CHASE) {
            // The actor is the last-touch team until the ball is actually
            // received, intercepted or deflected by someone else.
            state.setLastTouchTeam(actor.getTeam());
        }
        Action action = new Action(type, actor);
        action.setActionId(state.nextActionId());
        action.setStartTick(state.getSimulationTick());
        state.setAction(action);
        state.setStatus(description);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                action.getActionId(), type.name(), description);
        // Track consecutive carries for the carry penalty
        if (type == ActionType.CARRY && actor != null) {
            actor.incrementConsecutiveCarries();
        }
    }

    public void complete(String description) {
        state.clearActiveChasers();
        state.setAction(null);
        state.setRoundComplete(true);
        state.setRoundEndBallPosition(state.getBall().getPosition());
        for (Player p : state.getPlayers()) {
            state.setRoundEndPosition(p, p.getPosition());
        }
    }

    public void completeBlockedChase() {
        Action action = state.getAction();
        if (action == null || action.getType() != ActionType.CHASE) return;
        complete("CHASE: " + action.getActingPlayer().getLabel() + " blocked, switching chaser");
    }

    public void executePass() {
        executePass(null);
    }

    /**
     * Execute a pass to the given receiver (decision-layer chosen).
     * If receiver is null, falls back to internal nearest-teammate selection.
     * NOTE: This method must NOT decide to convert a PASS into a THRU pass —
     * that is the decision engine's job (corePrinciples §5: Decision Engine).
     */
    public void executePass(Player preferredReceiver) {
        Player carrier = state.getCarrier();

        if (preferredReceiver != null && preferredReceiver != carrier
                && !isOwnGoalkeeperOrDefensiveRow(preferredReceiver, carrier.getTeam())) {
            executePassTo(preferredReceiver);
            return;
        }

        int candidateCount = "GK".equals(carrier.getRole()) ? 5 : 6;
        List<Player> nearest = selection.nearestTeamTo(carrier, candidateCount);
        if (nearest.isEmpty()) { executeClearance(); return; }

        double carrierRow = carrier.getPosition().getRow();
        boolean home = "HOME".equals(carrier.getTeam());
        boolean inFinalRows = home ? (carrierRow >= 6) : (carrierRow <= 2);
        boolean isKickoff = carrierRow == 4 && carrier.getPosition().getColumn() == 3.5;

        List<Player> eligibleReceivers = new java.util.ArrayList<>();
        for (Player candidate : nearest) {
            if (isOwnGoalkeeperOrDefensiveRow(candidate, carrier.getTeam())) continue;
            if (inFinalRows) {
                double candidateRow = candidate.getPosition().getRow();
                boolean validRow = home ? (candidateRow >= carrierRow) : (candidateRow <= carrierRow);
                if (!validRow) continue;
            }
            if (isKickoff) {
                double candidateRow = candidate.getPosition().getRow();
                boolean validRow = home ? (candidateRow < 4) : (candidateRow > 4);
                if (!validRow) continue;
            }
            eligibleReceivers.add(candidate);
        }

        if (eligibleReceivers.isEmpty()) { executeClearance(); return; }
        Player receiver = eligibleReceivers.get(state.getRandom().nextInt(eligibleReceivers.size()));
        executePassTo(receiver);
    }

    public void executeThruPass(Player runner) {
        Player carrier = state.getCarrier();
        boolean home = "HOME".equals(carrier.getTeam());
        double forwardRow = home ? 1.0 : -1.0;
        double ahead = 1.0 + state.getRandom().nextDouble();
        double targetRow = SimUtils.clamp(runner.getPosition().getRow() + forwardRow * ahead, 1, 7);
        double colJitter = (state.getRandom().nextDouble() * 2 - 1) * 0.5;
        double targetCol = SimUtils.clamp(runner.getPosition().getColumn() + colJitter, 1, 6);
        Position thruTarget = new Position(targetRow, targetCol);

        state.incrementPassAttempts(carrier.getTeam());

        PassHeight passHeight = choosePassHeight(carrier.getPosition(), thruTarget, runner);
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), thruTarget, runner, PassLength.THRU, passHeight);
        // THRU passes: low OOB chance — they go into space behind defense
        double deviation = SimUtils.distance(result.actualTarget(), thruTarget);
        double thruOobChance = deviation > 0.5 ? (deviation - 0.5) * 0.08 : 0.0;
        boolean goesOut = state.getRandom().nextDouble() < thruOobChance;
        Position flightTarget;
        if (goesOut) {
            flightTarget = outOfBoundsEndpoint(result.actualTarget());
        } else {
            flightTarget = result.actualTarget();
        }
        boolean received = !goesOut;

        // Runner moves toward the ball's flight target (runs onto the pass) instead
        // of sitting frozen at the original position. corePrinciples §8 (Movement):
        // physical movement is the MovementEngine's job, but setting the tactical
        // target for the runner is an action-setup concern.
        runner.setTarget(flightTarget);

        start(ActionType.PASS, "THRU: " + carrier.getLabel() + " -> " + runner.getLabel());

        Action action = state.getAction();
        action.setTargetPlayer(runner);
        action.setTargetPosition(thruTarget);
        action.setExecutionOrigin(carrier.getPosition());
        action.setSkill(result.skill());
        action.setIntendedTarget(thruTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);
        action.setPassLength(PassLength.THRU);
        action.setPassHeight(passHeight);

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);
        state.setCarrier(null);
        state.incrementActionCount();
    }

    public void executePassTo(Player receiver) {
        Player carrier = state.getCarrier();
        if (isOwnGoalkeeperOrDefensiveRow(receiver, carrier.getTeam())) {
            executeClearance();
            return;
        }
        receiver.setLocked(true);
        state.incrementPassAttempts(carrier.getTeam());

        Position intendedTarget = receiver.getPosition();
        PassLength passLength = choosePassLength(carrier.getPosition(), intendedTarget);
        PassHeight passHeight = choosePassHeight(carrier.getPosition(), intendedTarget, receiver);
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), intendedTarget, receiver, passLength, passHeight);
        boolean received = result.received();
        Position flightTarget;
        if (received) {
            flightTarget = intendedTarget;
        } else {
            // Ball misses — goes to deviated target (clamped inside pitch at 1-7 / 1-6).
            // If the deviated target is near a boundary edge, the ball trajectory
            // might clip the sideline — detect this by checking proximity to edge.
            flightTarget = result.actualTarget();
        }

        start(ActionType.PASS, "PASS: " + carrier.getLabel() + " -> " + receiver.getLabel());

        Action action = state.getAction();
        //System.err.println("DEBUG executePassTo: action type=" + action.getType() + " isShotInFlight=" + action.isShotInFlight() + " isPassInFlight=" + action.isPassInFlight());
        action.setTargetPlayer(receiver);
        action.setTargetPosition(intendedTarget);
        action.setExecutionOrigin(carrier.getPosition());
        action.setSkill(result.skill());
        action.setIntendedTarget(intendedTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);
        action.setPassLength(passLength);
        action.setPassHeight(passHeight);
        // Pass speed: high passing skill = fast ball (1.0 to 3.0 cells/tick)
        // Fast balls are harder to intercept/deflect; slow balls are easier
        double passSkill = carrier.getSkills().passing();
        double passSpeed = 1.0 + (passSkill / 20.0) * 2.0; // skill 1→1.1, skill 20→3.0
        action.setPassSpeed(passSpeed);

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);
        state.setCarrier(null);
        state.incrementActionCount();
    }

    public void executeThruPassDirect(Player runner) {
        executeThruPass(runner);
    }

    private PassLength choosePassLength(Position from, Position to) {
        double dist = SimUtils.distance(from, to);
        if (dist <= 5) return PassLength.SHORT;
        return PassLength.LONG;
    }

    private PassHeight choosePassHeight(Position from, Position to, Player receiver) {
        double dist = SimUtils.distance(from, to);
        Player carrier = state.getCarrier();
        // Skill-based air pass selection: better technique/passing players attempt air passes
        // even at short distance. ~10-15% of passes should be air in real football.
        double skillFactor = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 40.0;
        // Base chance: 3% for short passes, scales up with distance and skill
        double airChance = 0.03 + skillFactor * 0.10 + dist * 0.015;
        // Long passes are more likely to be air
        if (dist > 5) airChance += 0.08;
        // Cap at 15% — most passes should be ground (85%+)
        airChance = Math.min(0.15, airChance);
        return state.getRandom().nextDouble() < airChance ? PassHeight.AIR : PassHeight.GROUND;
    }

    public void executeCross() {
        Player carrier = state.getCarrier();
        boolean home = "HOME".equals(carrier.getTeam());
        double targetRow = home ? 5.5 + state.getRandom().nextDouble() * 1.0
                : 2.5 - state.getRandom().nextDouble() * 1.0;
        double targetCol = 2.5 + state.getRandom().nextDouble() * 3.0;
        Position intendedTarget = new Position(targetRow, targetCol);

        List<Player> boxAttackers = selection.nearestTeamTo(carrier, 8);
        Player aerialTarget = null;
        double bestAerialScore = -1;
        for (Player p : boxAttackers) {
            if (p == carrier) continue;
            double pr = p.getPosition().getRow();
            boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
            if (!inBox) continue;
            double score = p.heightSkill() * 0.40 + p.getSkills().technique() * 0.30 + p.getSkills().striker() * 0.20;
            if (score > bestAerialScore) { bestAerialScore = score; aerialTarget = p; }
        }

        if (aerialTarget == null) { executePass(); return; }

        aerialTarget.setLocked(true);
        state.incrementPassAttempts(carrier.getTeam());

        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), intendedTarget, aerialTarget);
        boolean received = result.received();

        // Cross-out: crosses have higher OOB chance — they're aimed at the box from the wing
        // ~30% of bad crosses go out (real football: crosses from wing often miss)
        double deviation = SimUtils.distance(result.actualTarget(), intendedTarget);
        double sidelineDist = Math.min(intendedTarget.getColumn() - 1, 6 - intendedTarget.getColumn());
        double crossOobChance = received ? 0.0 : 0.15 + deviation * 0.10 + (1.0 - Math.min(1.0, sidelineDist)) * 0.20;
        boolean crossGoesOut = state.getRandom().nextDouble() < crossOobChance;

        Position flightTarget;
        if (crossGoesOut) {
            // Cross goes out over the end line → corner for the attacking team
            // Project ball onto the end line (not the nearest boundary)
            Position actualPos = result.actualTarget();
            double endRow = home ? 7.5 : 0.5;
            double endCol = SimUtils.clamp(actualPos.getColumn(), 1, 6);
            flightTarget = new Position(endRow, endCol);
            received = false;
        } else if (received) {
            flightTarget = intendedTarget;
        } else {
            flightTarget = intendedTarget; // loose near box
        }

        start(ActionType.CROSS, "CROSS: " + carrier.getLabel() + " -> " + aerialTarget.getLabel());

        Action action = state.getAction();
        action.setTargetPlayer(aerialTarget);
        action.setTargetPosition(intendedTarget);
        action.setSkill(result.skill());
        action.setIntendedTarget(intendedTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);
        state.setCarrier(null);
        state.incrementActionCount();
    }

    public void executeCenter() {
        Player carrier = state.getCarrier();
        boolean home = "HOME".equals(carrier.getTeam());
        Player aerialTarget = selectCenterTarget();

        if (aerialTarget == null) { executePass(); return; }

        aerialTarget.setLocked(true);
        state.incrementPassAttempts(carrier.getTeam());

        Position intendedTarget = aerialTarget.getPosition();
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), intendedTarget, aerialTarget);
        boolean received = result.received();

        // Center-out: similar OOB probability to crosses
        double deviation = SimUtils.distance(result.actualTarget(), intendedTarget);
        double sidelineDist = Math.min(intendedTarget.getColumn() - 1, 6 - intendedTarget.getColumn());
        double centerOobChance = received ? 0.0 : 0.06 + deviation * 0.05 + (1.0 - Math.min(1.0, sidelineDist)) * 0.10;
        boolean centerGoesOut = state.getRandom().nextDouble() < centerOobChance;

        Position flightTarget;
        if (centerGoesOut) {
            // Center goes out over the end line → corner
            Position actualPos = result.actualTarget();
            double endRow = home ? 7.5 : 0.5;
            double endCol = SimUtils.clamp(actualPos.getColumn(), 1, 6);
            flightTarget = new Position(endRow, endCol);
            received = false;
        } else if (received) {
            flightTarget = intendedTarget;
        } else {
            flightTarget = intendedTarget; // loose near box
        }

        start(ActionType.CENTER, "CENTER: " + carrier.getLabel() + " -> " + aerialTarget.getLabel());

        Action action = state.getAction();
        action.setTargetPlayer(aerialTarget);
        action.setTargetPosition(intendedTarget);
        action.setSkill(result.skill());
        action.setIntendedTarget(intendedTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);
        state.setCarrier(null);
        state.incrementActionCount();
    }

    /** Selects the same receiver used by executeCenter, for rules checks before execution. */
    public Player selectCenterTarget() {
        Player carrier = state.getCarrier();
        if (carrier == null) return null;
        boolean home = "HOME".equals(carrier.getTeam());
        List<Player> boxAttackers = selection.nearestTeamTo(carrier, 8);
        Player aerialTarget = null;
        double bestAerialScore = -1;
        for (Player p : boxAttackers) {
            if (p == carrier) continue;
            double pr = p.getPosition().getRow();
            boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
            if (!inBox) continue;
            double score = p.heightSkill() * 0.40 + p.getSkills().technique() * 0.30
                    + p.getSkills().striker() * 0.20;
            if (score > bestAerialScore) {
                bestAerialScore = score;
                aerialTarget = p;
            }
        }
        return aerialTarget;
    }

    private Position outOfBoundsEndpoint(Position target) {
        if (target.getColumn() < 1) return new Position(SimUtils.clamp(target.getRow(), 1, 7), 0);
        if (target.getColumn() > 6) return new Position(SimUtils.clamp(target.getRow(), 1, 7), 7);
        if (target.getRow() < 1) return new Position(0, target.getColumn());
        if (target.getRow() > 7) return new Position(8, target.getColumn());
        return target;
    }

    private boolean isOutsidePitch(Position position) {
        return position.getRow() < 1 || position.getRow() > 7
                || position.getColumn() < 1 || position.getColumn() > 6;
    }

    public void executeCarry() {
        Player carrier = state.getCarrier();
        if ("GK".equals(carrier.getRole())) { executeClearance(); return; }
        double r = carrier.getPosition().getRow();
        double c = carrier.getPosition().getColumn();
        boolean home = "HOME".equals(carrier.getTeam());
        int dr;
        boolean inFinalRows = home ? (r >= 6) : (r <= 2);
        if (inFinalRows) {
            do { dr = weightedForwardDr(); } while (home ? dr < 0 : dr > 0);
        } else {
            do { dr = weightedForwardDr(); } while (dr < 0);
        }
        int dc = state.getRandom().nextInt(3) - 1;
        if (dr == 0 && dc == 0) dr = 1;
        double direction = home ? 1 : -1;
        double nr = SimUtils.clamp(r + direction * dr, 1, 7);
        double nc = SimUtils.clamp(c + dc, 1, 6);
        Position carryTarget = new Position(nr, nc);
        carrier.setTarget(carryTarget);
        start(ActionType.CARRY, "CARRY: " + carrier.getLabel());
        state.getAction().setTargetPosition(carryTarget);
        state.incrementActionCount();
    }

    /**
     * A straight-line carry used by the open-flank (winger) override: the player
     * drives the ball directly up the touchline, staying in the SAME column so the
     * run hugs the sideline. Used to repeatedly advance along the flank until the
     * last row.
     */
    public void executeStraightCarry() {
        Player carrier = state.getCarrier();
        if ("GK".equals(carrier.getRole())) { executeClearance(); return; }
        double r = carrier.getPosition().getRow();
        double c = carrier.getPosition().getColumn();
        boolean home = "HOME".equals(carrier.getTeam());
        double direction = home ? 1 : -1;
        // Straight forward: keep the exact same column (stay on the touchline).
        double nr = SimUtils.clamp(r + direction * 1, 1, 7);
        double nc = SimUtils.clamp(c, 1, 6);
        Position carryTarget = new Position(nr, nc);
        carrier.setTarget(carryTarget);
        start(ActionType.CARRY, "CARRY (straight): " + carrier.getLabel());
        state.getAction().setTargetPosition(carryTarget);
        state.incrementActionCount();
    }

    public void prepareDribbleBypass(Player defender) {
        Action action = state.getAction();
        Player carrier = state.getCarrier();
        if (action == null || carrier == null || defender == null) return;

        Position current = carrier.getPosition();
        Position finalTarget = action.getTargetPosition() != null
                ? action.getTargetPosition() : carrier.getTarget();
        if (finalTarget == null) return;

        double forwardRow = finalTarget.getRow() - current.getRow();
        double forwardCol = finalTarget.getColumn() - current.getColumn();
        double length = Math.hypot(forwardRow, forwardCol);
        if (length < 1e-9) { forwardRow = 1.0; forwardCol = 0.0; length = 1.0; }
        forwardRow /= length;
        forwardCol /= length;

        double toDefenderRow = defender.getPosition().getRow() - current.getRow();
        double toDefenderCol = defender.getPosition().getColumn() - current.getColumn();
        double sideRow = -forwardCol;
        double sideCol = forwardRow;
        double side = toDefenderRow * sideRow + toDefenderCol * sideCol;
        if (side > 0) { sideRow = -sideRow; sideCol = -sideCol; }

        Position bypass = new Position(
                SimUtils.clamp(defender.getPosition().getRow() + forwardRow * 0.5 + sideRow * 0.42, 1, 7),
                SimUtils.clamp(defender.getPosition().getColumn() + forwardCol * 0.5 + sideCol * 0.42, 1, 6));
        action.setDribbleBypassTarget(bypass);
        carrier.setTarget(bypass);
    }

    private int weightedForwardDr() {
        int roll = state.getRandom().nextInt(100);
        if (roll < 50) return 1;
        if (roll < 75) return 0;
        return -1;
    }

    public void executeClearance() {
        Player carrier = state.getCarrier();
        Position current = carrier.getPosition();
        boolean home = "HOME".equals(carrier.getTeam());
        double direction = home ? 1 : -1;
        // Keep clearances inside the central playing area — avoid direct goal kicks
        // from overhit defensive clearances while still moving the ball upfield.
        double targetRow = SimUtils.clamp(current.getRow()
                        + direction * (1.5 + state.getRandom().nextDouble() * 1.5),
                home ? 1.0 : 2.0, home ? 6.0 : 7.0);
        // Allow clearances to go OOB on sidelines — realistic under pressure
        // Range [0.2, 6.8]: ~27% chance of going OOB on either sideline (col <1 or >6)
        Position target = new Position(targetRow, 0.2 + state.getRandom().nextDouble() * 6.6);

        start(ActionType.PASS, "CLEAR: " + carrier.getLabel());
        Action action = state.getAction();
        action.setClearance(true);
        action.setActualTarget(target);
        action.setIntendedTarget(target);
        action.setGoodExecution(true);

        state.getBall().setCarrier(null);
        state.getBall().setTarget(target);
        state.setCarrier(null);
        state.incrementActionCount();
    }

    public boolean executeShot() {
        Player carrier = state.getCarrier();
        Position shotOrigin = carrier.getPosition();
        String shootingTeam = carrier.getTeam();
        Position goalPosition = goalPositionFor(shootingTeam);
        int strikerSkill = (int) Math.round(carrier.getSkills().striker());
        // Calculate pressure: count non-GK opponents within 1.5 cells
        double pressure = 0;
        Player closestBlocker = null;
        double closestBlockerDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam()) || "GK".equals(p.getRole())) continue;
            double dist = SimUtils.distance(carrier.getPosition(), p.getPosition());
            if (dist < 1.5) {
                pressure += (1.5 - dist) / 1.5;
                if (dist < closestBlockerDist) {
                    closestBlockerDist = dist;
                    closestBlocker = p;
                }
            }
        }
        pressure = Math.min(pressure, 1.0) * 50.0; // scale 0..1 → 0..50

        Player goalkeeper = selection.anyGoalkeeper("HOME".equals(shootingTeam) ? "AWAY" : "HOME");
        boolean emptyGoal = goalkeeper == null
                || SimUtils.distance(goalkeeper.getPosition(), goalPosition) > 2.0;
        if (emptyGoal) {
            // An open goal is not a random target. If no outfield opponent is
            // between the shooter and the goal, the shot must reach the goal.
            for (Player opponent : state.getPlayers()) {
                if (opponent.getTeam().equals(shootingTeam) || "GK".equals(opponent.getRole())
                        || opponent.isSentOff() || opponent.isInjured()) continue;
                boolean goalSide = "HOME".equals(shootingTeam)
                        ? opponent.getPosition().getRow() > shotOrigin.getRow()
                        : opponent.getPosition().getRow() < shotOrigin.getRow();
                boolean inShootingLane = Math.abs(opponent.getPosition().getColumn()
                        - shotOrigin.getColumn()) <= 1.0;
                if (goalSide && inShootingLane) {
                    emptyGoal = false;
                    break;
                }
            }
        }

        // Pre-shot block check: defender within 1.5 cells can block the shot
        // Probability: closer = more likely. At 0.5 cells: ~40%, at 1.5 cells: ~5%
        if (closestBlocker != null) {
            double blockChance = Math.max(0.03, 0.30 * (1.5 - closestBlockerDist) / 1.0);
            if (state.getRandom().nextDouble() < blockChance) {
                // Shot blocked! Defender throws body in front
                state.getBall().setCarrier(null);
                state.getBall().setTarget(null);
                state.setCarrier(null);
                state.getBall().setPosition(shotOrigin);
                start(ActionType.CHASE, "BLOCK: " + closestBlocker.getLabel() + " blocked shot from " + carrier.getLabel());
                state.setActionDelayTicks(0);
                return false;  // signal: shot was blocked, don't continue with shot execution
            }
        }

        ExecutionQuality.ShotResult result = executionQuality.evaluateShot(
                goalPosition, strikerSkill, pressure, shotOrigin, goalkeeper);
        if (emptyGoal) {
            result = new ExecutionQuality.ShotResult(strikerSkill, goalPosition, true,
                    Math.max(0.5, strikerSkill / 20.0));
        }

        Position shotTarget = result.actualTarget();

        start(ActionType.SHOT, "SHOT by " + carrier.getLabel());

        Action action = state.getAction();
        action.setTargetPosition(goalPosition);
        action.setExecutionOrigin(shotOrigin);
        action.setLogicalGoalPosition(goalPosition);
        action.setSkill(result.skill());
        action.setIntendedTarget(goalPosition);
        action.setActualTarget(shotTarget);
        action.setGoodExecution(result.goal());

        state.getBall().setCarrier(null);
        state.getBall().setTarget(shotTarget);
        state.setCarrier(null);
        state.incrementActionCount();
        state.incrementShotCount();
        carrier.setLastShotTick(state.getMatchTicks());
        return true;
    }

    public boolean pickupPass() {
        Action action = state.getAction();
        Player receiver = action.getTargetPlayer();
        if (receiver == null) {
            passFailed();
            return false;
        }
        // If ball is past the goal line (OOB), the pass cannot be received.
        // Check before receiver distance — a receiver standing at row 7.3 should NOT
        // be able to "receive" a ball at row 7.5 (past the line).
        Position ballPos = state.getBall().getPosition();
        if (ballPos.getRow() > 7.0 || ballPos.getRow() < 1.0
                || ballPos.getColumn() < 1.0 || ballPos.getColumn() > 6.0) {
            passFailed();
            return false;
        }
        // For THRU passes, the receiver is running onto the ball and may not
        // reach the exact target coordinate. Use the THRU success threshold
        // (2.0 cells) as the pickup tolerance so a running receiver can collect
        // the ball in stride. For regular passes the receiver was locked to
        // the exact target, so the 0.5 pickup distance remains sufficient.
        double pickupDistance = (action.getPassLength() == PassLength.THRU)
                ? ExecutionQuality.THRU_SUCCESS_THRESHOLD
                : BallMovementEngine.PICKUP_DISTANCE;
        if (SimUtils.distance(receiver.getPosition(), state.getBall().getPosition())
                > pickupDistance) {
            passFailed();
            return false;
        }
        receiver.setLocked(false);
        receiver.setPosition(state.getBall().getPosition());
        receiver.setTarget(null);
        state.getBall().setCarrier(receiver);
        state.setCarrier(receiver);
        state.getBall().setTarget(null);
        receiver.resetConsecutiveCarries();
        state.setLastTouchTeam(receiver.getTeam());  // track for OOB restart determination
        state.setStatus(receiver.getLabel() + " received pass");
        state.setActionDelayTicks(0);
        state.incrementPassCompletions(receiver.getTeam());
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                action.getActionId(), "PASS_COMPLETED",
                "PASS -> " + receiver.getLabel() + " | RECEIVED");
        complete("PASS -> " + receiver.getLabel() + " | RECEIVED");
        return true;
    }

    public void giveBallTo(Player winner, String reason) {
        Action action = state.getAction();
        if (action != null && action.getTargetPlayer() != null) {
            action.getTargetPlayer().setLocked(false);
        }
        state.getBall().setTarget(null);
        state.getBall().setCarrier(winner);
        state.setCarrier(winner);
        winner.setTarget(null);
        winner.resetConsecutiveCarries();
        state.setActionDelayTicks(0);
        state.setLastTouchTeam(winner.getTeam());  // track for OOB restart determination
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                action != null ? action.getActionId() : null,
                "DUEL_WON", winner.getLabel() + " wins | " + reason);
        complete("DUEL: " + winner.getLabel() + " wins | " + reason);
    }

    public void finishAwayClearance() {
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        complete("CLEARANCE -> LOOSE BALL");
    }

    public void shotSaved(Player goalkeeper) {
        Action action = state.getAction();
        state.incrementShotsOnTarget(action.getActingPlayer().getTeam());
        state.getBall().setCarrier(null);
        Position gkPos = goalkeeper.getPosition();
        state.getBall().setPosition(gkPos);

        boolean corner = state.getRandom().nextInt(10) < 3; // 30% of saves → corner
        String defendingTeam = "HOME".equals(action.getActingPlayer().getTeam()) ? "AWAY" : "HOME";

        if (corner) {
            // Corner rebound - trigger corner immediately at the proper corner flag
            // (row 7 for HOME-attacking end, row 1 for AWAY-attacking end)
            action.setSaveType(Action.SaveType.CORNER_REBOUND);
            int cornerRow = "HOME".equals(action.getActingPlayer().getTeam()) ? 7 : 1;
            boolean right = state.getRandom().nextBoolean();
            Position cornerPos = new Position(cornerRow, right ? 6 : 1);
            state.getBall().setPosition(cornerPos);
            state.getBall().setTarget(null); // No target - ball at corner position
            action.setActualTarget(cornerPos);
        } else {
            // Field rebound - ball becomes loose at neutral zone (not in danger zone)
            action.setSaveType(Action.SaveType.FIELD_REBOUND);
            // Place rebound in midfield area to prevent shoot-loop
            int reboundRow = "HOME".equals(action.getActingPlayer().getTeam())
                    ? 3 + state.getRandom().nextInt(3)
                    : 3 + state.getRandom().nextInt(3);
            Position rebound = new Position(reboundRow, 1 + state.getRandom().nextInt(6));
            state.getBall().setPosition(rebound);
            state.getBall().setTarget(null); // No target - ball is loose
            action.setActualTarget(rebound);
        }

        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                action.getActionId(), "SHOT_SAVED",
                "SHOT saved by " + goalkeeper.getLabel());
        state.setCarrier(null);
        complete("SHOT | SAVE: " + goalkeeper.getLabel());
    }

    public void passFailed() {
        Player receiver = state.getAction().getTargetPlayer();
        if (receiver != null) receiver.setLocked(false);
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        if (state.getCarrier() != null) state.getCarrier().setTarget(null);
        state.setCarrier(null);
        String receiverLabel = receiver != null ? receiver.getLabel() : "unknown";
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                state.getAction().getActionId(), "PASS_LOOSE",
                "PASS -> " + receiverLabel + " | LOOSE BALL");
        complete("PASS -> " + receiverLabel + " | LOOSE BALL");
    }

    public void shotMissed() {
        Action action = state.getAction();
        Position missPosition = action.getActualTarget();
        if (missPosition == null) missPosition = state.getBall().getPosition();

        // When a shot misses, push ball PAST the end line so it's clearly out of play.
        // HOME shoots toward row 7 (AWAY goal) → ball goes to row 8.5 (past the line)
        // AWAY shoots toward row 1 (HOME goal) → ball goes to row -0.5 (past the line)
        // Wide misses: column also goes past the sideline (col -0.5 or 8.5)
        Position logicalGoal = action.getLogicalGoalPosition();
        if (logicalGoal != null) {
            if (logicalGoal.getRow() == 7.0) {
                missPosition = new Position(8.5, SimUtils.clamp(missPosition.getColumn(), -0.5, 8.5));
            } else if (logicalGoal.getRow() == 1.0) {
                missPosition = new Position(-0.5, SimUtils.clamp(missPosition.getColumn(), -0.5, 8.5));
            }
        }

        state.getBall().setPosition(missPosition);
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                action.getActionId(), "SHOT_MISSED",
                "SHOT | MISS — ball out of play");
        complete("SHOT | MISS");
    }

    public void goalScored() {
        Player scorer = state.getAction().getActingPlayer();
        state.incrementShotsOnTarget(scorer.getTeam());
        state.recordGoal(scorer);
        if ("HOME".equals(scorer.getTeam())) {
            state.incrementGoalCount();
        } else {
            state.incrementAwayGoalCount();
        }
        state.setKickoffTeam("HOME".equals(scorer.getTeam()) ? "AWAY" : "HOME");
        state.setCelebratingTeam(scorer.getTeam());
        state.setCelebrating(true);
        // Celebration hold: 20 ticks = 30s @ 1.5s/tick — realistic goal
        // celebration duration (was 100 ticks = 150s, far too long).
        state.setCelebrationHoldTicks(20);
        int score = "HOME".equals(scorer.getTeam()) ? state.getGoalCount() : state.getAwayGoalCount();
        state.setStatus("GOAL for " + scorer.getTeam() + "! (" + score + ")");
        // Set ball to goal exit position so the viewer snapshot shows ball behind the goal line
        Position exitPos = goalExitPositionFor(scorer.getTeam());
        state.getBall().setPosition(exitPos);
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                state.getAction().getActionId(), "GOAL",
                "GOAL for " + scorer.getTeam() + "! " + scorer.getLabel()
                        + " (" + score + ")");
        complete("SHOT (GOAL!)");
    }

    public void passOutOfBounds() {
        Player receiver = state.getAction().getTargetPlayer();
        if (receiver != null) receiver.setLocked(false);
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                state.getAction().getActionId(), "BALL_OUT",
                "PASS -> OUT OF BOUNDS");
        complete("PASS -> OUT OF BOUNDS");
    }

    public void checkActionCompletion() {
        if (!state.hasActiveAction()) return;
        switch (state.getAction().getType()) {
            case CHASE -> {
                Position ballPos = state.getBall().getPosition();
                if (hasOpposingChaseContest(ballPos)) return;
                Player winner = selection.closestEligibleActiveChaser(ballPos);
                if (winner != null && SimUtils.distance(winner.getPosition(), ballPos) <= POSSESSION_RADIUS) {
                    completeChasePossession(winner, "within possession radius");
                }
            }
            case CARRY -> {
                Player carrier = state.getCarrier();
                boolean targetReached = carrier.getTarget() == null
                        || (carrier.getTarget() != null
                            && SimUtils.distance(carrier.getPosition(), carrier.getTarget())
                               < MovementEngine.PLAYER_SPEED * 2);
                if (targetReached && state.getBall().getCarrier() == carrier) {
                    carrier.setTarget(null);
                    state.setActionDelayTicks(0);
                    recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                            state.getAction().getActionId(), "CARRY_COMPLETED",
                            "CARRY: " + carrier.getLabel());
                    complete("CARRY: " + carrier.getLabel());
                }
            }
            default -> {}
        }
    }

    private void completeChasePossession(Player winner, String reason) {
        state.getBall().setTarget(null);
        state.getBall().setCarrier(winner);
        state.setCarrier(winner);
        winner.setTarget(null);
        state.setActionDelayTicks(0);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                state.getAction().getActionId(), "CHASE_POSSESSION",
                "CHASE RESOLUTION: " + winner.getLabel() + " | " + reason);
        winner.setPosition(state.getBall().getPosition());
        complete("CHASE: " + winner.getLabel() + " | " + reason);
    }

    private boolean hasOpposingChaseContest(Position ballPos) {
        boolean homeNear = false;
        boolean awayNear = false;
        for (Player chaser : state.getActiveChasers()) {
            if (chaser.isLocked() || chaser.isSentOff() || chaser.isInjured() || state.isBlockedAfterDuel(chaser)) continue;
            if (SimUtils.distance(chaser.getPosition(), ballPos) > POSSESSION_RADIUS) continue;
            if ("HOME".equals(chaser.getTeam())) homeNear = true;
            else awayNear = true;
        }
        return homeNear && awayNear;
    }

    private boolean isOwnGoalkeeperOrDefensiveRow(Player player, String team) {
        if ("GK".equals(player.getRole())) return true;
        return "HOME".equals(team) ? player.getPosition().getRow() <= 1.0
                : player.getPosition().getRow() >= 7.0;
    }

    public void resolveChaseTimeout() {
        Position ballPos = state.getBall().getPosition();
        Player winner = selection.closestEligibleActiveChaser(ballPos);
        if (winner != null) {
            completeChasePossession(winner, "timeout — closest to ball");
        } else {
            complete("CHASE: timeout — no eligible chaser");
        }
    }

    public void resolveChaseNoProgress() {
        Position ballPos = state.getBall().getPosition();
        Player winner = selection.closestEligibleActiveChaser(ballPos);
        if (winner != null) {
            completeChasePossession(winner, "no progress — forced resolution");
        } else {
            complete("CHASE: no progress — no eligible chaser");
        }
    }

    public void executePenaltyKick(Player kicker, Player goalkeeper) {
        Ball ball = state.getBall();
        String kickingTeam = kicker.getTeam();
        boolean home = "HOME".equals(kickingTeam);
        Position penaltySpot = home ? PENALTY_SPOT_HOME : PENALTY_SPOT_AWAY;

        ball.setPosition(penaltySpot);
        ball.setCarrier(kicker);
        state.setCarrier(kicker);
        state.setPhase(MatchPhase.PENALTY);

        int strikerSkill = Math.max(1, Math.min(20, (int) Math.round(kicker.getSkills().striker())));
        int techniqueSkill = Math.max(1, Math.min(20, (int) Math.round(kicker.getSkills().technique())));
        int shotSkill = (int) Math.round(strikerSkill * 0.6 + techniqueSkill * 0.4);

        Position goalTarget = home ? GOAL_POSITION : new Position(1, 3.5);
        ExecutionQuality.ShotResult shotResult = executionQuality.evaluateShot(goalTarget, shotSkill);

        double gkDiveSkill = goalkeeper.getSkills().keeper() / 20.0;
        double gkDive = (state.getRandom().nextDouble() - 0.5) * 2.0 * gkDiveSkill;
        Position gkFinalPos = new Position(
                goalkeeper.getPosition().getRow(),
                goalkeeper.getPosition().getColumn() + gkDive);
        gkFinalPos = new Position(
                SimUtils.clamp(gkFinalPos.getRow(), 1, 7),
                SimUtils.clamp(gkFinalPos.getColumn(), 1, 6));

        boolean saved = false;
        if (SimUtils.distance(shotResult.actualTarget(), gkFinalPos) < 1.2) {
            saved = true;
        }

        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                null, "PENALTY_KICK",
                "PENALTY by " + kicker.getLabel() + " (skill " + shotSkill + ")");

        if (saved) {
            state.incrementShotsOnTarget(kicker.getTeam());
            ball.setPosition(gkFinalPos);
            ball.setCarrier(goalkeeper);
            state.setCarrier(goalkeeper);
            recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                    null, "PENALTY_SAVED",
                    "PENALTY SAVED by " + goalkeeper.getLabel());
            complete("PENALTY_SAVED by " + goalkeeper.getLabel());
        } else if (shotResult.goal()) {
            goalScored();
            complete("PENALTY_GOAL by " + kicker.getLabel());
        } else {
            ball.setPosition(new Position(4, 3.5));
            ball.setCarrier(null);
            ball.setTarget(null);
            state.setCarrier(null);
            recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                    null, "PENALTY_MISS",
                    "PENALTY MISSED by " + kicker.getLabel());
            complete("PENALTY_MISS by " + kicker.getLabel());
        }
    }
}
