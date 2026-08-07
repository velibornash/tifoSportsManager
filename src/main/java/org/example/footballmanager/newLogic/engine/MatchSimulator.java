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

    private MatchState state;
    private TeamConfig homeConfig, awayConfig;
    private ZonePositionCalculator zones;
    private TacticalIntentEngine tacticalIntents;
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

    private int minute;
    private int homeGoals, awayGoals;
    private int homeShots, awayShots;
    private int homeShotsOnTarget, awayShotsOnTarget;
    private int homeFouls, awayFouls;
    private int homeCorners, awayCorners;
    private int homeYellowCards, awayYellowCards;
    private int homeRedCards, awayRedCards;
    private int homeSuccessfulPasses, awaySuccessfulPasses;
    private int homeTotalPasses, awayTotalPasses;
    private int totalStoppageTicks;

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

    private void initializeSystems(Match match) {
        this.homeConfig = TeamConfig.fromTeam(match.homeTeam());
        this.awayConfig = TeamConfig.fromTeam(match.awayTeam());
        this.zones = new ZonePositionCalculator();
        this.tacticalIntents = new TacticalIntentEngine();
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

    private void initializeMatchState(Match match) {
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
        this.totalStoppageTicks = 0;

        MovementEngine.initializePositions(state);
        seedPlayerSlotKeys();

        state.carrierId = null;
        state.carrierTeamSide = null;
        state.possessionTeam = null;
        state.lastTouchTeam = "HOME";
        state.stoppage = MatchState.StoppageType.KICK_OFF;
        state.stoppageTicks = 6;
    }

    private void simulateTick() {
        state.tick++;

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
        coordination.update(state, intents, awareness);
        updateMovement();
        MovementEngine.processBlends(state);

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

    private void updateBallCarrierDecision() {
        if (state.tick - state.lastDecisionTick < 10) return;

        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return;

        // Respect action commitment: do not re-decide while busy
        if (carrier.isBusy()) return;

        DecisionEngine.BallAction action = decisions.decide(state, carrier, awareness);
        state.lastDecisionTick = state.tick;

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
                carrier.setCurrentAction(actName, 8); // ~4s
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
            }
            case SHORT_PASS -> { metrics.onPass(); carrier.setCurrentAction(actName, 4); executePass(carrier, 15.0); }
            case LONG_PASS -> { metrics.onPass(); carrier.setCurrentAction(actName, 6); executePass(carrier, 35.0); }
            case CROSS -> { metrics.onCross(); carrier.setCurrentAction(actName, 6); executeCross(carrier); }
            case SHOOT -> { carrier.setCurrentAction(actName, 6); executeShot(carrier); }
            case DRIBBLE -> {
                metrics.onDribble();
                carrier.setCurrentAction(actName, 8);
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
            double passQuality = (carrier.passing() + carrier.technique()) / 40.0;
            double interceptChance = 0.08 * (1.0 - passQuality);
            // Further shorten pass duration to reduce ball-in-flight time
            int duration = Math.max(2, (int) (carrier.distanceTo(bestReceiver) / 3.0));
            boolean intercepted = RNG.nextDouble() < interceptChance;

            if (intercepted) {
                PlayerSnapshot interceptor = findNearestOpponent(bestReceiver, carrier.teamSide());
                if (interceptor != null && interceptor.distanceTo(bestReceiver) < 6.0) {
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
                    return;
                }
            }

            ballEngine.startTransit(state, bestReceiver.x(), bestReceiver.y(),
                duration, bestReceiver.playerId(), carrier.teamSide());
            state.addEvent(PassEvent.completed(state.minute, state.tick,
                carrier.playerId(), carrier.name(),
                bestReceiver.playerId(), bestReceiver.name(), carrier.teamSide()));

            // Force some passes to go out of bounds for set pieces (8% base chance)
            if (RNG.nextDouble() < 0.08) {
                String defendingTeam = carrier.teamSide().equals("HOME") ? "AWAY" : "HOME";
                // Randomly choose sideline or goal line
                if (RNG.nextDouble() < 0.4) {
                    // Sideline -> throw-in
                    state.stoppage = MatchState.StoppageType.THROW_IN;
                    state.stoppageTicks = 3;
                    state.possessionTeam = defendingTeam;
                    rules.checkThrowIn(state, defendingTeam);
                    metrics.onThrowIn();
                } else {
                    // Goal line -> corner or goal kick (more goal kicks)
                    if (RNG.nextDouble() < 0.3) {
                        state.stoppage = MatchState.StoppageType.CORNER;
                        state.stoppageTicks = 5;
                        state.possessionTeam = carrier.teamSide();
                        rules.checkCorner(state, carrier.teamSide());
                        metrics.onCorner();
                    } else {
                        state.stoppage = MatchState.StoppageType.GOAL_KICK;
                        state.stoppageTicks = 5;
                        state.possessionTeam = defendingTeam;
                        metrics.onGoalKick();
                    }
                }
                ballEngine.setLoose(state, 50.0, 50.0);
                return;
            }

            // Increased chance that a nearby defender deflects the ball out-of-bounds -> restart
            int nearbyBlockers = 0;
            for (PlayerSnapshot opp : state.playerSnapshots) {
                if (opp.teamSide().equals(carrier.teamSide())) continue;
                if (opp.distanceTo(bestReceiver) < 4.0) nearbyBlockers++;
            }
            // Base 8% + 3% per nearby defender to generate more set pieces
            double outChance = 0.08 + (nearbyBlockers * 0.03);
            if (nearbyBlockers > 0 && RNG.nextDouble() < outChance) {
                // compute out coords slightly beyond field edge following pass vector
                double ox = bestReceiver.x() + (bestReceiver.x() - carrier.x()) * 0.2;
                double oy = bestReceiver.y() + (bestReceiver.y() - carrier.y()) * 0.2;
                // push beyond edges
                if (ox < MatchState.MIN_X) ox = MatchState.MIN_X - 1.0;
                if (ox > MatchState.MAX_X) ox = MatchState.MAX_X + 1.0;
                if (oy < MatchState.MIN_Y) oy = MatchState.MIN_Y - 1.0;
                if (oy > MatchState.MAX_Y) oy = MatchState.MAX_Y + 1.0;

                state.ballInTransit = false;
                state.ball = org.example.footballmanager.newLogic.model.BallState.at(ox, oy);
                state.carrierId = null;
                state.carrierTeamSide = null;
                state.lastTouchTeam = carrier.teamSide();

                // Determine side of out: goal line or sideline
                if (ox < MatchState.MIN_X || ox > MatchState.MAX_X) {
                    // treat as corner for simplicity (attacking team)
                    rules.checkCorner(state, carrier.teamSide());
                    metrics.onCorner();
                } else {
                    String defendingTeam = carrier.teamSide().equals("HOME") ? "AWAY" : "HOME";
                    rules.checkThrowIn(state, defendingTeam);
                    metrics.onThrowIn();
                }
            }

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
            state.lastPasserId = carrier.playerId(); // Track for assist attribution

            // Offside check: forward pass to a player beyond the offside line
            offside.checkOffsideOnPass(state, carrier, bestReceiver);
        }
    }

    private void executeCross(PlayerSnapshot carrier) {
        double targetX = carrier.teamSide().equals("HOME") ? 88.0 : 12.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 20.0;

        double crossQuality = (carrier.passing() + carrier.technique()) / 40.0;
        boolean accurate = RNG.nextDouble() < (0.4 + crossQuality * 0.4);

        // 15% chance cross goes out for goal kick (more realistic)
        if (RNG.nextDouble() < 0.15) {
            String defendingTeam = carrier.teamSide().equals("HOME") ? "AWAY" : "HOME";
            state.stoppage = MatchState.StoppageType.GOAL_KICK;
            state.stoppageTicks = 5;
            state.possessionTeam = defendingTeam;
            rules.checkGoalKick(state, defendingTeam);
            metrics.onGoalKick();
            ballEngine.setLoose(state, targetX, targetY);
            return;
        }

        if (accurate) {
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
        state.addEvent(new CrossEvent(state.minute, state.tick, 0,
            carrier.playerId(), carrier.name(), carrier.teamSide(),
            "cross", carrier.x(), carrier.y()));
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
            // 25% chance missed shot goes out for corner/goal kick
            if (RNG.nextDouble() < 0.25) {
                String defendingTeam = carrier.teamSide().equals("HOME") ? "AWAY" : "HOME";
                // Determine if corner or goal kick based on where shot went
                if (carrier.teamSide().equals("HOME") && carrier.x() > 90) {
                    state.stoppage = MatchState.StoppageType.CORNER;
                    state.stoppageTicks = 5;
                    state.possessionTeam = carrier.teamSide();
                    rules.checkCorner(state, carrier.teamSide());
                    metrics.onCorner();
                } else if (!carrier.teamSide().equals("HOME") && carrier.x() < 10) {
                    state.stoppage = MatchState.StoppageType.CORNER;
                    state.stoppageTicks = 5;
                    state.possessionTeam = carrier.teamSide();
                    rules.checkCorner(state, carrier.teamSide());
                    metrics.onCorner();
                } else {
                    state.stoppage = MatchState.StoppageType.GOAL_KICK;
                    state.stoppageTicks = 5;
                    state.possessionTeam = defendingTeam;
                    metrics.onGoalKick();
                }
                ballEngine.setLoose(state, 50.0, 50.0);
            } else {
                ballEngine.setLoose(state, 50.0, 50.0 + (RNG.nextDouble() - 0.5) * 20.0);
            }
        }
    }

    private void executeClearance(PlayerSnapshot carrier) {
        double clearQuality = (carrier.passing() + carrier.defending()) / 40.0;
        double targetX = carrier.teamSide().equals("HOME") ? 70.0 : 30.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 40.0;

        if (RNG.nextDouble() < clearQuality * 0.3) {
            PlayerSnapshot teammate = findBestClearanceTarget(carrier);
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

        ballEngine.startTransit(state, targetX, targetY, 30, null, carrier.teamSide());
        state.addEvent(new ClearanceEvent(state.minute, state.tick, 0,
            carrier.playerId(), carrier.name(), carrier.teamSide(),
            "clearance", carrier.x(), carrier.y()));
    }

    private void updateDuels() {
        if (state.carrierId == null) return;
        if (state.tick - state.lastDuelTick < 3) return; // shorter cooldown

        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return;

        List<PlayerSnapshot> nearbyDefenders = new ArrayList<>();
        for (PlayerSnapshot opponent : state.playerSnapshots) {
            if (opponent.teamSide().equals(carrier.teamSide())) continue;
            double dist = carrier.distanceTo(opponent);
            if (dist < 4.0) {
                nearbyDefenders.add(opponent);
            }
        }

        if (nearbyDefenders.isEmpty()) return;

        if (RNG.nextDouble() > 0.5) return; // duel triggers ~50% of the time

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
            double tackleSkill = tackler.defending() + tackler.pace();
            double foulChance = 0.45 * (1.0 - tackleSkill / 40.0); // Further increased foul chance

            if (RNG.nextDouble() < foulChance) {
                metrics.onFoul();
                rules.checkFoul(state, tackler, carrier);
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
            // Determine if it's a corner or goal kick
            boolean homeAttacking = "HOME".equals(lastTeam);
            boolean outOverHomeGoal = bx < MatchState.MIN_X;
            boolean outOverAwayGoal = bx > MatchState.MAX_X;
            
            // If attacking team touched it last over opponent's goal line = goal kick
            // If defending team touched it last over own goal line = corner
            boolean isCorner = (homeAttacking && outOverAwayGoal && defendingTeam.equals("AWAY"))
                || (!homeAttacking && outOverHomeGoal && defendingTeam.equals("HOME"));
            
            if (isCorner) {
                state.stoppage = MatchState.StoppageType.CORNER;
                state.stoppageTicks = 5;
                state.possessionTeam = lastTeam;
                rules.checkCorner(state, lastTeam);
                metrics.onCorner();
            } else {
                state.stoppage = MatchState.StoppageType.GOAL_KICK;
                state.stoppageTicks = 5;
                state.possessionTeam = defendingTeam;
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

        PlayerSnapshot closest = null;
        double minDist = 3.0;

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
            state.lastTouchTeam = closest.teamSide();
        }
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
        }
    }

    private void handleStoppageEnd() {
        MatchState.StoppageType endedStoppage = state.stoppage;
        state.stoppage = null;

        if (endedStoppage == MatchState.StoppageType.KICK_OFF) {
            startKickoffPlay();
        } else if (state.carrierId == null) {
            releaseBallAfterStoppage();
        }
    }

    private void startKickoffPlay() {
        PlayerSnapshot kickoffTaker = findKickoffTaker(state.lastTouchTeam.equals("HOME") ? "AWAY" : "HOME");
        if (kickoffTaker != null) {
            ballEngine.setCarrier(state, kickoffTaker.playerId(), kickoffTaker.teamSide());
            state.possessionTeam = kickoffTaker.teamSide();
        }
    }

    private void releaseBallAfterStoppage() {
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

        } else if (state.stoppage == MatchState.StoppageType.PENALTY) {
            state.ball = BallState.at(50.0, 50.0);
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
        List<String> homeSlots = ZonePositionCalculator.buildSlotKeys(homeConfig.formation(), state.match.homeTeam().startingXI());
        List<String> awaySlots = ZonePositionCalculator.buildSlotKeys(awayConfig.formation(), state.match.awayTeam().startingXI());

        for (int i = 0; i < state.match.homeTeam().startingXI().size(); i++) {
            Player p = state.match.homeTeam().startingXI().get(i);
            state.playerSlotKeys.put(p.id(), homeSlots.get(i));
        }
        for (int i = 0; i < state.match.awayTeam().startingXI().size(); i++) {
            Player p = state.match.awayTeam().startingXI().get(i);
            state.playerSlotKeys.put(p.id(), awaySlots.get(i));
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
