package org.example.footballmanager.newLogic.engine;

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
    }

    private void initializeMatchState(Match match) {
        this.state = new MatchState(match);
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

        removeSentOffPlayers();
        checkBallOutOfBounds();

        zones.updateTargets(state, state.match.homeTeam().tacticRules(), state.match.awayTeam().tacticRules());
        awareness.update(state);
        intents.update(state, awareness);
        coordination.update(state, intents, awareness);
        updateMovement();

        if (state.carrierId != null && !state.ballInTransit) {
            updateBallCarrierDecision();
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

        DecisionEngine.BallAction action = decisions.decide(state, carrier, awareness);
        state.lastDecisionTick = state.tick;

        executeAction(carrier, action);
    }

    private void executeAction(PlayerSnapshot carrier, DecisionEngine.BallAction action) {
        switch (action) {
            case CARRY -> {}
            case SHORT_PASS -> executePass(carrier, 15.0);
            case LONG_PASS -> executePass(carrier, 35.0);
            case CROSS -> executeCross(carrier);
            case SHOOT -> executeShot(carrier);
            case DRIBBLE -> {}
            case THROUGH_PASS -> executePass(carrier, 25.0);
            case CLEAR -> executeClearance(carrier);
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
            double interceptChance = 0.05 * (1.0 - passQuality);
            boolean intercepted = RNG.nextDouble() < interceptChance;

            int duration = (int) (carrier.distanceTo(bestReceiver) / 0.5);

            if (intercepted) {
                PlayerSnapshot interceptor = findNearestOpponent(bestReceiver, carrier.teamSide());
                if (interceptor != null) {
                    ballEngine.startTransit(state, interceptor.x(), interceptor.y(),
                        duration / 2, interceptor.playerId(), interceptor.teamSide());
                    state.addEvent(new PassInterceptedEvent(state.minute, state.tick,
                        state.possessionChainId,
                        carrier.playerId(), carrier.name(),
                        interceptor.playerId(), interceptor.name(), interceptor.teamSide(),
                        "intercepted", interceptor.x(), interceptor.y()));
                    return;
                }
            }

            ballEngine.startTransit(state, bestReceiver.x(), bestReceiver.y(),
                duration, bestReceiver.playerId(), carrier.teamSide());
            state.addEvent(PassEvent.completed(state.minute, state.tick,
                carrier.playerId(), carrier.name(),
                bestReceiver.playerId(), bestReceiver.name(), carrier.teamSide()));
            if (carrier.teamSide().equals("HOME")) {
                homeSuccessfulPasses++;
                homeTotalPasses++;
            } else {
                awaySuccessfulPasses++;
                awayTotalPasses++;
            }
        }
    }

    private void executeCross(PlayerSnapshot carrier) {
        double targetX = carrier.teamSide().equals("HOME") ? 88.0 : 12.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 20.0;

        double crossQuality = (carrier.passing() + carrier.technique()) / 40.0;
        boolean accurate = RNG.nextDouble() < (0.4 + crossQuality * 0.4);

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

        ballEngine.startTransit(state, targetX, targetY, 40, null, carrier.teamSide());
        state.addEvent(new CrossEvent(state.minute, state.tick, 0,
            carrier.playerId(), carrier.name(), carrier.teamSide(),
            "cross", carrier.x(), carrier.y()));
    }

    private void executeShot(PlayerSnapshot carrier) {
        double distToGoal = distanceToGoal(carrier);
        double xG = calculateXG(distToGoal);

        double shootQuality = (carrier.shooting() + carrier.technique()) / 40.0;
        xG *= (0.7 + shootQuality * 0.6);

        PlayerSnapshot gk = findGkOnPitch(carrier.teamSide().equals("HOME") ? "AWAY" : "HOME");
        DuelResolver.DuelResult result;
        if (gk != null) {
            result = duels.resolveShotDuel(carrier, gk, distToGoal, xG);
        } else {
            result = duels.resolveOpenGoalShot(carrier, distToGoal, xG);
        }

        if (carrier.teamSide().equals("HOME")) homeShots++;
        else awayShots++;

        boolean onTarget = "GOAL".equals(result.resultType()) || "SAVED".equals(result.resultType());
        if (onTarget) {
            if (carrier.teamSide().equals("HOME")) homeShotsOnTarget++;
            else awayShotsOnTarget++;
        }

        if (result.goal()) {
            if (carrier.teamSide().equals("HOME")) homeGoals++;
            else awayGoals++;

            state.addEvent(new GoalEvent(state.minute, state.tick,
                carrier.playerId(), carrier.name(), null, null,
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
            ballEngine.setLoose(state, 50.0, 50.0 + (RNG.nextDouble() - 0.5) * 20.0);
        }
    }

    private void executeClearance(PlayerSnapshot carrier) {
        double clearQuality = (carrier.passing() + carrier.defending()) / 40.0;
        double targetX = carrier.teamSide().equals("HOME") ? 70.0 : 30.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 40.0;

        if (RNG.nextDouble() < clearQuality * 0.3) {
            PlayerSnapshot teammate = findBestClearanceTarget(carrier);
            if (teammate != null) {
                int duration = (int) (carrier.distanceTo(teammate) / 0.5);
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
        if (state.tick - state.lastDuelTick < 15) return;

        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return;

        List<PlayerSnapshot> nearbyDefenders = new ArrayList<>();
        for (PlayerSnapshot opponent : state.playerSnapshots) {
            if (opponent.teamSide().equals(carrier.teamSide())) continue;
            double dist = carrier.distanceTo(opponent);
            if (dist < 2.0) {
                nearbyDefenders.add(opponent);
            }
        }

        if (nearbyDefenders.isEmpty()) return;

        if (RNG.nextDouble() > 0.06) return;

        state.lastDuelTick = state.tick;

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
            double foulChance = 0.15 * (1.0 - tackleSkill / 40.0);

            if (RNG.nextDouble() < foulChance) {
                rules.checkFoul(state, tackler, carrier);
            } else {
                ballEngine.setCarrier(state, tackler.playerId(), tackler.teamSide());
                state.possessionTeam = tackler.teamSide();
                state.addEvent(new TackleEvent(state.minute, state.tick, 0,
                    tackler.playerId(), tackler.name(), tackler.teamSide(),
                    carrier.playerId(), carrier.name(), true,
                    "tackle", tackler.x(), tackler.y()));
            }
        }
    }

    private void checkBallOutOfBounds() {
        if (state.ballInTransit) return;
        if (state.stoppage != null) return;

        double bx = state.ball.x();
        double by = state.ball.y();

        boolean outOfBounds = bx < MatchState.MIN_X || bx > MatchState.MAX_X
            || by < MatchState.MIN_Y || by > MatchState.MAX_Y;

        if (!outOfBounds) return;

        String lastTeam = state.lastTouchTeam;
        if (lastTeam == null) lastTeam = "HOME";
        String defendingTeam = "HOME".equals(lastTeam) ? "AWAY" : "HOME";
        String attackingTeam = "HOME".equals(lastTeam) ? "HOME" : "AWAY";

        if (bx < MatchState.MIN_X || bx > MatchState.MAX_X) {
            state.stoppage = MatchState.StoppageType.CORNER;
            state.stoppageTicks = 5;
            rules.checkCorner(state, attackingTeam);
        } else {
            state.stoppage = MatchState.StoppageType.THROW_IN;
            state.stoppageTicks = 3;
            rules.checkThrowIn(state, defendingTeam);
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
        if (distToGoal < 8.0) return 0.35;
        if (distToGoal < 14.0) return 0.22;
        if (distToGoal < 20.0) return 0.12;
        if (distToGoal < 28.0) return 0.05;
        return 0.02;
    }
}
