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

    public static final int SHOOT_MIN_ROW = 6; // shots only from the last 2 field cells (~14-28m from goal)
    public static final Position GOAL_POSITION = new Position(8.0, 3.5);
    public static final Position GOAL_EXIT_POSITION = new Position(8.5, 3.5);
    public static final Position PENALTY_SPOT_HOME = new Position(7.0, 3.5);
    public static final Position PENALTY_SPOT_AWAY = new Position(2.0, 3.5);
    public static final double POSSESSION_RADIUS = BallMovementEngine.PICKUP_DISTANCE;
    public static final int CHASE_MAX_TICKS = 30;
    public static final int CHASE_NO_PROGRESS_TICKS = 8;
    public static final double CHASE_PROGRESS_EPSILON = MovementEngine.PLAYER_SPEED * 0.25;

    public static Position goalPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_POSITION : new Position(1.0, 3.5);
    }

    public static Position goalExitPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_EXIT_POSITION : new Position(-0.5, 3.5);
    }

    /**
     * True when `receiver` is clearly offside (more than 0.2 cells beyond the
     * second-to-last defender) at the moment `passer` would play the ball.
     * Matches the PASS decision filter so a cross/center never targets an
     * attacker who is obviously offside. Marginal offside (≤ 0.2) stays — the
     * referee / VAR decides that close call at execution (checkOffside).
     */
    private boolean isClearlyOffside(Player passer, Player receiver) {
        boolean home = "HOME".equals(passer.getTeam());
        double passerRow = passer.getPosition().getRow();
        double receiverRow = receiver.getPosition().getRow();
        boolean forward = home ? receiverRow > passerRow : receiverRow < passerRow;
        boolean opponentHalf = home ? receiverRow >= 4.5 : receiverRow <= 4.5;
        if (!forward || !opponentHalf) return false;

        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> opponentRows = new java.util.ArrayList<>();
        for (Player opponent : state.getPlayers()) {
            if (defendingTeam.equals(opponent.getTeam())
                    && !opponent.isSentOff() && !opponent.isInjured()) {
                opponentRows.add(opponent.getPosition().getRow());
            }
        }
        if (opponentRows.size() < 2) return true;
        opponentRows.sort(home ? java.util.Comparator.reverseOrder()
                : java.util.Comparator.naturalOrder());
        double secondLastOpponent = opponentRows.get(1);
        double margin = home ? receiverRow - secondLastOpponent
                : secondLastOpponent - receiverRow;
        return margin > 0.2;
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
                action.getActionId(), type.name(), description, state);
        // Track consecutive carries for the carry penalty
        if (type == ActionType.CARRY && actor != null) {
            actor.incrementConsecutiveCarries();
        }
    }

    public void complete(String description) {
        // Safety: unlock any player locked as a pass/cross/center target
        // who hasn't been explicitly unlocked yet (e.g. when the action is
        // resolved by a duel at the decision phase rather than by a normal
        // arrival via handleInFlightArrival / pickupPass / passFailed).
        // Without this, the receiver can remain locked if the action is
        // completed out-of-band (e.g. free kick awarded for a foul), causing
        // the receiver to freeze when it later gains possession.
        Action pending = state.getAction();
        if (pending != null && pending.getTargetPlayer() != null) {
            pending.getTargetPlayer().setLocked(false);
        }
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
        boolean isKickoff = carrierRow == 4.5 && carrier.getPosition().getColumn() == 4.0;

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
                boolean validRow = home ? (candidateRow < 4.5) : (candidateRow > 4.5);
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
                carrier, carrier.getPosition(), thruTarget, runner, PassLength.THRU, passHeight, 1.35);
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

        // §49.2: thru passes are hit through the defensive line at pace — the
        // engine chooses the power, ExecutionQuality already applied the
        // overspeed penalty against the passer's passing skill.
        double thruSpeed = Math.min(BallMovementEngine.MAX_BALL_SPEED, 1.35);
        action.setPassSpeed(thruSpeed);
        state.getBall().setSpeed(thruSpeed);
        state.getBall().setAirborne(passHeight == PassHeight.AIR);

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
        // User rule: the pass goes INTO SPACE, not at the receiver's exact body.
        // The receiver steps away from their nearest opponent toward the open
        // area during flight, and the passer aims at that spot. With an
        // interception being a read within 1 m (0.07 cell) of the path, a
        // receiver needs only ~2 m (0.14 cell) of separation to be clean —
        // this nudge both creates that gap and gives the passer a target that
        // is not a marked body (fewer loose balls, more controlled reception).
        Position opening = openingTarget(receiver);
        receiver.setLocked(false); // receiver runs onto the pass (was static/locked)
        receiver.setTarget(opening);
        state.incrementPassAttempts(carrier.getTeam());

        Position intendedTarget = opening;
        PassLength passLength = choosePassLength(carrier.getPosition(), intendedTarget);
        PassHeight passHeight = choosePassHeight(carrier.getPosition(), intendedTarget, receiver);
        // §49: the engine chooses how hard to hit the ball; quality then checks
        // the passer can actually handle that speed (ExecutionQuality §49.2).
        double desiredSpeed = switch (passLength) {
            case THRU -> 1.35;
            case LONG -> 1.30;
            case SHORT -> 1.05;
        };
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), intendedTarget, receiver, passLength, passHeight,
                desiredSpeed);
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
        // §49.2: ball speed = the chosen power, capped at 14 m/s (1.5 cells/tick
        // @40 TPM). Ball speed is SET ONCE here; the ball travels A→B on its own
        // after this (§49.3). Faster balls are harder to deflect in the lane
        // collision (see corePrinciples §49.4). ExecutionQuality already applied
        // the overspeed penalty against the passer's passing skill.
        double hitSpeed = Math.min(BallMovementEngine.MAX_BALL_SPEED, desiredSpeed);
        action.setPassSpeed(hitSpeed);
        state.getBall().setSpeed(hitSpeed);
        state.getBall().setAirborne(passHeight == PassHeight.AIR);

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);
        state.setCarrier(null);
        state.incrementActionCount();
    }

    public void executeThruPassDirect(Player runner) {
        executeThruPass(runner);
    }

    /**
     * User rule: a pass aims at the SPACE the receiver moves into, not at the
     * receiver's current body. The opening point is the receiver position
     * nudged away from their nearest opponent by up to 0.5 cells (~7 m),
     * clamped on-pitch; the receiver runs there during the flight (see the
     * pass-flight guard in TacticalIntentEngine, which keeps the opening
     * target until the pass resolves). A pass to a marked body is exactly what
     * generates deflections/interceptions — targeting space avoids that.
     */
    private Position openingTarget(Player receiver) {
        Position rp = receiver.getPosition();
        Player nearestOpp = null;
        double best = Double.MAX_VALUE;
        for (Player opp : state.getPlayers()) {
            if (opp.getTeam().equals(receiver.getTeam())) continue;
            if ("GK".equals(opp.getRole())) continue;
            double d = SimUtils.distance(opp.getPosition(), rp);
            if (d < best) {
                best = d;
                nearestOpp = opp;
            }
        }
        if (nearestOpp == null) return rp;
        double dx = rp.getColumn() - nearestOpp.getPosition().getColumn();
        double dy = rp.getRow() - nearestOpp.getPosition().getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) return rp;
        // Step away from the opponent: half their separation, capped at 0.5 cells.
        double step = Math.min(0.5, Math.max(0.15, len * 0.5));
        double targetRow = SimUtils.clamp(rp.getRow() + dy / len * step, 1.0, 7.0);
        double targetCol = SimUtils.clamp(rp.getColumn() + dx / len * step, 1.0, 6.9);
        return new Position(targetRow, targetCol);
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
        org.example.footballmanager.demo.service.result.GoalSource crossSource =
                state.isCornerActive()
                        ? org.example.footballmanager.demo.service.result.GoalSource.CORNER
                        : org.example.footballmanager.demo.service.result.GoalSource.CROSS;
        state.setGoalSource(crossSource);
        // Find and remember the aerial target so we can verify a goal
        // attributed to this cross is actually scored by the target player.
        state.setAerialTargetId(null);
        Player carrier = state.getCarrier();
        boolean home = "HOME".equals(carrier.getTeam());
        double targetRow = home ? 5.5 + state.getRandom().nextDouble() * 1.0
                : 2.5 - state.getRandom().nextDouble() * 1.0;
        double targetCol = 2.5 + state.getRandom().nextDouble() * 3.0;
        Position intendedTarget = new Position(targetRow, targetCol);

        Player aerialTarget = selectCrossTarget();
        if (aerialTarget == null) { executePass(); return; }
        state.setAerialTargetId(aerialTarget.getId());
        state.setActionCountAtAerial(state.getActionCount());

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
        state.setGoalSource(org.example.footballmanager.demo.service.result.GoalSource.CENTER);
        state.setAerialTargetId(null);
        Player carrier = state.getCarrier();
        boolean home = "HOME".equals(carrier.getTeam());
        Player aerialTarget = selectCenterTarget();
        if (aerialTarget != null) {
            state.setAerialTargetId(aerialTarget.getId());
            state.setActionCountAtAerial(state.getActionCount());
        }

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
            // Never cross/center to a receiver who is clearly offside (same 0.2
            // margin as the PASS decision filter). A winger does not aim a cross
            // at an attacker obviously beyond the offside line — the offside
            // whistle at execution (checkOffside) is only a backstop for the
            // marginal ≤0.2 cases that the referee deems a close call.
            if (isClearlyOffside(carrier, p)) continue;
            double score = p.heightSkill() * 0.40 + p.getSkills().technique() * 0.30
                    + p.getSkills().striker() * 0.20;
            if (score > bestAerialScore) {
                bestAerialScore = score;
                aerialTarget = p;
            }
        }
        // --- User rule: in the final 2 rows, prefer the top-2 box attackers
        // closest to the opponent goal (the "two most dangerous in the box").
        // A real winger delivering from the byline aims at the man nearest the
        // goal, not just the best aerial winner anywhere on the pitch. We pick
        // from the top-2 closest and choose the best aerial among them so a
        // tall striker close to goal beats a small winger further away. ---
        double carrierRow = carrier.getPosition().getRow();
        boolean inFinalRows = home ? (carrierRow >= 6) : (carrierRow <= 2);
        if (inFinalRows && aerialTarget != null) {
            Position goalPos = goalPositionFor(carrier.getTeam());
            List<Player> top2 = new java.util.ArrayList<>();
            for (Player p : boxAttackers) {
                if (p == carrier) continue;
                double pr = p.getPosition().getRow();
                boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
                if (!inBox) continue;
                top2.add(p);
            }
            top2.sort((a, b) -> {
                double ad = SimUtils.distance(a.getPosition(), goalPos);
                double bd = SimUtils.distance(b.getPosition(), goalPos);
                return Double.compare(ad, bd);
            });
            if (top2.size() >= 2) {
                Player a = top2.get(0);
                Player b = top2.get(1);
                double aScore = a.heightSkill() * 0.40 + a.getSkills().technique() * 0.30
                        + a.getSkills().striker() * 0.20;
                double bScore = b.heightSkill() * 0.40 + b.getSkills().technique() * 0.30
                        + b.getSkills().striker() * 0.20;
                return aScore >= bScore ? a : b;
            }
        }
        return aerialTarget;
    }

    /**
     * Selects the same box attacker used by executeCross (best aerial among the
     * box attackers, offside-filtered) so the caller can run the offside check
     * BEFORE the cross is executed — a winger never delivers into an attacker
     * who is clearly beyond the offside line.
     */
    public Player selectCrossTarget() {
        Player carrier = state.getCarrier();
        if (carrier == null) return null;
        boolean home = "HOME".equals(carrier.getTeam());
        // Offside CANNOT occur directly from a corner — exclude the filter there.
        boolean corner = state.isCornerActive();
        List<Player> boxAttackers = selection.nearestTeamTo(carrier, 8);
        Player aerialTarget = null;
        double bestAerialScore = -1;
        for (Player p : boxAttackers) {
            if (p == carrier) continue;
            double pr = p.getPosition().getRow();
            boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
            if (!inBox) continue;
            // Never cross to a receiver who is clearly offside (0.2 margin) —
            // except from a corner, where offside does not apply.
            if (!corner && isClearlyOffside(carrier, p)) continue;
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
        Position carryTarget = computeCarryTarget(carrier);
        // BOUNDARY GUARD: if the computed target equals the carrier's current
        // position (carrier pressed against end line / touchline), do NOT set
        // the target — that creates an infinite loop where checkActionCompletion
        // fires every tick but reDecide() returns false (all options negative),
        // the carrier freezes at the boundary and the viewer shows a 20+ second
        // silent stretch with no log entries. Let the action complete and
        // let the decision engine pick the next option (SHOT / PASS / CARRY
        // with a different column) instead.
        if (SimUtils.distance(carrier.getPosition(), carryTarget) < 0.05) {
            return;
        }
        carrier.setTarget(carryTarget);
        // Track when this CARRY started so checkActionCompletion() can force-complete
        // after MAX_CARRY_TICKS (~3 s). Without this, an isolated carrier on the wing
        // can dribble for 30+ seconds (every tick the re-decide keeps CARRY because
        // pass lanes are closed and SHOT is too far). Per user rule: CARRY must
        // resolve within ~3 s — either the next decision finds a better option
        // (SHOT, PASS, CENTER) or the carrier is forced to release the ball.
        if (carrier.getCarryStartTick() < 0) {
            carrier.setCarryStartTick(state.getMatchTicks());
        }
        start(ActionType.CARRY, "CARRY: " + carrier.getLabel());
        state.getAction().setTargetPosition(carryTarget);
        state.incrementActionCount();
    }

    /**
     * Compute a forward/lateral carry target 3-4 cells ahead of the carrier.
     * Shared by executeCarry (start of a new carry) and the continuous-run
     * re-target in checkActionCompletion, so consecutive CARRY actions never
     * leave the carrier target-less for a tick (which produced a visible
     * ~1-second stop at the end of every carry on the viewer).
     */
    private Position computeCarryTarget(Player carrier) {
        double r = carrier.getPosition().getRow();
        double c = carrier.getPosition().getColumn();
        boolean home = "HOME".equals(carrier.getTeam());
        int dr;
        // Carriers can only move forward or laterally — never backward — to
        // prevent oscillation that would cause carry freezes. This applies to
        // BOTH teams (HOME direction=+1, AWAY direction=-1).
        do { dr = weightedForwardDr(); } while (dr < 0);
        // --- User rule: drive toward goal center when path is open ---
        // When the carrier is in the attacking half and the lane to goal
        // centre is OPEN (no defender within 1 cell of the carrier AND no
        // defender on the path to the goal centre), bias the carry column
        // toward goal centre (3.5) instead of random lateral movement. This
        // prevents wingers running into the corner when the goal is in clear
        // sight — they cut inside toward goal and the SHOT override can then
        // fire when they reach ~16m. The straight-line flank carry is handled
        // separately by executeStraightCarry and is unaffected.
        int dc = 0;
        boolean inAttackingHalf = home ? r >= 4.5 : r <= 4.5;
        // HARD RULE (user): in the final 2.5 rows the carrier MUST drive
        // toward the goal (row + 1) — NEVER a lateral carry. The only
        // exception is the touchline winger carrying up the sideline toward
        // a CROSS entry. A central carrier in row 6.5 / col 3.5 who has
        // a 16m carry-lane to goal centre cannot dribble sideways — the
        // simulation must cut inside toward goal so SHOT can fire.
        boolean inFinalQuarter = home ? r >= 5.5 : r <= 2.5;
        boolean onTouchline = c <= 1.5 || c >= 5.5;
        if (inFinalQuarter && !onTouchline) {
            dr = 1; // FORCED forward in the final 2.5 rows from non-touchline
        }
        Position goalCenter = new Position(home ? 8.0 : 1.0, 3.5);
        // The lane-open bias toward goal-centre was previously gated on
        // `isCarryLaneOpen` returning true, which requires NO opponent within
        // 1 cell of the carrier. With a defender close (e.g. col 2 winger with
        // marker 0.8 cells away), the bias was off and the winger shuffled
        // sideways at random instead of cutting inside toward goal. Per user
        // rule: any non-touchline carrier (cols 2..5) in the attacking half
        // should bias toward goal-centre so the next decision can fire SHOT
        // from a central position. The strict 1-cell lane-open check remains
        // for the very narrow channel case (< 0.4 cells from centre already).
        boolean inInteriorCols = c >= 2.0 && c <= 5.0;
        boolean laneOpen = inAttackingHalf && isCarryLaneOpen(carrier, goalCenter);
        // Drive toward goal center: always bias dc toward 3.5 when in the
        // attacking half. Random lateral was producing corners like (1.0, 1.0) —
        // far from the goal at (1.0, 3.5) and useless for attacking.
        // This rule applies to both HOME and AWAY; the goal centre for AWAY
        // is (1.0, 3.5) and for HOME is (8.0, 3.5).
        if (inAttackingHalf) {
            if (inInteriorCols && Math.abs(c - 3.5) >= 0.4) {
                dc = (c < 3.5) ? 1 : -1; // cut inside — already toward centre
            } else if (Math.abs(c - 3.5) >= 0.4) {
                dc = (c < 3.5) ? 1 : -1; // winger on flank: step toward centre
            }
            // else: carrier is already at col 3.5 ±0.4 — lateral drift is fine
        } else {
            dc = state.getRandom().nextInt(3) - 1; // neutral / own half: random
        }
        if (dr == 0 && dc == 0) dr = 1;
        // Carry 3-4 cells ahead so the carrier moves CONTINUOUSLY for several
        // seconds before the next decision. Shorter targets cause a visible
        // "jump-pause-jump" effect on the viewer (carrier completes one cell,
        // engine re-decides, starts new carry).
        int carryDistance = 3 + state.getRandom().nextInt(2); // 3 or 4 cells

        // --- User rule (2026-09-12): NEVER park the carrier ON the goal line ---
        // The final-row HARD RULE forces SHOT / delivery once the carrier reaches
        // row 7 (HOME) / row 1 (AWAY). The carry TARGET must therefore stop half a
        // cell BEFORE the line (7.5 / 1.5) — if the target pointed at the line the
        // carrier would dribble INTO the goal mouth before the next decision.
        // Stopping 0.5 cells short lets the shot/delivery decision fire on re-decide
        // while the carrier is still on the pitch. The ball/player may still legally
        // leave the field (restarts) — that is normal football, this only prevents
        // freezing exactly ON the line.
        double maxForwardRowHome = 7.5;
        double minForwardRowAway = 1.5;

        // --- HARD RULE: no carry more than 1 cell ALONG THE SAME ROW ---
        // Side-to-side dribbles look ridiculous on the UI (a winger shuffling
        // from one sideline to the other) and rarely produce a goal. Per user
        // rule: when the carry direction is purely lateral (dr == 0), cap the
        // carry distance at 1 cell. Forward (+/-dr) and diagonal carries
        // (|dr| > 0 AND |dc| > 0) keep the 3-4 cell range.
        if (dr == 0) {
            carryDistance = 1; // 1 cell * dc direction = at most 1.0 cells along row
        }

        double nr = home
                ? SimUtils.clamp(r + dr * carryDistance, 1, maxForwardRowHome)
                : SimUtils.clamp(r - dr * carryDistance, minForwardRowAway, 8);
        double nc = SimUtils.clamp(c + dc * carryDistance, 1, 6);
        return new Position(nr, nc);
    }

    /**
     * Open-lane check for carry direction biasing. True when no non-GK opponent
     * is within 1 cell of the carrier AND no non-GK opponent is on the line
     * segment from carrier to the goal centre (perpendicular distance < 0.3
     * cells ≈ 4 m). Used by executeCarry() to decide whether the carrier should
     * cut inside toward goal centre instead of drifting along the touchline.
     */
    private boolean isCarryLaneOpen(Player carrier, Position goal) {
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (SimUtils.distance(p.getPosition(), carrier.getPosition()) < 1.0) return false;
            if (SimUtils.pointSegmentDistance(p.getPosition(), carrier.getPosition(), goal) < 0.3) {
                return false;
            }
        }
        return true;
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
        // Track when this CARRY started so checkActionCompletion() can force-complete
        // after MAX_CARRY_TICKS (~3 s). Without this, straight carries (executeStraightCarry)
        // would never time out.
        if (carrier.getCarryStartTick() < 0) {
            carrier.setCarryStartTick(state.getMatchTicks());
        }
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
        // Clearances must go FORWARD toward opponent goal, never toward own goal.
        // HOME attacks toward row 8 (opponent goal at 8.0), AWAY attacks toward row 1 (opponent goal at 1.0).
        double targetRow = SimUtils.clamp(current.getRow()
                        + direction * (1.5 + state.getRandom().nextDouble() * 1.5),
                home ? 3.0 : 1.0, home ? 8.0 : 6.0);
        // Allow clearances to go OOB on sidelines — realistic under pressure
        // Range [0.2, 6.8]: ~27% chance of going OOB on either sideline (col <1 or >6)
        Position target = new Position(targetRow, 0.2 + state.getRandom().nextDouble() * 6.6);

        start(ActionType.PASS, "CLEAR: " + carrier.getLabel());
        Action action = state.getAction();
        action.setClearance(true);
        action.setActualTarget(target);
        action.setIntendedTarget(target);
        action.setGoodExecution(true);

        // §49.2: clearances are hammered aimlessly at full power — quest for distance.
        double clearSpeed = Math.min(BallMovementEngine.MAX_BALL_SPEED, 1.40);
        action.setPassSpeed(clearSpeed);
        state.getBall().setSpeed(clearSpeed);
        state.getBall().setAirborne(true);

        state.getBall().setCarrier(null);
        state.getBall().setTarget(target);
        state.setCarrier(null);
        state.incrementActionCount();
    }

    public boolean executeShot() {
        return executeShot(false);
    }

    /**
     * Execute a shot. When the decision layer has determined the goal is EMPTY
     * (no goalkeeper and no defender in the shooting lane) it passes that flag
     * so even a weak finisher aims on frame — the ball then reaches the goal.
     */
    public boolean executeShot(boolean emptyGoalDecided) {
        Player carrier = state.getCarrier();
        Position shotOrigin = carrier.getPosition();
        String shootingTeam = carrier.getTeam();
        Position goalPosition = goalPositionFor(shootingTeam);
        int strikerSkill = (int) Math.round(carrier.getSkills().striker());
        // If the shot was triggered by a CROSS / CENTER action, tag the goal
        // source so any goal that comes out of it is categorised correctly.
        if (state.getAction() != null) {
            var t = state.getAction().getType();
            if (t == org.example.footballmanager.demo.service.model.ActionType.CROSS) {
                state.setGoalSource(org.example.footballmanager.demo.service.result.GoalSource.CROSS);
            } else if (t == org.example.footballmanager.demo.service.model.ActionType.CENTER) {
                state.setGoalSource(org.example.footballmanager.demo.service.result.GoalSource.CENTER);
            } else if (state.getAction().getPassLength()
                    == org.example.footballmanager.demo.service.model.PassLength.THRU) {
                state.setGoalSource(org.example.footballmanager.demo.service.result.GoalSource.THRU_BALL);
            }
        }
        // Calculate pressure: count non-GK opponents within 1.5 cells
        double pressure = 0;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam()) || "GK".equals(p.getRole())) continue;
            // Cooldown check: a defender recovering from a lost duel cannot press or block the shot
            if (state.isBlockedAfterDuel(p)) continue;
            double dist = SimUtils.distance(carrier.getPosition(), p.getPosition());
            if (dist < 1.5) {
                pressure += (1.5 - dist) / 1.5;
            }
        }
        pressure = Math.min(pressure, 1.0) * 50.0; // scale 0..1 → 0..50

        Player goalkeeper = selection.anyGoalkeeper("HOME".equals(shootingTeam) ? "AWAY" : "HOME");
        boolean emptyGoal = goalkeeper == null;
        if (!emptyGoal && goalkeeper != null) {
            // Goal is "empty" when the GK is either far from the goal centre OR
            // clearly OFF the shot lane (perpendicular distance > ~1.6 cells
            // from the shooter -> goal line). A GK within 2 cells but on the
            // wrong post is NOT covering the shot — the far-post aim improvement
            // would beat them anyway, but we treat it as an open goal here so
            // the finisher picks the shot without hesitation.
            double gkDistToGoal = SimUtils.distance(goalkeeper.getPosition(), goalPosition);
            if (gkDistToGoal > 2.0) {
                emptyGoal = true;
            } else {
                // GK is within 2 cells of goal — check if they're on the shot lane.
                double dx = goalPosition.getColumn() - shotOrigin.getColumn();
                double dy = goalPosition.getRow() - shotOrigin.getRow();
                double len = Math.hypot(dx, dy);
                if (len > 1e-6) {
                    double t = ((goalkeeper.getPosition().getColumn() - shotOrigin.getColumn()) * dx
                            + (goalkeeper.getPosition().getRow() - shotOrigin.getRow()) * dy) / (len * len);
                    double projCol = shotOrigin.getColumn() + t * dx;
                    double projRow = shotOrigin.getRow() + t * dy;
                    double perpDist = SimUtils.distance(goalkeeper.getPosition(),
                            new Position(projRow, projCol));
                    // GK "off lane" if perpendicular distance > 1.2 cells (they're
                    // glued to the post, not the centre of the shot lane).
                    if (perpDist > 1.2) {
                        emptyGoal = true;
                    }
                }
            }
        }
        if (emptyGoalDecided) {
            // The playmaker already ruled the goal totally open (no GK, no
            // defenders on frame); force the shot onto target regardless.
            emptyGoal = true;
        }
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

        // NOTE: no pre-shot probabilistic "block" here any more. A shot is
        // blocked ONLY when the ball physically travels and strikes a defender
        // standing on the shot line (see MatchSimulator.resolveShotBlock).
        // This keeps every SHOT_BLOCKED verifiable: shooter fires -> ball
        // moves -> blocker on the line intercepts it.

        ExecutionQuality.ShotResult result = executionQuality.evaluateShot(
                goalPosition, strikerSkill, pressure, shotOrigin, goalkeeper, 1.40);
        if (emptyGoal) {
            // Truly empty goal (keeper > 2 cells from goal): force the shot on frame
            // with effectively no save chance (gkInLane ~ 0.05).
            result = new ExecutionQuality.ShotResult(strikerSkill, goalPosition, true,
                    Math.max(0.5, strikerSkill / 20.0), 0.05, 1.0);
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
        action.setGkInLane(result.gkInLane());
        action.setAngleFactor(result.angleFactor());
        // Realistic struck-ball speed (§49.2): the engine hits at full power, capped
        // at 14 m/s (1.5 cells/tick @ 40 TPM). This makes a box shot (1-2 cells)
        // fly over several ticks, so a defender standing exactly on the shot
        // line has the geometry to physically intercept it
        // (MatchSimulator.resolveShotBlock). ExecutionQuality already applied
        // the overspeed penalty against the striker skill, so an average
        // finisher smashing the ball at 1.4 wavers off-frame far more often
        // than a skill-20 marksman would.
        double shotSpeed = Math.min(BallMovementEngine.MAX_BALL_SPEED, 1.40);
        action.setPassSpeed(shotSpeed);
        state.getBall().setSpeed(shotSpeed);
        state.getBall().setAirborne(true);

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
        // Check before receiver distance — a receiver standing near the goal
        // should NOT be able to "receive" a ball already past the line.
        Position ballPos = state.getBall().getPosition();
        if (ballPos.getRow() > 8.0 || ballPos.getRow() < 1.0
                || ballPos.getColumn() < 1.0 || ballPos.getColumn() > 7.0) {
            passFailed();
            return false;
        }
        // For THRU passes, the receiver is running onto the ball and may not
        // reach the exact target coordinate. Use the THRU success threshold
        // (2.0 cells) as the pickup tolerance so a running receiver can collect
        // the ball in stride. For regular passes the receiver was locked to
        // the exact target, but the per-tick tactical refresh moves them during
        // flight — give the intended receiver a grace radius (~0.9 cells) so a
        // pass landing within a body-length surfaces to a controlled reception
        // instead of spawning a loose-ball chase. Generic pickup stays at
        // PICKUP_DISTANCE for all non-intended outcomes.
        double pickupDistance = (action.getPassLength() == PassLength.THRU)
                ? ExecutionQuality.THRU_SUCCESS_THRESHOLD
                : Math.max(BallMovementEngine.PICKUP_DISTANCE, 0.9);
        if (SimUtils.distance(receiver.getPosition(), state.getBall().getPosition())
                > pickupDistance) {
            passFailed();
            return false;
        }
        receiver.setLocked(false);
        Position snapPos = state.getBall().getPosition();
        if ("GK".equals(receiver.getRole())) {
            snapPos = new Position(
                    GoalkeeperMovementEngine.clampGkToZone(receiver, snapPos.getRow()),
                    snapPos.getColumn());
        }
        receiver.setPosition(snapPos);
        receiver.setTarget(null);
        state.getBall().setCarrier(receiver);
        state.setCarrier(receiver);
        state.getBall().setTarget(null);
        receiver.resetConsecutiveCarries();
        receiver.setCarryStartTick(-100);
        state.setLastTouchTeam(receiver.getTeam());  // track for OOB restart determination
        // --- Aerial chain tracking ---
        // When a CENTER / CROSS receiver picks up the ball, mark the pickup
        // moment (actionCountAtAerial) so handleShotArrival can distinguish:
        // - "goal scored in the same action as aerial delivery" → keep CENTER
        // - "goal scored after an extra carry/pass" → reset to OPEN_PLAY
        // Only the original aerial target's pickup is tracked. If a different
        // player receives the ball, the aerial chain is broken and we clear
        // the tracking fields; the goalSource stays as-is (handleShotArrival
        // will reset it based on scorer vs aerial target).
        if (state.getAerialTargetId() != null
                && state.getAerialTargetId().equals(receiver.getId())) {
            state.setActionCountAtAerial(state.getActionCount());
        } else {
            state.setAerialTargetId(null);
            state.setActionCountAtAerial(0);
        }
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
        winner.setCarryStartTick(-100);
        // Reset the previous carrier's carryStartTick as well (duel loser).
        Player prevCarrier = state.getCarrier();
        if (prevCarrier != null && prevCarrier != winner) {
            prevCarrier.setCarryStartTick(-100);
        }
        state.setActionDelayTicks(0);
        state.setLastTouchTeam(winner.getTeam());  // track for OOB restart determination
        // Clear the aerial chain when possession changes to a new carrier.
        state.setAerialTargetId(null);
        state.setActionCountAtAerial(0);
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
        goalkeeper.setLastSaveTick(state.getMatchTicks());
        state.getBall().setCarrier(null);
        Position gkPos = goalkeeper.getPosition();
        state.getBall().setPosition(gkPos);

        boolean corner = state.getRandom().nextInt(10) < 9; // 85% of saves → corner
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
                "SHOT saved by " + goalkeeper.getLabel(), state);
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
        // HOME shoots toward row 8 (AWAY goal) → ball goes to row 8.5 (past the line)
        // AWAY shoots toward row 1 (HOME goal) → ball goes to row -0.5 (past the line)
        // Wide misses: column also goes past the sideline (col -0.5 or 8.5)
        Position logicalGoal = action.getLogicalGoalPosition();
        if (logicalGoal != null) {
            if (logicalGoal.getRow() == 8.0) {
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
                "SHOT | MISS — ball out of play", state);
        complete("SHOT | MISS");
    }

    public void goalScored() {
        goalScored(state.getAction() != null ? state.getAction().getActingPlayer() : state.getCarrier(), "GOAL");
    }

    public void goalScored(String eventType) {
        goalScored(state.getAction() != null ? state.getAction().getActingPlayer() : state.getCarrier(), eventType);
    }

    public void goalScored(Player scorer, String eventType) {
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
                null, eventType,
                eventType + " for " + scorer.getTeam() + "! " + scorer.getLabel()
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
                "PASS -> OUT OF BOUNDS", state);
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
                // CARRY TIMEOUT: force-complete the action after MAX_CARRY_TICKS
                // (~3 s) so an isolated carrier cannot dribble for half a minute.
                // Per user rule: CARRY must resolve quickly — either via re-decide
                // (SHOT / PASS / CENTER) or via this hard ceiling.
                final int MAX_CARRY_TICKS = 36; // 36 ticks @ ~120/min ≈ 3.0 s
                boolean carryTimedOut = carrier != null
                        && carrier.getCarryStartTick() >= 0
                        && (state.getMatchTicks() - carrier.getCarryStartTick()) >= MAX_CARRY_TICKS;
                if (carryTimedOut) {
                    carrier.setCarryStartTick(-100);
                    carrier.setTarget(null);
                    state.setActionDelayTicks(0);
                    recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                            state.getAction().getActionId(), "CARRY_TIMED_OUT",
                            "CARRY: " + carrier.getLabel() + " timed out at ("
                                    + String.format(Locale.US, "%.2f", carrier.getPosition().getRow())
                                    + "," + String.format(Locale.US, "%.2f", carrier.getPosition().getColumn())
                                    + ") — releasing ball",
                            state);
                    complete("CARRY: " + carrier.getLabel() + " | timed out");
                    return;
                }
                // Continuous-run re-target: the moment the carrier arrives
                // within PLAYER_SPEED*2 of the current target, point the SAME
                // action at a fresh forward target so the run never stalls for
                // a tick (eliminates the visible ~1s stop between consecutive
                // carries). The per-tick re-decide in MatchSimulator still runs
                // and can override with SHOT/PASS the instant a better option
                // appears. Boundary cases (new target < PLAYER_SPEED*2 away,
                // e.g. carrier pressed against the end line) fall through to a
                // normal completion so the next decision resolves the situation.
                if (carrier != null) {
                    Position target = carrier.getTarget();
                    boolean nearTarget = target == null
                            || SimUtils.distance(carrier.getPosition(), target)
                               < MovementEngine.PLAYER_SPEED * 2;
                    if (nearTarget && !"GK".equals(carrier.getRole())) {
                        Position next = computeCarryTarget(carrier);
                        if (SimUtils.distance(carrier.getPosition(), next)
                                >= MovementEngine.PLAYER_SPEED * 2) {
                            carrier.setTarget(next);
                            state.getAction().setTargetPosition(next);
                            recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                                    state.getAction().getActionId(), "CARRY_CONTINUED",
                                    "CARRY: " + carrier.getLabel() + " continues to ("
                                            + String.format(java.util.Locale.US, "%.2f", next.getRow())
                                            + "," + String.format(java.util.Locale.US, "%.2f", next.getColumn()) + ")",
                                    state);
                            return;
                        }
                    }
                }
                boolean targetReached = carrier.getTarget() == null
                        || (carrier.getTarget() != null
                            && SimUtils.distance(carrier.getPosition(), carrier.getTarget())
                               < MovementEngine.PLAYER_SPEED * 2);
                if (targetReached && state.getBall().getCarrier() == carrier) {
                    carrier.setCarryStartTick(-100);
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
        Position chasePos = state.getBall().getPosition();
        if ("GK".equals(winner.getRole())) {
            chasePos = new Position(
                    GoalkeeperMovementEngine.clampGkToZone(winner, chasePos.getRow()),
                    chasePos.getColumn());
        }
        winner.setPosition(chasePos);
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
        // HOME defends goal at row 1.0 → defensive row ≤ 1.0.
        // AWAY defends goal at row 8.0 → defensive row ≥ 7.0 (last row cell center 7.5).
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

    public PenaltyResult executePenaltyKick(Player kicker, Player goalkeeper) {
        Ball ball = state.getBall();
        String kickingTeam = kicker.getTeam();
        boolean home = "HOME".equals(kickingTeam);
        Position penaltySpot = home ? PENALTY_SPOT_HOME : PENALTY_SPOT_AWAY;

        ball.setPosition(penaltySpot);
        ball.setCarrier(kicker);
        state.setCarrier(kicker);
        state.setPhase(MatchPhase.PENALTY);

        int techniqueSkill = Math.max(1, Math.min(20, (int) Math.round(kicker.getSkills().technique())));
        int strikerSkill = Math.max(1, Math.min(20, (int) Math.round(kicker.getSkills().striker())));
        int penaltySkill = (techniqueSkill + strikerSkill) / 2 + 2; // composure bonus

        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                null, "PENALTY_KICK",
                "PENALTY by " + kicker.getLabel() + " (skill " + penaltySkill + ")");

        // Duel between striker and keeper: striker has home advantage (~75% goal rate).
        // Base goal prob = 0.75. Small adjustment by skill difference (keeper/kicker).
        // Keeper skill advantage → fewer goals; striker skill advantage → more goals.
        double gkSkill = goalkeeper.getSkills().keeper();
        double strikerAdvantage = (penaltySkill - gkSkill) / 40.0; // ±0.5 max adjustment
        double goalProb = Math.max(0.60, Math.min(0.92, 0.75 + strikerAdvantage));
        boolean goal = state.getRandom().nextDouble() < goalProb;

        if (goal) {
            goalScored(kicker, "PENALTY_GOAL");
            complete("PENALTY_GOAL by " + kicker.getLabel());
            return PenaltyResult.GOAL;
        }

        // Not a goal: GK saves (~20-25% of penalties) vs striker misses (~5-10%).
        // GK skill drives the save probability.
        double saveProb = Math.min(0.40, 0.20 + (gkSkill - penaltySkill) / 60.0);
        boolean saved = state.getRandom().nextDouble() < saveProb;

        if (saved) {
            state.incrementShotsOnTarget(kickingTeam);
            double gkDive = (state.getRandom().nextDouble() - 0.5) * 2.0;
            Position gkFinalPos = new Position(
                    goalkeeper.getPosition().getRow(),
                    SimUtils.clamp(goalkeeper.getPosition().getColumn() + gkDive, 1, 6));
            ball.setPosition(gkFinalPos);
            ball.setCarrier(goalkeeper);
            state.setCarrier(goalkeeper);
            recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                    null, "PENALTY_SAVED",
                    "PENALTY SAVED by " + goalkeeper.getLabel());
            complete("PENALTY_SAVED by " + goalkeeper.getLabel());
            return PenaltyResult.SAVED;
        }

        // Miss: striker hits the post or skies it (rare in real football ~5-8%).
        ball.setPosition(new Position(4.5, 4.0));
        ball.setCarrier(null);
        ball.setTarget(null);
        state.setCarrier(null);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                null, "PENALTY_MISS",
                "PENALTY MISSED by " + kicker.getLabel());
        complete("PENALTY_MISS by " + kicker.getLabel());
        return PenaltyResult.MISSED;
    }
}
