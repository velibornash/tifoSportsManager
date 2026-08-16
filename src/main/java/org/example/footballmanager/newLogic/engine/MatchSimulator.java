package org.example.footballmanager.newLogic.engine;

import lombok.Getter;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class MatchSimulator {

    private static final Logger log = LoggerFactory.getLogger(MatchSimulator.class);
    private static final int TICKS_PER_MINUTE = 120;
    private static final Random RNG = new Random();

    // A teammate is "adjacent" to a pass landing zone within this distance (units)
    private static final double REACTION_ADJACENT_DISTANCE = 12.0;
    private static final double COLLISION_MIN_SEPARATION = 1.8;
    private static final double COLLISION_MAX_NUDGE = 0.15;

    private MatchState state;
    private TeamConfig homeConfig, awayConfig;
    private ZonePositionCalculator zones;    private TacticalIntentEngine tacticalIntents;
    private AwarenessEngine awareness;
    private IntentEngine intents;
    private MovementEngine movement;
    private DecisionEngine decisions;
    private BallEngine ballEngine;
    private DuelResolver duels;
    private RulesEngine rules;
    private MoraleSystem morale;
    private SpaceEvaluation space;
    private PredictionSystem prediction;
    private TeamCoordination coordination;
    private FatigueSystem fatigue;
    private OffsideTracker offside;
    @Getter
    private MatchMetrics metrics;

    public ZonePositionCalculator getZones() { return zones; }
    public TacticalIntentEngine getTacticalIntents() { return tacticalIntents; }

    private int minute;
    @Getter private int homeGoals, awayGoals;
    @Getter private int homeShots, awayShots;
    @Getter private int homeShotsOnTarget, awayShotsOnTarget;
    @Getter private int homeFouls, awayFouls;
    @Getter private int homeCorners, awayCorners;
    @Getter private int homeYellowCards, awayYellowCards;
    @Getter private int homeRedCards, awayRedCards;
    @Getter private int homeSuccessfulPasses, awaySuccessfulPasses;
    @Getter private int homeTotalPasses, awayTotalPasses;
    @Getter private int totalStoppageTicks;

    /** Positions at the start of the current tick, used by the no-teleport clamp. */
    private final Map<Long, double[]> tickStartPositions = new HashMap<>();

    public MatchResult simulate(Match match) {
        initializeSystems(match);
        initializeMatchState(match);

        state.addEvent(new MatchStartEvent(0, 0, match.homeTeam().name(), match.awayTeam().name()));
        state.recordTick();

        int firstHalfStoppageTicks = 0;
        int secondHalfStoppageTicks = 0;

        for (minute = 1; minute <= 90; minute++) {
            state.minute = minute;

            for (int tick = 0; tick < TICKS_PER_MINUTE; tick++) {
                simulateTick();

                if (state.stoppage != null) {
                    state.stoppageTicks--;
                    totalStoppageTicks++;
                    if (state.stoppageTicks <= 0) {
                        handleStoppageEnd();
                    }
                }

                state.recordTick();
            }

            if (minute == 45) {
                firstHalfStoppageTicks = totalStoppageTicks;
                int extraPhases = Math.min(200, totalStoppageTicks / 3);
                for (int e = 0; e < extraPhases; e++) {
                    simulateTick();
                    state.recordTick();
                }
            }

            fatigue.updateFatigue(state, minute);
            fatigue.maybeInjury(state, minute, "HOME");
            fatigue.maybeInjury(state, minute, "AWAY");
            fatigue.maybeSubstitution(state, minute, "HOME");
            fatigue.maybeSubstitution(state, minute, "AWAY");
        }

        secondHalfStoppageTicks = totalStoppageTicks - firstHalfStoppageTicks;
        int extraPhases2 = Math.min(300, secondHalfStoppageTicks / 3);
        for (int e = 0; e < extraPhases2; e++) {
            simulateTick();
            state.recordTick();
        }

        state.addEvent(new MatchEndEvent(90, state.tick, homeGoals, awayGoals));
        state.recordTick();

        return buildResult(match);
    }

    public void initializeSystems(Match match) {
        this.homeConfig = TeamConfig.fromTeam(match.homeTeam());
        this.awayConfig = TeamConfig.fromTeam(match.awayTeam());
        this.zones = new ZonePositionCalculator();
        this.tacticalIntents = new TacticalIntentEngine(zones);
        this.awareness = new AwarenessEngine();
        this.intents = new IntentEngine();
        this.movement = new MovementEngine();
        this.decisions = new DecisionEngine();
        this.ballEngine = new BallEngine();
        this.duels = new DuelResolver();
        this.rules = new RulesEngine();
        this.morale = new MoraleSystem();
        this.space = new SpaceEvaluation();
        this.prediction = new PredictionSystem();
        this.coordination = new TeamCoordination();
        this.fatigue = new FatigueSystem();
        this.offside = new OffsideTracker();
        this.metrics = new MatchMetrics();
    }

    public MatchState getState() {
        return state;
    }

    public void startKickoffPlay() {
        PlayerSnapshot kickoffTaker = findKickoffTaker(state.lastTouchTeam.equals("HOME") ? "AWAY" : "HOME");
        if (kickoffTaker != null) {
            ballEngine.setCarrier(state, kickoffTaker.playerId(), kickoffTaker.teamSide());
            state.possessionTeam = kickoffTaker.teamSide();
        }
    }

    public void executePenalty() {
        String attackingSide = state.pendingPenaltyTeamSide;
        if (attackingSide == null) {
            attackingSide = state.lastTouchTeam != null ? state.lastTouchTeam : "HOME";
        }
        String defendingSide = attackingSide.equals("HOME") ? "AWAY" : "HOME";

        PlayerSnapshot taker = null;
        if (state.pendingPenaltyTakerId != null) {
            taker = state.snapshotById(state.pendingPenaltyTakerId);
            if (taker != null && !attackingSide.equals(taker.teamSide())) taker = null;
        }
        if (taker == null) {
            taker = findBestPenaltyTaker(attackingSide);
        }
        PlayerSnapshot gk = findGkOnPitch(defendingSide);

        state.pendingPenaltyTakerId = null;
        state.pendingPenaltyTeamSide = null;

        if (taker == null) {
            ballEngine.setLoose(state, 50.0, 50.0);
            state.possessionTeam = attackingSide;
            return;
        }

        DuelResolver.DuelResult result = duels.resolvePenalty(taker, gk);
        double xG = 0.76;

        if (result.goal()) {
            metrics.onShot();
            metrics.onShotOnTarget();
            metrics.onGoal();
            if ("HOME".equals(attackingSide)) { homeGoals++; homeShots++; homeShotsOnTarget++; }
            else { awayGoals++; awayShots++; awayShotsOnTarget++; }
            state.addEvent(new org.example.footballmanager.newLogic.model.event.PenaltyEvent(state.minute, state.tick,
                taker.playerId(), taker.name(), attackingSide, true, false, xG));
            state.addEvent(new org.example.footballmanager.newLogic.model.event.GoalEvent(state.minute, state.tick,
                taker.playerId(), taker.name(), null, null, attackingSide, xG, homeGoals, awayGoals));
            state.lastTouchTeam = attackingSide;
            ballEngine.setLoose(state, 50.0, 50.0);
            state.stoppage = MatchState.StoppageType.KICK_OFF;
            state.stoppageTicks = 6;
        } else if (result.saved()) {
            metrics.onShot();
            metrics.onShotOnTarget();
            if ("HOME".equals(attackingSide)) { homeShots++; homeShotsOnTarget++; }
            else { awayShots++; awayShotsOnTarget++; }
            state.addEvent(new org.example.footballmanager.newLogic.model.event.PenaltyEvent(state.minute, state.tick,
                taker.playerId(), taker.name(), attackingSide, false, true, xG));
            if (gk != null) {
                state.addEvent(new org.example.footballmanager.newLogic.model.event.GkSaveEvent(state.minute, state.tick, state.possessionChainId,
                    gk.playerId(), gk.name(), defendingSide,
                    taker.playerId(), taker.name(), xG, "penalty_saved", 50.0, 50.0));
                ballEngine.setCarrier(state, gk.playerId(), defendingSide);
                state.possessionTeam = defendingSide;
                state.lastTouchTeam = defendingSide;
            } else {
                ballEngine.setLoose(state, 50.0, 50.0);
                state.possessionTeam = defendingSide;
            }
        } else {
            metrics.onShot();
            if ("HOME".equals(attackingSide)) homeShots++;
            else awayShots++;
            state.addEvent(new org.example.footballmanager.newLogic.model.event.PenaltyEvent(state.minute, state.tick,
                taker.playerId(), taker.name(), attackingSide, false, false, xG));
            state.lastTouchTeam = attackingSide;
            state.stoppage = MatchState.StoppageType.GOAL_KICK;
            state.stoppageTicks = 5;
            state.possessionTeam = defendingSide;
            ballEngine.setLoose(state, 50.0, 50.0);
        }
    }

    public void releaseBallAfterStoppage() {
        if (state.stoppage == MatchState.StoppageType.CORNER) {
            double cx = "HOME".equals(state.possessionTeam) ? 95.0 : 5.0;
            double cy = RNG.nextDouble() < 0.5 ? 7.0 : 93.0;
            state.ball = BallState.at(cx, cy);
        } else if (state.stoppage == MatchState.StoppageType.THROW_IN) {
            double by = state.ball.y();
            by = Math.max(MatchState.MIN_Y, Math.min(MatchState.MAX_Y, by));
            double bx = "HOME".equals(state.possessionTeam) ? 5.0 : 95.0;
            state.ball = BallState.at(bx, by);
        } else if (state.stoppage == MatchState.StoppageType.GOAL_KICK) {
            state.ball = BallState.at(
                "HOME".equals(state.possessionTeam) ? 5.0 : 95.0,
                50.0
            );
        } else if (state.stoppage == MatchState.StoppageType.FREE_KICK) {

        } else if (state.stoppage == MatchState.StoppageType.GOAL_CELEBRATION) {
            state.ball = BallState.at(50.0, 50.0);
        } else if (state.stoppage == MatchState.StoppageType.KICK_OFF) {
            state.ball = BallState.at(50.0, 50.0);
        }

        PlayerSnapshot closest = null;
        double minDist = Double.MAX_VALUE;

        for (PlayerSnapshot snap : state.playerSnapshots) {
            double dist = snap.distanceToPoint(state.ball.x(), state.ball.y());
            if (dist < minDist) {
                minDist = dist;
                closest = snap;
            }
        }

        if (closest != null) {
            ballEngine.setCarrier(state, closest.playerId(), closest.teamSide());
            state.possessionTeam = closest.teamSide();
        }
    }

    public FatigueSystem getFatigueSystem() {
        return fatigue;
    }

    public double getOffsideLineX(String defendingTeam) {
        if (offside == null) return -1;
        return offside.calculateOffsideLine(state, defendingTeam);
    }

    public void initializeMatchState(Match match) {
        this.state = new MatchState(match);
        this.state.simulatorMetrics = this.metrics; // Set metrics reference for tracking
        this.homeGoals = 0;
        this.awayGoals = 0;
        this.homeShots = 0;
        this.awayShots = 0;
        this.homeShotsOnTarget = 0;
        this.awayShotsOnTarget = 0;
        this.homeFouls = 0;
        this.awayFouls = 0;
        this.homeCorners = 0;
        this.awayCorners = 0;
        this.homeYellowCards = 0;
        this.awayYellowCards = 0;
        this.homeRedCards = 0;
        this.homeSuccessfulPasses = 0;
        this.awaySuccessfulPasses = 0;
        this.homeTotalPasses = 0;
        this.awayTotalPasses = 0;
        this.        totalStoppageTicks = 0;

        seedPlayerSlotKeys();
        MovementEngine.initializePositions(state, state.match.homeTeam().tacticRules(), state.match.awayTeam().tacticRules());

        state.carrierId = null;
        state.carrierTeamSide = null;
        state.possessionTeam = null;
        state.lastTouchTeam = "HOME";
        state.stoppage = MatchState.StoppageType.KICK_OFF;
        state.stoppageTicks = 6;
    }

    public void simulateTick() {
        state.tick++;

        tickStartPositions.clear();
        for (PlayerSnapshot snap : state.playerSnapshots) {
            tickStartPositions.put(snap.playerId(), new double[]{snap.x(), snap.y()});
        }

        // Update action timers for all players (action commitment)
        for (PlayerSnapshot snap : state.playerSnapshots) {
            snap.updateActionTick();
        }

        removeSentOffPlayers();
        checkBallOutOfBounds();

        zones.updateTargets(state, state.match.homeTeam().tacticRules(), state.match.awayTeam().tacticRules());
        tacticalIntents.updateIntents(state, state.match.homeTeam().tacticRules(), state.match.awayTeam().tacticRules());
        awareness.update(state);
        intents.update(state, awareness);
        // PlayerDecisionDemo ordering: after the carrier decides, attacking
        // teammates react to the pass target, then defenders press. The tactical
        // editor remains the base shape — these are the clear-situation overrides.
        applyAttackerReactionOverrides();
        List<Long> offsideRetreatPlayers = offside.updateRetreat(state);
        for (long pid : offsideRetreatPlayers) {
            intents.forceIntent(pid, PlayerSnapshot.Intent.RETURN_TO_SHAPE);
        }
        // Attacker-reaction overrides set PlayerSnapshot.Intent.SUPPORT; make the
        // intent engine consume their desired position instead of a computed run.
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.intent() == PlayerSnapshot.Intent.SUPPORT) {
                intents.forceIntent(snap.playerId(), PlayerSnapshot.Intent.SUPPORT);
            }
        }
        coordination.update(state, intents, awareness);
        updateMovement();
        MovementEngine.processBlends(state);
        resolveCollisions();

        // If someone holds the ball, increment their possession counter and enforce timeout
        if (state.carrierId != null && !state.ballInTransit) {
            PlayerSnapshot carrier = state.snapshotById(state.carrierId);
            if (carrier != null) {
                carrier.incPossessionTick();
                // If possession exceeds 60 ticks (~30s), force a pass to avoid indefinite carry
                if (carrier.getPossessionTicks() > 60) {
                    executePass(carrier, 25.0);
                    carrier.resetPossessionTicks();
                } else {
                    updateBallCarrierDecision();
                }
            }
        }

        ballEngine.updateBall(state);
        updateDuels();
        offside.update(state);
        updateLooseBallPickup();
        updatePossessionTracking();
        morale.update(state);
        space.update(state);
        prediction.update(state);
        updatePossessionState();
        clampPaceThisTick();
    }

    /**
     * Hard no-teleport invariant: every player's per-tick displacement is capped
     * at their pace speed (PACE_STEP_MIN 0.04 .. PACE_STEP_MAX 0.33 units/tick).
     * Runs after every movement/collision/possession system so no combination of
     * carry + nudge + loose-ball chase can ever exceed the cap.
     */
    private void clampPaceThisTick() {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            double[] start = tickStartPositions.get(snap.playerId());
            if (start == null) continue;
            double dx = snap.x() - start[0];
            double dy = snap.y() - start[1];
            double moved = Math.sqrt(dx * dx + dy * dy);
            double maxStep = MovementEngine.paceToSpeed(snap.pace());
            if (moved > maxStep) {
                double scale = maxStep / moved;
                snap.setPosition(start[0] + dx * scale, start[1] + dy * scale);
            }
        }
    }

    private void updateMovement() {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            // Ball carrier movement is handled by the action engine (CARRY/DRIBBLE);
            // moving them here too would stack two movements in a single tick
            if (state.carrierId != null && state.carrierId == snap.playerId()) continue;
            if (MovementEngine.hasActiveBlend(snap.playerId())) continue;
            double[] target = intents.getTarget(snap.playerId(), snap, state, awareness, zones);
            TeamCoordination.CoordinationAdjustment coord = coordination.get(snap.playerId());

            double finalX = target[0] + coord.offsetX();
            double finalY = target[1] + coord.offsetY();

            finalX = Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, finalX));
            finalY = Math.max(MatchState.MIN_Y, Math.min(MatchState.MAX_Y, finalY));

            double fatigueMod = fatigue.getFatigueModifier(snap.playerId());
            double dx = finalX - snap.x();
            double dy = finalY - snap.y();
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist < 0.5) continue;

            double baseSpeed = 0.04 + (snap.pace() / 20.0) * 0.29;
            double speed = baseSpeed * fatigueMod;
            double moveDist = Math.min(speed, dist);

            double moveX = (dx / dist) * moveDist;
            double moveY = (dy / dist) * moveDist;

            double newX = Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, snap.x() + moveX));
            double newY = Math.max(MatchState.MIN_Y, Math.min(MatchState.MAX_Y, snap.y() + moveY));

            snap.setPosition(newX, newY);
        }
    }

    /**
     * PlayerDecisionDemo attacker reaction: while a pass is in flight, off-ball
     * teammates near the pass landing zone move toward it (positive-threat
     * override). A teammate already standing at the landing zone stays put.
     * Everyone else keeps the tactical editor position.
     */
    private void applyAttackerReactionOverrides() {
        if (!state.ballInTransit || state.pendingReceiverId == null) return;

        double targetX = state.transitTargetX;
        double targetY = state.transitTargetY;

        // The receiver should HOLD_POSITION — stay at the landing zone so the pass lands cleanly
        PlayerSnapshot receiver = state.snapshotById(state.pendingReceiverId);
        if (receiver != null && !receiver.position().equals(Position.GK)) {
            receiver.setDesiredPosition(targetX, targetY);
            receiver.setIntent(PlayerSnapshot.Intent.HOLD_POSITION);
            receiver.setReason("Receiving: holding position for pass");
        }

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.position() == Position.GK) continue;
            if (snap.playerId() == state.pendingReceiverId) continue;
            if (!snap.teamSide().equals(state.pendingPassTeam)) continue;

            double distToLanding = snap.distanceToPoint(targetX, targetY);
            if (distToLanding < REACTION_ADJACENT_DISTANCE) {
                snap.setDesiredPosition(targetX, targetY);
                snap.setIntent(PlayerSnapshot.Intent.SUPPORT);
                snap.setReason("Attacker reaction: pass lands adjacent");
            }
        }
    }

    /**
     * PlayerDecisionDemo collision phase: after movement, players that overlap
     * (same team or opposing, ball not contested) are nudged apart. The ball
     * carrier keeps the ball; the other player yields. The nudge is small per
     * tick so it never looks like a teleport.
     */
    private void resolveCollisions() {
        List<PlayerSnapshot> players = state.playerSnapshots;
        // Per-tick nudge budget so collision separation never stacks with normal
        // movement past the teleport threshold (0.33 + 0.15 = 0.48 < 0.5/tick).
        Map<Long, Double> nudgeBudget = new HashMap<>();

        for (int i = 0; i < players.size(); i++) {
            PlayerSnapshot a = players.get(i);
            if (a.position() == Position.GK) continue;
            for (int j = i + 1; j < players.size(); j++) {
                PlayerSnapshot b = players.get(j);
                if (b.position() == Position.GK) continue;

                double dx = b.x() - a.x();
                double dy = b.y() - a.y();
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist >= COLLISION_MIN_SEPARATION) continue;

                PlayerSnapshot mover;
                boolean aIsCarrier = state.carrierId != null && state.carrierId == a.playerId();
                boolean bIsCarrier = state.carrierId != null && state.carrierId == b.playerId();
                if (aIsCarrier) {
                    mover = b;
                } else if (bIsCarrier) {
                    mover = a;
                } else if (a.position() == Position.ATT || a.position() == Position.WNG) {
                    mover = b;
                } else {
                    mover = a;
                }

                double remaining = COLLISION_MAX_NUDGE - nudgeBudget.getOrDefault(mover.playerId(), 0.0);
                if (remaining <= 0.0001) continue;

                double overlap = COLLISION_MIN_SEPARATION - dist;
                double nudge = Math.min(remaining, overlap);
                double dirX = dist > 0.0001 ? dx / dist : 1.0;
                double dirY = dist > 0.0001 ? dy / dist : 0.0;

                double nx = Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, mover.x() - dirX * nudge));
                double ny = Math.max(MatchState.MIN_Y, Math.min(MatchState.MAX_Y, mover.y() - dirY * nudge));
                mover.setPosition(nx, ny);
                nudgeBudget.merge(mover.playerId(), nudge, Double::sum);
            }
        }
    }

    private void updateBallCarrierDecision() {
        if (state.tick - state.lastDecisionTick < 6) return;

        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return;

        // Respect action commitment: do not re-decide while busy
        if (carrier.isBusy()) return;

        DecisionEngine.BallAction action = decisions.decide(state, carrier, awareness);
        state.lastDecisionTick = state.tick;

        // Emit a decision event so the replay viewer can show *why* the
        // carrier chose this action — the action itself is visible through
        // the follow-up PassEvent/ShotEvent, but the decision intent isn't.
        String reason = action.name().equals("SHORT_PASS") || action.name().equals("LONG_PASS")
            ? "open_lane"
            : action.name().equals("SHOOT") ? "in_range"
            : action.name().equals("DRIBBLE") ? "open_space"
            : "default";
        state.addEvent(new BallCarrierDecisionEvent(state.minute, state.tick,
            state.possessionChainId,
            carrier.playerId(), carrier.name(), carrier.teamSide(),
            action.name(), reason, carrier.x(), carrier.y()));

        executeAction(carrier, action);
    }

    private void executeAction(PlayerSnapshot carrier, DecisionEngine.BallAction action) {
        // Update lastBallAction and streak
        String actName = action.name();
        if (state.lastBallAction != null && state.lastBallAction.equals(actName)) {
            state.lastBallActionStreak = state.lastBallActionStreak + 1;
        } else {
            state.lastBallActionStreak = 1;
        }
        state.lastBallAction = actName;

        switch (action) {
            case CARRY -> {
                metrics.onCarry();
                carrier.setCurrentAction(actName, 6); // ~3s
                boolean home = carrier.teamSide().equals("HOME");
                double goalX = home ? 96.0 : 4.0;
                double dx = goalX - carrier.x();
                double baseDy = 50.0 - carrier.y();
                boolean inOpponentHalf = home ? carrier.x() > 50 : carrier.x() < 50;

                boolean defenderBlocking = false;
                for (PlayerSnapshot opp : state.playerSnapshots) {
                    if (opp.teamSide().equals(carrier.teamSide())) continue;
                    if (carrier.distanceTo(opp) < 4.0) {
                        defenderBlocking = true;
                        baseDy = carrier.y() < 50.0 ? -3.0 : 3.0;
                        break;
                    }
                }

                double dist = Math.sqrt(dx * dx + baseDy * baseDy);
                if (dist > 0.5) {
                    double move;
                    if (defenderBlocking) {
                        move = Math.min(0.22, dist);
                    } else if (inOpponentHalf) {
                        move = Math.min(0.45, dist);
                    } else {
                        move = Math.min(0.30, dist);
                    }
                    carrier.setPosition(carrier.x() + (dx / dist) * move, carrier.y() + (baseDy / dist) * move);
                }
                ballEngine.setCarrier(state, carrier.playerId(), carrier.teamSide());
            }
            case SHORT_PASS -> { metrics.onPass(); carrier.setCurrentAction(actName, 4); executePass(carrier, 15.0); }
            case LONG_PASS -> { metrics.onPass(); carrier.setCurrentAction(actName, 6); executePass(carrier, 35.0); }
            case CROSS -> { metrics.onCross(); carrier.setCurrentAction(actName, 6); executeCross(carrier); }
            case SHOOT -> { carrier.setCurrentAction(actName, 6); executeShot(carrier); }
            case DRIBBLE -> {
                metrics.onDribble();
                carrier.setCurrentAction(actName, 6);
                // Dribble forward similar to carry but more aggressive
                boolean homeD = carrier.teamSide().equals("HOME");
                double goalXD = homeD ? 96.0 : 4.0;
                double dxD = goalXD - carrier.x();
                double baseDyD = 50.0 - carrier.y();
                double distD = Math.sqrt(dxD * dxD + baseDyD * baseDyD);
                if (distD > 0.5) {
                    double moveD = Math.min(0.4, distD * 0.15 + 0.1);
                    carrier.setPosition(carrier.x() + (dxD / distD) * moveD, carrier.y() + (baseDyD / distD) * moveD);
                }
                ballEngine.setCarrier(state, carrier.playerId(), carrier.teamSide());

                // If defender nearby, resolve dribble duel
                PlayerSnapshot nearestDef = findNearestOpponent(carrier, carrier.teamSide());
                if (nearestDef != null && carrier.distanceTo(nearestDef) < 2.5) {
                    DuelResolver.DuelResult dr = duels.resolveNumericDuel(carrier, java.util.List.of(carrier), java.util.List.of(nearestDef));
                    if (!dr.attackerWins()) {
                        // lost dribble -> tackle
                        ballEngine.setCarrier(state, nearestDef.playerId(), nearestDef.teamSide());
                        state.possessionTeam = nearestDef.teamSide();
                        state.addEvent(new TackleEvent(state.minute, state.tick, 0,
                            nearestDef.playerId(), nearestDef.name(), nearestDef.teamSide(),
                            carrier.playerId(), carrier.name(), false,
                            "dribble_lost", nearestDef.x(), nearestDef.y()));
                    } else {
                        // success: if near goal, maybe shoot
                        double distToGoalD = distanceToGoal(carrier);
                        if (distToGoalD < 20.0 && RNG.nextDouble() < 0.18) {
                            executeShot(carrier);
                        }
                    }
                }
            }
            case THROUGH_PASS -> { metrics.onThroughBall(); carrier.setCurrentAction(actName, 5); executePass(carrier, 25.0); }
            case CLEAR -> { metrics.onClearance(); carrier.setCurrentAction(actName, 4); executeClearance(carrier); }
        }
    }

    private void executePass(PlayerSnapshot carrier, double maxDist) {
        PlayerSnapshot bestReceiver = null;
        double bestScore = -1;

        double goalX = carrier.teamSide().equals("HOME") ? 96.0 : 4.0;

        for (PlayerSnapshot teammate : state.playerSnapshots) {
            if (!teammate.teamSide().equals(carrier.teamSide())) continue;
            if (teammate.playerId() == carrier.playerId()) continue;

            double dist = carrier.distanceTo(teammate);
            if (dist > maxDist || dist < 3.0) continue;

            double distScore = 1.0 / (1.0 + dist);

            double distToGoalTeammate = Math.abs(teammate.x() - goalX);
            double distToGoalCarrier = Math.abs(carrier.x() - goalX);
            double forwardBonus = distToGoalCarrier > distToGoalTeammate ? 0.5 : -0.2;

            double passSkill = (carrier.passing() + carrier.technique()) / 40.0;
            double score = distScore + forwardBonus + passSkill * 0.2;

            if (score > bestScore) {
                bestScore = score;
                bestReceiver = teammate;
            }
        }

        if (bestReceiver != null) {
            int duration = Math.max(2, (int) (carrier.distanceTo(bestReceiver) / 3.0));

            // Resolve what actually happened to the pass (interception,
            // inaccuracy, out of bounds, completion) in the DuelResolver.
            DuelResolver.PassResolution res = duels.resolvePass(carrier, bestReceiver, state.playerSnapshots);

            switch (res.outcome()) {
                case INTERCEPTED -> {
                    PlayerSnapshot interceptor = res.interceptor();
                    ballEngine.startTransit(state, interceptor.x(), interceptor.y(),
                        Math.max(2, duration / 2), interceptor.playerId(), interceptor.teamSide());
                    state.addEvent(new PassInterceptedEvent(state.minute, state.tick,
                        state.possessionChainId,
                        carrier.playerId(), carrier.name(),
                        interceptor.playerId(), interceptor.name(), interceptor.teamSide(),
                        "intercepted", interceptor.x(), interceptor.y()));
                    // interception ends run of backward passes
                    state.backwardPassCount = 0;
                    state.lastBallAction = "PASS";
                    state.lastBallActionStreak = 1;
                }
                case INACCURATE -> {
                    // Misplaced pass: ball flies to the error spot and lands loose
                    state.addEvent(new PassIncompleteEvent(state.minute, state.tick,
                        state.possessionChainId,
                        carrier.playerId(), carrier.name(), carrier.teamSide(),
                        "incomplete", "misplaced pass", res.x(), res.y()));
                    state.lastTouchTeam = carrier.teamSide();
                    ballEngine.startTransit(state, res.x(), res.y(), duration, null, carrier.teamSide());
                    state.lastBallAction = "PASS";
                    state.lastBallActionStreak = 1;
                    if (carrier.teamSide().equals("HOME")) homeTotalPasses++;
                    else awayTotalPasses++;
                }
                case OUT_OF_BOUNDS -> {
                    // Ball travels past the touchline; checkBallOutOfBounds handles
                    // the restart when it crosses. No instant repositioning.
                    state.lastTouchTeam = carrier.teamSide();
                    ballEngine.startTransit(state, res.x(), res.y(), duration, null, carrier.teamSide());
                }
                default -> {
                    ballEngine.startTransit(state, bestReceiver.x(), bestReceiver.y(),
                        duration, bestReceiver.playerId(), carrier.teamSide());
                    state.addEvent(PassEvent.completed(state.minute, state.tick,
                        carrier.playerId(), carrier.name(),
                        bestReceiver.playerId(), bestReceiver.name(), carrier.teamSide()));

                    // Update backward pass counter: if receiver is further from goal than carrier, count as backward
                    double distCarrier = Math.abs(carrier.x() - goalX);
                    double distReceiver = Math.abs(bestReceiver.x() - goalX);
                    if (distReceiver > distCarrier) {
                        state.backwardPassCount = state.backwardPassCount + 1;
                    } else {
                        state.backwardPassCount = 0;
                    }
                    state.lastBallAction = "PASS";
                    state.lastBallActionStreak = 1;
                    if (carrier.teamSide().equals("HOME")) {
                        homeSuccessfulPasses++;
                        homeTotalPasses++;
                    } else {
                        awaySuccessfulPasses++;
                        awayTotalPasses++;
                    }
                    state.possessionPassCount++;
                    state.lastPasserId = carrier.playerId(); // Track for assist attribution

                    // Offside check: forward pass to a player beyond the offside line
                    offside.checkOffsideOnPass(state, carrier, bestReceiver);
                }
            }
        }
    }

    private void executeCross(PlayerSnapshot carrier) {
        double targetX = carrier.teamSide().equals("HOME") ? 88.0 : 12.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 20.0;

        double crossQuality = (carrier.passing() + carrier.technique()) / 40.0;

        state.addEvent(new CrossEvent(state.minute, state.tick, 0,
            carrier.playerId(), carrier.name(), carrier.teamSide(),
            "cross", carrier.x(), carrier.y()));

        // Resolve cross outcome in the DuelResolver (pure resolution).
        DuelResolver.CrossOutcome crossOutcome = duels.resolveCrossOutcome(carrier, crossQuality);

        if (crossOutcome.goalKick()) {
            String defendingTeam = carrier.teamSide().equals("HOME") ? "AWAY" : "HOME";
            state.stoppage = MatchState.StoppageType.GOAL_KICK;
            state.stoppageTicks = 5;
            state.possessionTeam = defendingTeam;
            rules.checkGoalKick(state, defendingTeam);
            metrics.onGoalKick();
            ballEngine.setLoose(state, targetX, targetY);
            return;
        }

        if (crossOutcome.accurate()) {
            PlayerSnapshot target = findAttackerInBox(carrier.teamSide());
            if (target != null) {
                DuelResolver.DuelResult result = duels.resolveHeaderDuel(target, findNearestDefender(target));
                state.addEvent(new CrossHeaderEvent(state.minute, state.tick,
                    state.possessionChainId,
                    target.playerId(), target.name(), target.teamSide(),
                    carrier.playerId(), carrier.name(),
                    result.attackerWins(), result.xG(),
                    "cross_header", target.x(), target.y()));
                if (result.attackerWins()) {
                    executeShot(target);
                    return;
                }
            }
        }

        // shorten cross flight for realism
        int crossDuration = Math.max(3, (int) (Math.hypot(targetX - carrier.x(), targetY - carrier.y()) / 8.0));
        ballEngine.startTransit(state, targetX, targetY, crossDuration, null, carrier.teamSide());
    }

    private void executeShot(PlayerSnapshot carrier) {
        double distToGoal = distanceToGoal(carrier);
        double xG = calculateXG(distToGoal);

        double shootQuality = (carrier.shooting() + carrier.technique()) / 40.0;
        // Increased shoot quality multiplier for better goal conversion
        xG *= (0.8 + shootQuality * 0.8);

        PlayerSnapshot gk = findGkOnPitch(carrier.teamSide().equals("HOME") ? "AWAY" : "HOME");
        DuelResolver.DuelResult result;
        if (gk != null) {
            result = duels.resolveShotDuel(carrier, gk, distToGoal, xG);
        } else {
            result = duels.resolveOpenGoalShot(carrier, distToGoal, xG);
        }

        metrics.onShot();
        if (carrier.teamSide().equals("HOME")) homeShots++;
        else awayShots++;

        boolean onTarget = "GOAL".equals(result.resultType()) || "SAVED".equals(result.resultType());
        if (onTarget) {
            metrics.onShotOnTarget();
            if (carrier.teamSide().equals("HOME")) homeShotsOnTarget++;
            else awayShotsOnTarget++;
        }

        if (result.goal()) {
            metrics.onGoal();
            if (carrier.teamSide().equals("HOME")) homeGoals++;
            else awayGoals++;

            // Find assist from last passer (same team, not the scorer)
            Long assistId = null;
            String assistName = null;
            if (state.lastPasserId != null && state.lastPasserId != carrier.playerId()) {
                PlayerSnapshot passer = state.snapshotById(state.lastPasserId);
                if (passer != null && passer.teamSide().equals(carrier.teamSide())) {
                    assistId = passer.playerId();
                    assistName = passer.name();
                }
            }

            state.addEvent(new GoalEvent(state.minute, state.tick,
                carrier.playerId(), carrier.name(), assistId, assistName,
                carrier.teamSide(), result.xG(), homeGoals, awayGoals));
            ballEngine.setLoose(state, 50.0, 50.0);
            state.stoppage = MatchState.StoppageType.KICK_OFF;
            state.stoppageTicks = 6;
        } else if ("SAVED".equals(result.resultType())) {
            state.addEvent(new ShotSavedEvent(state.minute, state.tick,
                state.possessionChainId,
                carrier.playerId(), carrier.name(), carrier.teamSide(),
                gk != null ? gk.playerId() : 0, gk != null ? gk.name() : "GK",
                result.xG(), "shot_saved", carrier.x(), carrier.y()));
            if (gk != null) {
                ballEngine.setCarrier(state, gk.playerId(), gk.teamSide());
                state.possessionTeam = gk.teamSide();
            } else {
                ballEngine.setLoose(state, 50.0, 50.0);
            }
        } else {
            state.addEvent(new ShotMissedEvent(state.minute, state.tick,
                state.possessionChainId,
                carrier.playerId(), carrier.name(), carrier.teamSide(),
                result.xG(), "shot_missed", carrier.x(), carrier.y()));
            // Resolve where the missed shot ends up — corner or goal kick.
            DuelResolver.ShotMissOutcome missOutcome = duels.resolveShotMissOutcome(carrier, carrier.x(), carrier.y());
            if (missOutcome.attackingTeamGetsRestart()) {
                state.stoppage = MatchState.StoppageType.CORNER;
                state.stoppageTicks = 5;
                state.possessionTeam = carrier.teamSide();
                rules.checkCorner(state, carrier.teamSide());
                metrics.onCorner();
            } else {
                state.stoppage = MatchState.StoppageType.GOAL_KICK;
                state.stoppageTicks = 5;
                state.possessionTeam = carrier.teamSide().equals("HOME") ? "AWAY" : "HOME";
                metrics.onGoalKick();
            }
            ballEngine.setLoose(state, 50.0, 50.0);
        }
    }

    private void executeClearance(PlayerSnapshot carrier) {
        double clearQuality = (carrier.passing() + carrier.defending()) / 40.0;

        // Resolve clearance outcome in the DuelResolver (pure resolution).
        DuelResolver.ClearanceOutcome outcome = duels.resolveClearanceOutcome(carrier, clearQuality);

        if (outcome.foundTeammate() && outcome.teammateId() > 0) {
            PlayerSnapshot teammate = state.snapshotById(outcome.teammateId());
            if (teammate != null) {
                int duration = Math.max(3, (int) (carrier.distanceTo(teammate) / 1.8));
                ballEngine.startTransit(state, teammate.x(), teammate.y(),
                    duration, teammate.playerId(), carrier.teamSide());
                state.addEvent(new ClearanceEvent(state.minute, state.tick, 0,
                    carrier.playerId(), carrier.name(), carrier.teamSide(),
                    "clearance", carrier.x(), carrier.y()));
                return;
            }
        }

        ballEngine.startTransit(state, outcome.targetX(), outcome.targetY(), 30, null, carrier.teamSide());
        state.addEvent(new ClearanceEvent(state.minute, state.tick, 0,
            carrier.playerId(), carrier.name(), carrier.teamSide(),
            "clearance", carrier.x(), carrier.y()));
    }

    private void updateDuels() {
        if (state.carrierId == null) return;
        if (state.tick - state.lastDuelTick < 3) return; // duel cooldown

        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return;

        List<PlayerSnapshot> nearbyDefenders = new ArrayList<>();
        for (PlayerSnapshot opponent : state.playerSnapshots) {
            if (opponent.teamSide().equals(carrier.teamSide())) continue;
            double dist = carrier.distanceTo(opponent);
            if (dist < 5.0) {
                nearbyDefenders.add(opponent);
            }
        }

        if (nearbyDefenders.isEmpty()) return;

        state.lastDuelTick = state.tick;
        metrics.onDuel();

        List<PlayerSnapshot> nearbyAttackers = new ArrayList<>();
        nearbyAttackers.add(carrier);
        for (PlayerSnapshot teammate : state.playerSnapshots) {
            if (!teammate.teamSide().equals(carrier.teamSide())) continue;
            if (teammate.playerId() == carrier.playerId()) continue;
            double dist = carrier.distanceTo(teammate);
            if (dist < 5.0) {
                nearbyAttackers.add(teammate);
            }
        }

        DuelResolver.DuelResult result = duels.resolveNumericDuel(carrier, nearbyAttackers, nearbyDefenders);

        if (!result.attackerWins()) {
            PlayerSnapshot tackler = nearbyDefenders.get(0);
            // Pick a random defender from the nearby cluster as the tackler so
            // cards distribute across the back line rather than always hitting
            // the same player (who'd then hit the 15-minute rebook cooldown).
            if (nearbyDefenders.size() > 1) {
                tackler = nearbyDefenders.get(RNG.nextInt(nearbyDefenders.size()));
            }
            // Combined defending IQ: defending (tackling), technique (clean
            // timing) and pace (recovery). Higher total → cleaner challenge,
            // lower chance of fouling. Skill totals in football range roughly
            // 30 (amateur) to 60 (world class).
            int tackleIq = tackler.defending() + tackler.technique() + tackler.pace();
            double skillFactor = Math.min(1.0, tackleIq / 60.0);
            // Base foul rate for an unskilled defender ~75%; scales down to
            // ~25% for a top-class centre-back.
            double foulChance = 0.75 - skillFactor * 0.50;
            // Defenders are MUCH more careful in the penalty box — conceding
            // a penalty is far worse than conceding a corner, so top defenders
            // pull out of 50/50s. Cut foul chance by 70%.
            double distToGoal = Math.abs(carrier.x() - (carrier.teamSide().equals("HOME") ? 100.0 : 0.0));
            if (distToGoal < 16.5) {
                foulChance *= 0.30;
            }

            if (RNG.nextDouble() < foulChance) {
                metrics.onFoul();
                RulesEngine.FoulResult fr = rules.checkFoul(state, tackler, carrier);
                if (fr.foulCommitted()) {
                    if (tackler.teamSide().equals("HOME")) homeFouls++;
                    else awayFouls++;
                    if (fr.card() != null) {
                        if (fr.card() == CardEvent.CardType.RED) {
                            if (tackler.teamSide().equals("HOME")) homeRedCards++;
                            else awayRedCards++;
                        } else {
                            if (tackler.teamSide().equals("HOME")) homeYellowCards++;
                            else awayYellowCards++;
                        }
                    }
                }
            } else {
                ballEngine.setCarrier(state, tackler.playerId(), tackler.teamSide());
                state.possessionTeam = tackler.teamSide();
                state.addEvent(new TackleEvent(state.minute, state.tick, 0,
                    tackler.playerId(), tackler.name(), tackler.teamSide(),
                    carrier.playerId(), carrier.name(), true,
                    "tackle", tackler.x(), tackler.y()));
                metrics.onTackle();
            }
        }
    }

    private void checkBallOutOfBounds() {
        if (state.ballInTransit) return;
        if (state.stoppage != null) return;

        double bx = state.ball.x();
        double by = state.ball.y();

        boolean outOverGoalLine = bx < MatchState.MIN_X || bx > MatchState.MAX_X;
        boolean outOverSideline = by < MatchState.MIN_Y || by > MatchState.MAX_Y;

        if (!outOverGoalLine && !outOverSideline) return;

        String lastTeam = state.lastTouchTeam;
        if (lastTeam == null) lastTeam = "HOME";
        String defendingTeam = "HOME".equals(lastTeam) ? "AWAY" : "HOME";

        if (outOverGoalLine) {
            // Real rules: attacking team touches last over opponent's goal line
            // -> goal kick for the defending team. Defending team touches last
            // over own goal line -> corner for the attacking team.
            boolean overAwayGoal = bx > MatchState.MAX_X;
            String attackingTeam = overAwayGoal ? "HOME" : "AWAY";
            String goalLineDefender = "HOME".equals(attackingTeam) ? "AWAY" : "HOME";
            boolean lastTouchedByDefending = lastTeam.equals(goalLineDefender);

            if (lastTouchedByDefending) {
                state.stoppage = MatchState.StoppageType.CORNER;
                state.stoppageTicks = 5;
                state.possessionTeam = attackingTeam;
                rules.checkCorner(state, attackingTeam);
                metrics.onCorner();
            } else {
                state.stoppage = MatchState.StoppageType.GOAL_KICK;
                state.stoppageTicks = 5;
                state.possessionTeam = goalLineDefender;
                metrics.onGoalKick();
            }
        } else {
            // Throw-in for the opposing team of the last touch
            state.stoppage = MatchState.StoppageType.THROW_IN;
            state.stoppageTicks = 3;
            state.possessionTeam = defendingTeam;
            rules.checkThrowIn(state, defendingTeam);
            metrics.onThrowIn();
        }

        state.ball = BallState.at(
            Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, bx)),
            Math.max(MatchState.MIN_Y, Math.min(MatchState.MAX_Y, by))
        );
    }

    private void updateLooseBallPickup() {
        if (state.carrierId != null || state.ballInTransit) return;

        // Collect everyone within contest range. If exactly one player is
        // close, they pick it up cleanly. If two or more arrive together,
        // run a loose-ball duel — pace + technique decide the winner so the
        // contest feels like a 50/50 sprint, not just "who was declared first".
        final double PICKUP_RADIUS = 3.0;
        final double DUEL_RADIUS = 2.5;

        List<PlayerSnapshot> nearby = new ArrayList<>();
        for (PlayerSnapshot snap : state.playerSnapshots) {
            double dist = snap.distanceToPoint(state.ball.x(), state.ball.y());
            if (dist < PICKUP_RADIUS) {
                nearby.add(snap);
            }
        }

        if (nearby.isEmpty()) return;

        PlayerSnapshot winner;
        if (nearby.size() == 1) {
            winner = nearby.get(0);
        } else {
            // Duel between the two closest contestants (the most likely pair to
            // actually challenge each other in real football).
            nearby.sort((a, b) -> Double.compare(
                a.distanceToPoint(state.ball.x(), state.ball.y()),
                b.distanceToPoint(state.ball.x(), state.ball.y())));
            PlayerSnapshot c1 = nearby.get(0);
            PlayerSnapshot c2 = nearby.get(1);
            // Only run the duel if they're actually close to each other —
            // otherwise it's a clean pickup by the closer one.
            if (c1.distanceTo(c2) < DUEL_RADIUS) {
                DuelResolver.DuelResult dr = duels.resolveLooseBallDuel(c1, c2);
                winner = dr.attackerWins() ? c1 : c2;
            } else {
                winner = c1;
            }
        }

        // After winning the duel, ensure the winner actively chases the ball
        // by forcing a MAKE_RUN intent if the ball is in the central area
        // (where attackers/midfielders should naturally run towards it).
        if (winner != null) {
            if (state.ball.x() > 45.0 && state.ball.x() < 55.0) {
                // Central ball zone: attackers/midfielders should make runs
                if (winner.position() == Position.ATT || winner.position() == Position.MID) {
                    // Confirm intent to make run
                    if (winner.intent() != PlayerSnapshot.Intent.MAKE_RUN) {
                        winner.setIntent(PlayerSnapshot.Intent.MAKE_RUN);
                    }
                }
            }
        }

        ballEngine.setCarrier(state, winner.playerId(), winner.teamSide());
        state.possessionTeam = winner.teamSide();
        state.lastTouchTeam = winner.teamSide();
    }

    private void updatePossessionTracking() {
        String currentTeam = state.possessionTeam;
        if (currentTeam == null && state.lastTouchTeam != null) {
            currentTeam = state.lastTouchTeam;
        }
        if (currentTeam != null && !currentTeam.equals(state.possessionTeamLabel)) {
            if (state.possessionTeamLabel != null) {
                state.addEvent(new PossessionEndEvent(state.minute, state.tick,
                    state.possessionChainId, state.possessionTeamLabel,
                    state.possessionPassCount, "possession_change",
                    "possession_end", 50.0, 50.0));
            }
            state.possessionTeamLabel = currentTeam;
            state.possessionPassCount = 0;
            state.possessionChainId++;
            // Reset per-possession counters
            state.backwardPassCount = 0;
            state.lastBallAction = null;
            state.lastBallActionStreak = 0;
            state.addEvent(new PossessionStartEvent(state.minute, state.tick,
                state.possessionChainId, currentTeam, "possession_start", 50.0, 50.0));
        }
        if (state.carrierId != null && state.possessionTeamLabel != null) {
            state.possessionAgeTicks++;
        }
    }

    private void updatePossessionState() {
        if (state.carrierId != null) {
            if (state.carrierTeamSide.equals("HOME")) {
                state.homePossessionTicks++;
            } else {
                state.awayPossessionTicks++;
            }
        } else if (state.ballInTransit && state.transitPossessionTeam != null) {
            // During pass transit, credit possession to the passing team
            if (state.transitPossessionTeam.equals("HOME")) {
                state.homePossessionTicks++;
            } else {
                state.awayPossessionTicks++;
            }
        }
    }

    private void handleStoppageEnd() {
        MatchState.StoppageType endedStoppage = state.stoppage;
        state.stoppage = null;

        if (endedStoppage == MatchState.StoppageType.KICK_OFF) {
            startKickoffPlay();
        } else if (endedStoppage == MatchState.StoppageType.PENALTY) {
            executePenalty();
        } else if (state.carrierId == null) {
            releaseBallAfterStoppage();
        }
    }


    private PlayerSnapshot findKickoffTaker(String teamSide) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.teamSide().equals(teamSide) && snap.position() == Position.MID) {
                return snap;
            }
        }
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.teamSide().equals(teamSide)) {
                return snap;
            }
        }
        return null;
    }

    private PlayerSnapshot findGkOnPitch(String teamSide) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.teamSide().equals(teamSide) && snap.position() == Position.GK) {
                return snap;
            }
        }
        return null;
    }

    private PlayerSnapshot findBestPenaltyTaker(String teamSide) {
        PlayerSnapshot best = null;
        double bestScore = -1;
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(teamSide)) continue;
            double score = snap.shooting() + snap.technique();
            if (score > bestScore) {
                bestScore = score;
                best = snap;
            }
        }
        return best;
    }

    private PlayerSnapshot findNearestOpponent(PlayerSnapshot target, String friendlyTeam) {
        PlayerSnapshot nearest = null;
        double minDist = Double.MAX_VALUE;
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.teamSide().equals(friendlyTeam)) continue;
            double dist = target.distanceTo(snap);
            if (dist < minDist) {
                minDist = dist;
                nearest = snap;
            }
        }
        return nearest;
    }

    private PlayerSnapshot findNearestDefender(PlayerSnapshot target) {
        PlayerSnapshot nearest = null;
        double minDist = Double.MAX_VALUE;
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.teamSide().equals(target.teamSide())) continue;
            double dist = target.distanceTo(snap);
            if (dist < minDist) {
                minDist = dist;
                nearest = snap;
            }
        }
        return nearest != null ? nearest : new PlayerSnapshot(0, "AI", target.teamSide().equals("HOME") ? "AWAY" : "HOME",
            Position.DEF, target.x(), target.y(), "NORMAL", false);
    }

    private PlayerSnapshot findAttackerInBox(String teamSide) {
        double boxMinX = teamSide.equals("HOME") ? 78 : 4;
        double boxMaxX = teamSide.equals("HOME") ? 96 : 22;
        double boxMinY = 30;
        double boxMaxY = 70;

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(teamSide)) continue;
            if (snap.position() == Position.GK || snap.position() == Position.DEF) continue;
            if (snap.x() >= boxMinX && snap.x() <= boxMaxX && snap.y() >= boxMinY && snap.y() <= boxMaxY) {
                return snap;
            }
        }
        return null;
    }

    private PlayerSnapshot findBestClearanceTarget(PlayerSnapshot carrier) {
        PlayerSnapshot best = null;
        double bestScore = -1;
        double goalX = carrier.teamSide().equals("HOME") ? 96.0 : 4.0;

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(carrier.teamSide())) continue;
            if (snap.playerId() == carrier.playerId()) continue;
            double dist = carrier.distanceTo(snap);
            if (dist > 30 || dist < 5) continue;

            double distToGoal = Math.abs(snap.x() - goalX);
            double score = 1.0 / (1.0 + dist) + (distToGoal > 40 ? 0.3 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = snap;
            }
        }
        return best;
    }

    private static double getDistToGoal(PlayerSnapshot snap) {
        double goalX = snap.teamSide().equals("HOME") ? 96.0 : 4.0;
        double goalY = 50.0;
        return snap.distanceToPoint(goalX, goalY);
    }

    private void seedPlayerSlotKeys() {
        List<String> homeSlots = state.match.homeTeam().slotKeys();
        List<String> awaySlots = state.match.awayTeam().slotKeys();

        if (homeSlots == null || homeSlots.isEmpty()) {
            homeSlots = ZonePositionCalculator.buildSlotKeys(homeConfig.formation(), state.match.homeTeam().startingXI());
        }
        if (awaySlots == null || awaySlots.isEmpty()) {
            awaySlots = ZonePositionCalculator.buildSlotKeys(awayConfig.formation(), state.match.awayTeam().startingXI());
        }

        for (int i = 0; i < state.match.homeTeam().startingXI().size(); i++) {
            Player p = state.match.homeTeam().startingXI().get(i);
            String slot = i < homeSlots.size() ? homeSlots.get(i) : "UNK_" + i;
            state.playerSlotKeys.put(p.id(), slot);
        }
        for (int i = 0; i < state.match.awayTeam().startingXI().size(); i++) {
            Player p = state.match.awayTeam().startingXI().get(i);
            String slot = i < awaySlots.size() ? awaySlots.get(i) : "UNK_" + i;
            state.playerSlotKeys.put(p.id(), slot);
        }
    }

    private void removeSentOffPlayers() {
        if (state.sentOffPlayers.isEmpty()) return;
        state.playerSnapshots.removeIf(snap -> state.sentOffPlayers.contains(snap.playerId()));
        if (state.carrierId != null && state.sentOffPlayers.contains(state.carrierId)) {
            ballEngine.setLoose(state, state.ball.x(), state.ball.y());
        }
    }

    private MatchResult buildResult(Match match) {
        double homePoss = state.homePossessionTicks > 0 || state.awayPossessionTicks > 0
            ? 100.0 * state.homePossessionTicks / (state.homePossessionTicks + state.awayPossessionTicks)
            : 50.0;
        double awayPoss = 100.0 - homePoss;

        return new MatchResult(
            match.id(), homeGoals, awayGoals,
            List.copyOf(state.events), List.copyOf(state.tickHistory),
            state.tick, TICKS_PER_MINUTE,
            homePoss, awayPoss,
            homeShots, awayShots,
            homeShotsOnTarget, awayShotsOnTarget,
            homeFouls, awayFouls,
            homeCorners, awayCorners,
            homeYellowCards, awayYellowCards,
            homeRedCards, awayRedCards,
            0.0, 0.0
        );
    }

    private static double distanceToGoal(PlayerSnapshot snap) {
        double goalX = snap.teamSide().equals("HOME") ? 96.0 : 4.0;
        double goalY = 50.0;
        return snap.distanceToPoint(goalX, goalY);
    }

    private static double calculateXG(double distToGoal) {
        // Further increased xG values for more realistic goal conversion
        if (distToGoal < 6.0) return 0.75;  // Very close to goal
        if (distToGoal < 10.0) return 0.60; // Inside 6-yard box area
        if (distToGoal < 14.0) return 0.45; // Penalty area
        if (distToGoal < 20.0) return 0.30; // Edge of box
        if (distToGoal < 28.0) return 0.20; // Long range
        return 0.12;
    }

}
