package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;

import java.util.*;

public final class MatchSimulator {

    private static final int ACTIONS_PER_MINUTE = 60;
    private static final double MIN_X = MatchState.MIN_X, MAX_X = MatchState.MAX_X;
    private static final double MIN_Y = MatchState.MIN_Y, MAX_Y = MatchState.MAX_Y;
    private static final double LOOSE_BALL_PICKUP = 1.05;
    private static final double LOOSE_BALL_STEP = 0.95;
    private static final double SHOT_TRIGGER_DISTANCE = 24.5;
    private static final double OVERLAP_DUEL_DISTANCE = 2.5;
    private static final double PRESSURE_DUEL_DISTANCE = 4.2;
    private static final int DUEL_COOLDOWN_TICKS = 5;
    private static final Random RNG = new Random();

    private MatchState state;
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
    private int totalStoppageTicks; // ticks lost to stoppages, for injury time

    public MatchResult simulate(Match match) {
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

        // Initialize player positions
        MovementEngine.initializePositions(state);
        seedPlayerSlotKeys();

        // Determine possession team (kickoff)
        state.carrierId = null;
        state.carrierTeamSide = null;
        state.possessionTeam = null;
        state.lastTouchTeam = "HOME";
        state.stoppage = MatchState.StoppageType.KICK_OFF;
        state.stoppageTicks = 6;
        state.kickoffTakerId = null;
        state.restartTakerId = null;
        state.restartTeamSide = null;
        state.restartMode = null;
        state.restartBallX = 50.0;
        state.restartBallY = 50.0;
        state.possessionAgeTicks = 0;
        state.possessionPhase = MatchState.PossessionPhase.TRANSITION;

        // Match start event
        state.addEvent(new MatchStartEvent(0, 0, match.homeTeam().name(), match.awayTeam().name()));
        state.recordTick();

        // Main loop: 90 minutes
        int firstHalfStoppageTicks = 0;
        int secondHalfStoppageTicks = 0;
        boolean firstHalfDone = false;
        for (minute = 1; minute <= 90; minute++) {
            state.minute = minute;

            for (int phase = 0; phase < ACTIONS_PER_MINUTE; phase++) {
                simulatePhase();

                updatePossessionState();

                // Handle stoppage countdown
                if (state.stoppage != null) {
                    state.stoppageTicks--;
                    totalStoppageTicks++;
                    if (state.stoppageTicks <= 0) {
                        MatchState.StoppageType endedStoppage = state.stoppage;
                        state.stoppage = null;
                        if (endedStoppage == MatchState.StoppageType.KICK_OFF) {
                            startKickoffPlay();
                        } else if (endedStoppage == MatchState.StoppageType.GOAL_KICK
                            || endedStoppage == MatchState.StoppageType.THROW_IN
                            || endedStoppage == MatchState.StoppageType.FREE_KICK) {
                            startRestartPlay();
                        } else if (endedStoppage == MatchState.StoppageType.PENALTY) {
                            resolvePenaltyShot();
                        } else if (endedStoppage == MatchState.StoppageType.CORNER) {
                            executeCornerDelivery();
                        } else if (state.carrierId == null) {
                            releaseBallAfterStoppage();
                        }
                    }
                }

                state.recordTick();
            }

            // Track half-time stoppages for injury time
            if (minute == 45 && !firstHalfDone) {
                firstHalfStoppageTicks = totalStoppageTicks;
                firstHalfDone = true;
                // Add first-half injury time
                int extraPhases = Math.min(200, totalStoppageTicks / 3);
                for (int e = 0; e < extraPhases; e++) {
                    simulatePhase();
                    // Track possession during injury time
                    updatePossessionState();
                    state.recordTick();
                }
            }

            // End-of-minute updates
            FatigueSystem.updateFatigue(state, minute);
            FatigueSystem.maybeInjury(state, minute, "HOME");
            FatigueSystem.maybeInjury(state, minute, "AWAY");
            FatigueSystem.maybeSubstitution(state, minute, "HOME");
            FatigueSystem.maybeSubstitution(state, minute, "AWAY");
        }

        // Second-half injury time
        secondHalfStoppageTicks = totalStoppageTicks - firstHalfStoppageTicks;
        int extraPhases2 = Math.min(300, secondHalfStoppageTicks / 3);
            for (int e = 0; e < extraPhases2; e++) {
                simulatePhase();
                updatePossessionState();
                state.recordTick();
            }

        // Match end
        state.addEvent(new MatchEndEvent(90, state.tick, homeGoals, awayGoals));
        state.recordTick();

        // Calculate stats
        double homePoss = calculatePossession("HOME");
        double awayPoss = calculatePossession("AWAY");
        double homeRating = calculateAvgRating("HOME");
        double awayRating = calculateAvgRating("AWAY");

        return new MatchResult(
            match.id(), homeGoals, awayGoals,
            List.copyOf(state.events), List.copyOf(state.tickHistory),
            state.tick, ACTIONS_PER_MINUTE,
            homePoss, awayPoss,
            homeShots, awayShots,
            homeShotsOnTarget, awayShotsOnTarget,
            homeFouls, awayFouls,
            homeCorners, awayCorners,
            homeYellowCards, awayYellowCards,
            homeRedCards, awayRedCards,
            homeRating, awayRating
        );
    }

    private void simulatePhase() {
        state.tick++;
        state.playerSnapshots.removeIf(s -> state.sentOffPlayers.contains(s.playerId()));

        if (state.stoppage != null) {
            if (state.stoppage == MatchState.StoppageType.GOAL_CELEBRATION
                || state.stoppage == MatchState.StoppageType.KICK_OFF) {
                if (state.stoppage == MatchState.StoppageType.KICK_OFF) {
                    stageKickoffPlayers();
                }
                MovementEngine.updateAllMovement(state);
                return;
            }
            MovementEngine.updateAllMovement(state);
            return;
        }

        if (state.ballInTransit) {
            PhysicsEngine.updateBallTransit(state);
            resolveTransit();
            MovementEngine.updateAllMovement(state);
            return;
        }

        Player carrier = state.carrierId != null ? state.playerById(state.carrierId) : null;

        if (carrier == null) {
            long chaserId = resolveLooseBall();
            if (chaserId >= 0) {
                MovementEngine.updateAllMovement(state, chaserId);
            } else {
                MovementEngine.updateAllMovement(state);
            }
            return;
        }

        // Check for defender pressure (duel)
        Player defender = findPressuringDefender(carrier);
        if (defender != null) {
            resolveDuel(carrier, defender);
            MovementEngine.updateAllMovement(state);
            return;
        }

        // Only make decision if minimum interval has passed (prevents too-frequent decisions)
        int DECISION_COOLDOWN_TICKS = 5; // ~0.5 seconds - allows build-up before next action
        if (state.tick - state.lastDecisionTick < DECISION_COOLDOWN_TICKS) {
            // Just dribble to maintain possession until next decision
            executeDribble(carrier, state.teamSideOf(carrier.id()));
            MovementEngine.updateAllMovement(state);
            return;
        }

        // Make decision
        state.lastDecisionTick = state.tick;
        var decision = DecisionEngine.decide(carrier, state);
        String team = state.teamSideOf(carrier.id());

        switch (decision.action()) {
            case PASS -> executePass(carrier, decision.targetPlayer(), team);
            case SHOT -> executeShot(carrier, team);
            case DRIBBLE -> executeDribble(carrier, team);
        }

        // Ball out of play check (carrier carried ball beyond boundary)
        var snap = state.ballCarrierSnapshot();
        if (snap != null && isOutOfBounds(snap)) {
            handleOutOfBounds();
        }

        MovementEngine.updateAllMovement(state);
    }

    private void seedPlayerSlotKeys() {
        state.playerSlotKeys.clear();
        seedSlotKeysForTeam(state.match.homeTeam().startingXI(), state.match.homeTeam().slotKeys());
        seedSlotKeysForTeam(state.match.awayTeam().startingXI(), state.match.awayTeam().slotKeys());
    }

    private void seedSlotKeysForTeam(List<Player> starters, List<String> slotKeys) {
        if (starters == null || starters.isEmpty()) return;
        List<String> fallback = List.of("GK", "DL", "DCL", "DCR", "DR", "CML", "CM", "CMR", "WL", "ST", "WR");
        List<String> effective = slotKeys != null && slotKeys.size() == starters.size() ? slotKeys : fallback;
        for (int i = 0; i < Math.min(starters.size(), effective.size()); i++) {
            state.playerSlotKeys.put(starters.get(i).id(), effective.get(i));
        }
    }

    // ─── PASS ────────────────────────────────────────────────

    private void executePass(Player passer, Player receiver, String team) {
        var passerSnap = state.snapshotById(passer.id());
        var receiverSnap = state.snapshotById(receiver.id());
        if (passerSnap == null || receiverSnap == null) {
            executeDribble(passer, team);
            return;
        }

        // Offside check — higher passer skill = better timing = less offside
        double offsideProb = 0.20
            - (passer.skills().playmaking() / 20.0) * 0.08
            - (passer.skills().passing() / 20.0) * 0.06;
        offsideProb = Math.max(0.04, Math.min(0.20, offsideProb));
        if (OffsideTracker.isOffside(state, receiverSnap) && RNG.nextDouble() < offsideProb) {
            state.addEvent(new OffsideEvent(minute, state.tick, receiver.id(), receiver.name(), team));
            state.clearPendingPass();
            state.lastTouchTeam = state.oppositeTeam(team);
            String defTeam = state.oppositeTeam(team);
            double fkX = state.ball.x();
            double fkY = state.ball.y();
            Player freeKickTaker = findOutfieldPlayer(state, defTeam);
            if (freeKickTaker != null) {
                var fkSnap = state.snapshotById(freeKickTaker.id());
                if (fkSnap != null) { fkX = fkSnap.x(); fkY = fkSnap.y(); }
            }
            state.ball = BallState.at(fkX, fkY);
            state.lastTouchTeam = defTeam;
            state.carrierId = null;
            state.stoppage = MatchState.StoppageType.FREE_KICK;
            state.stoppageTicks = 24; // Increased from 14 to give time for FK setup
            return;
        }

        // If passer is in shooting zone and the chosen receiver is not forward, prefer a shot
        boolean passerInShotZone = DecisionEngine.isInShotZone(team, passerSnap.x());
        boolean receiverForward = DecisionEngine.isForwardPass(passer, receiver, state, team);
        if (passerInShotZone && !receiverForward && RNG.nextDouble() < 0.80) {
            // convert risky backward pass in the box/shot zone into a shot most of the time
            executeShot(passer, team);
            return;
        }

        // Create pass event
        state.addEvent(PassEvent.completed(minute, state.tick, passer.id(), passer.name(),
            receiver.id(), receiver.name(), team));
        state.lastTouchTeam = team;
        state.lastPassFromId = passer.id();
        state.lastPassToId = receiver.id();
        state.lastPassTick = state.tick;

        // Start ball transit
        var transit = PhysicsEngine.calculatePassTransit(state, passer, passerSnap, receiverSnap);
        state.ballInTransit = true;
        state.transitStartX = transit.startX();
        state.transitStartY = transit.startY();
        state.transitTargetX = transit.targetX();
        state.transitTargetY = transit.targetY();
        state.transitMaxTicks = transit.maxTicks();
        state.transitMode = transit.mode();
        state.transitInterceptable = transit.interceptable();
        state.transitTicks = 0;
        state.pendingReceiverId = receiver.id();
        state.pendingPasserId = passer.id();
        state.pendingPassTeam = team;

        // Advance attacking shape
        advanceShape(passer, receiver, team);

        // GK reacts to cross — move towards landing zone
        if ("CROSS".equals(transit.mode())) {
            moveGKForCross(state.oppositeTeam(team), transit.targetX(), transit.targetY());
        }

        state.restartMode = null;
        state.restartTakerId = null;
        state.restartTeamSide = null;
    }

    private void moveGKForCross(String defTeam, double targetX, double targetY) {
        Player gk = findGoalkeeper(state, defTeam);
        if (gk == null) return;
        var gkSnap = state.snapshotById(gk.id());
        if (gkSnap == null) return;

        double distToBall = gkSnap.distanceToPoint(targetX, targetY);
        double distToGoalLine = "HOME".equals(defTeam) ? targetX : 100.0 - targetX;
        double gkSkill = gk.skills().goalkeeping() / 20.0;

        boolean shouldCome = distToGoalLine <= 22.0
            && distToBall <= 22.0
            && RNG.nextDouble() < 0.15 + gkSkill * 0.55;

        if (shouldCome) {
            int arrivalTicks = Math.max(10, Math.min(state.transitMaxTicks - 2,
                (int) Math.round(distToBall / 0.56)));
            MovementEngine.startBlend(state, gk.id(), targetX, targetY, arrivalTicks);
        }
    }

    private void resolveTransit() {
        if (!state.ballInTransit) return;

        // Check for out of bounds during transit
        if (state.ball.x() <= MIN_X - 2 || state.ball.x() >= MAX_X + 2
            || state.ball.y() <= MIN_Y - 2 || state.ball.y() >= MAX_Y + 2) {
            handleOutOfBounds();
            return;
        }

        // Interception check
        if (state.transitInterceptable && state.pendingPassTeam != null) {
            var interceptor = findInterceptor(state.pendingPassTeam);
            if (interceptor != null) {
                var intSnap = state.snapshotById(interceptor.id());
                if (intSnap != null) {
                    double intDist = intSnap.distanceToPoint(state.ball.x(), state.ball.y());
                    double intSkill = interceptor.skills().defending() / 20.0;
                    boolean inRange = intDist <= 2.6;
                    boolean luckyCut = intDist <= 3.8 && RNG.nextDouble() < 0.05 + intSkill * 0.07;
                    if (inRange || luckyCut) {
                        state.addEvent(PassEvent.intercepted(minute, state.tick,
                            state.pendingPasserId != null ? state.pendingPasserId : -1,
                            nameOf(state.pendingPasserId),
                            state.pendingReceiverId != null ? state.pendingReceiverId : -1,
                            nameOf(state.pendingReceiverId),
                            state.pendingPassTeam, interceptor.id()));
                        // Count attempted pass (intercepted)
                        if ("HOME".equals(state.pendingPassTeam)) homeTotalPasses++;
                        else awayTotalPasses++;

                        state.ballInTransit = false;
                        state.carrierId = interceptor.id();
                        state.carrierTeamSide = state.teamSideOf(interceptor.id());
                        state.lastTouchTeam = state.teamSideOf(interceptor.id());
                        state.possessionTeam = state.teamSideOf(interceptor.id());
                        state.possessionAgeTicks = 0;
                        state.possessionPhase = MatchState.PossessionPhase.TRANSITION;
                        state.clearPendingPass();
                        return;
                    }
                }
            }
        }

        // Receiver pickup
        if (state.pendingReceiverId != null) {
            var receiver = state.playerById(state.pendingReceiverId);
            if (receiver != null) {
                MovementEngine.movePlayerTowardsBall(state, receiver, LOOSE_BALL_STEP + 0.24);
                var recSnap = state.snapshotById(receiver.id());
                if (recSnap != null && recSnap.distanceToPoint(state.ball.x(), state.ball.y()) <= 0.6) {
                    // Successful pass completion
                    if ("HOME".equals(state.pendingPassTeam)) {
                        homeTotalPasses++;
                        homeSuccessfulPasses++;
                    } else {
                        awayTotalPasses++;
                        awaySuccessfulPasses++;
                    }

                    state.ballInTransit = false;
                    state.carrierId = receiver.id();
                    state.carrierTeamSide = state.teamSideOf(receiver.id());
                    state.lastTouchTeam = state.teamSideOf(receiver.id());
                    state.possessionTeam = state.teamSideOf(receiver.id());
                    state.ball = BallState.at(state.ball.x(), state.ball.y());
                    state.possessionPhase = MatchState.PossessionPhase.TRANSITION;
                    state.clearPendingPass();
                    return;
                }
            }
        }

        // Transit complete
        if (state.transitTicks >= state.transitMaxTicks) {
            state.ballInTransit = false;
            state.ball = BallState.at(state.transitTargetX, state.transitTargetY);

            // Catch the exact landing point as well; otherwise a ball can finish beyond the line
            // without ever tripping the in-flight threshold on the last interpolation step.
            if (isOutOfBounds(state.ball.x(), state.ball.y())) {
                handleOutOfBounds();
                return;
            }

            if ("CORNER".equals(state.transitMode)) {
                SetPieceHandler.resolveCornerDelivery(state, minute);
            } else if ("CROSS".equals(state.transitMode)) {
                // GK interception check for crosses
                String defTeam = state.oppositeTeam(state.pendingPassTeam);
                if (!tryGKIntercept(defTeam, state.transitTargetX, state.transitTargetY)) {
                    state.carrierId = null;
                }
            } else {
                state.carrierId = null;
            }
            // Clear pending pass regardless
            state.pendingReceiverId = null;
            state.pendingPasserId = null;
            state.pendingPassTeam = null;
            if (state.carrierId == null) {
                state.possessionAgeTicks = 0;
                state.possessionPhase = MatchState.PossessionPhase.TRANSITION;
            }
        }
    }

    private boolean tryGKIntercept(String defTeam, double ballX, double ballY) {
        Player gk = findGoalkeeper(state, defTeam);
        if (gk == null) return false;
        var gkSnap = state.snapshotById(gk.id());
        if (gkSnap == null) return false;

        double dist = gkSnap.distanceToPoint(ballX, ballY);
        double gkSkill = gk.skills().goalkeeping() / 20.0;
        boolean canReach = dist <= 2.5 + gkSkill * 1.5;
        boolean catches = RNG.nextDouble() < 0.20 + gkSkill * 0.55;

        if (canReach && catches) {
            // GK catches the cross
            state.carrierId = gk.id();
            state.carrierTeamSide = defTeam;
            state.lastTouchTeam = defTeam;
            state.possessionTeam = defTeam;
            state.ball = BallState.at(ballX, ballY);
            state.addEvent(PassEvent.intercepted(minute, state.tick,
                state.pendingPasserId != null ? state.pendingPasserId : -1, "",
                state.pendingReceiverId != null ? state.pendingReceiverId : -1, "",
                state.pendingPassTeam, gk.id()));
            return true;
        }
        return false;
    }

    // ─── SHOT ────────────────────────────────────────────────

    private void executeShot(Player shooter, String team) {
        var snap = state.snapshotById(shooter.id());
        if (snap == null) return;

        double goalX = "HOME".equals(team) ? 100.0 : 0.0;
        double goalDist = Math.sqrt(Math.pow(goalX - snap.x(), 2) + Math.pow(50.0 - snap.y(), 2));
        double angle = Math.abs(50.0 - snap.y()) / Math.max(0.8, Math.abs(goalX - snap.x()));

        String defTeam = state.oppositeTeam(team);
        Player goalkeeper = findGoalkeeper(state, defTeam);
        boolean openGoal = goalkeeper == null || !isGoalkeeperInPosition(state, goalkeeper, defTeam);

        var params = new DuelResolver.ShotParams(snap.x(), snap.y(), goalDist, angle);
        var result = openGoal
            ? DuelResolver.resolveShot(shooter, goalkeeper, params, true)
            : DuelResolver.resolveShot(shooter, goalkeeper, params, false);

        if ("HOME".equals(team)) homeShots++;
        else awayShots++;

        if (result.saved()) {
            if ("HOME".equals(team)) homeShotsOnTarget++;
            else awayShotsOnTarget++;
        }

        if (result.goal()) {
            // GOAL!
            if ("HOME".equals(team)) homeGoals++;
            else awayGoals++;

            state.addEvent(new ShotEvent(minute, state.tick, shooter.id(), shooter.name(), team,
                true, false, result.xG(), snap.x(), snap.y()));

            // Find assistant
            Long assistId = findAssistant(shooter, team);

            state.addEvent(new GoalEvent(minute, state.tick,
                shooter.id(), shooter.name(),
                assistId, assistId != null ? state.playerById(assistId).name() : null,
                team, result.xG(), homeGoals, awayGoals));

            // Kickoff restart
            String restartTeam = state.oppositeTeam(team);
            state.carrierId = null;
            state.carrierTeamSide = null;
            state.lastTouchTeam = restartTeam;
            state.stoppage = MatchState.StoppageType.GOAL_CELEBRATION;
            state.stoppageTicks = 45; // Increased from 26 - celebration + VAR check time

            // Reset positions
            MovementEngine.blendToFormation(state);

        } else if (result.saved()) {
            state.addEvent(new ShotEvent(minute, state.tick, shooter.id(), shooter.name(), team,
                true, true, result.xG(), snap.x(), snap.y()));

            // GK save -> possible corner or rebound
            if (RNG.nextDouble() < 0.24 + result.xG() * 0.28) {
                // Rebound
                double deflectX = snap.x() + (RNG.nextDouble() - 0.5) * 10;
                double deflectY = snap.y() + (RNG.nextDouble() - 0.5) * 10;
                state.ball = BallState.at(clamp(deflectX, MIN_X, MAX_X), clamp(deflectY, MIN_Y, MAX_Y));
                state.carrierId = null;
            } else if (RNG.nextDouble() < 0.58) {
                // Corner
                handleCorner();
            } else {
                // GK catches -> goal kick
                handleGoalKick();
            }
        } else {
            // Missed
            state.addEvent(new ShotEvent(minute, state.tick, shooter.id(), shooter.name(), team,
                false, false, result.xG(), snap.x(), snap.y()));

            // Goal kick
            handleGoalKick();
        }

        state.clearPendingPass();
        state.restartMode = null;
        state.restartTakerId = null;
        state.restartTeamSide = null;
    }

    // ─── DRIBBLE ─────────────────────────────────────────────

    private void executeDribble(Player dribbler, String team) {
        // Check for defender again (might have moved into range)
        Player immediateDef = findPressuringDefender(dribbler);
        if (immediateDef != null) {
            resolveDuel(dribbler, immediateDef);
            return;
        }

        MovementEngine.moveCarrierTowardsGoal(state, dribbler, 0.58);

        var snap = state.snapshotById(dribbler.id());
        if (snap != null) {
            state.ball = BallState.at(snap.x(), snap.y());
            if (isNearTouchline(snap.y()) && RNG.nextDouble() < 0.55) {
                state.lastTouchTeam = team;
                state.ball = BallState.at(
                    clamp(snap.x(), MIN_X, MAX_X),
                    snap.y() <= 50.0 ? MIN_Y - 0.6 : MAX_Y + 0.6
                );
                handleOutOfBounds();
                return;
            }
        }
        state.lastTouchTeam = team;

        // Check if in shooting range after dribble
        double goalX = "HOME".equals(team) ? 100.0 : 0.0;
        double goalDist = snap != null ? Math.sqrt(Math.pow(goalX - snap.x(), 2) + Math.pow(50.0 - snap.y(), 2)) : 30;
        if (goalDist <= SHOT_TRIGGER_DISTANCE && RNG.nextDouble() < 0.042) {
            executeShot(dribbler, team);
        }
        state.restartMode = null;
        state.restartTakerId = null;
        state.restartTeamSide = null;
    }

    // ─── DUEL ────────────────────────────────────────────────

    private void resolveDuel(Player attacker, Player defender) {
        if (attacker == null || defender == null || attacker.id() == defender.id()) return;

        String attTeam = state.teamSideOf(attacker.id());
        String defTeam = state.teamSideOf(defender.id());
        if (attTeam == null || defTeam == null || attTeam.equals(defTeam)) return;

        state.lastDuelTick = state.tick;
        var attSnap = state.snapshotById(attacker.id());

        // Foul check
        boolean inPenaltyBox = isInPenaltyBox(state, attacker);
        if (DuelResolver.isFoul(attacker, defender, inPenaltyBox)) {
            state.addEvent(new FoulEvent(minute, state.tick, defender.id(), defender.name(),
                attacker.id(), attacker.name(), defTeam, inPenaltyBox,
                state.ball.x(), state.ball.y()));

            if ("HOME".equals(attTeam)) homeFouls++;
            else awayFouls++;

            // Card check
            boolean isLastMan = isLastDefender(defender, attTeam);
            boolean dangerousFoul = inPenaltyBox || isLastMan || RNG.nextDouble() < 0.15;

            if (DuelResolver.isCardWorthy(dangerousFoul ? 0.15 : 0.02)) {
                // Check for second yellow -> red
                int existingYellows = state.playerYellowCards.getOrDefault(defender.id(), 0);
                boolean straightRed = dangerousFoul && RNG.nextDouble() < 0.40;

                if (straightRed || existingYellows >= 1) {
                    // RED CARD
                    CardEvent.CardType cardType = existingYellows >= 1
                        ? CardEvent.CardType.RED
                        : CardEvent.CardType.RED;
                    state.addEvent(new CardEvent(minute, state.tick, defender.id(), defender.name(),
                        defTeam, cardType));
                    if ("HOME".equals(defTeam)) {
                        homeYellowCards += 1;
                        homeRedCards += 1;
                    } else {
                        awayYellowCards += 1;
                        awayRedCards += 1;
                    }
                    handleRedCard(defender);
                    // Free kick / penalty after red card
                    if (inPenaltyBox) {
                        handlePenalty(attacker.id());
                    } else {
                        handleFreeKick(attacker, attTeam);
                    }
                    return;
                } else {
                    // YELLOW CARD
                    state.playerYellowCards.merge(defender.id(), 1, Integer::sum);
                    state.addEvent(new CardEvent(minute, state.tick, defender.id(), defender.name(),
                        defTeam, CardEvent.CardType.YELLOW));
                    if ("HOME".equals(defTeam)) homeYellowCards++;
                    else awayYellowCards++;
                }
            }

            if (inPenaltyBox) {
                handlePenalty(attacker.id());
            } else {
                handleFreeKick(attacker, attTeam);
            }
            return;
        }

        // Sideline pressure should occasionally turn into a throw-in instead of a recycled duel.
        if (attSnap != null && isNearTouchline(attSnap.y()) && RNG.nextDouble() < 0.50) {
            state.carrierId = null;
            state.carrierTeamSide = null;
            state.ballInTransit = false;
            state.lastTouchTeam = attTeam;
            state.ball = BallState.at(
                clamp(attSnap.x(), MIN_X, MAX_X),
                attSnap.y() <= 50.0 ? MIN_Y - 0.6 : MAX_Y + 0.6
            );
            handleOutOfBounds();
            return;
        }

        var result = DuelResolver.resolveTackle(attacker, defender);
        state.addEvent(new DuelEvent(minute, state.tick, attacker.id(), attacker.name(),
            defender.id(), defender.name(), attTeam, result.attackerWins(), "TACKLE"));

        boolean attWin = result.attackerWins();
        if (attWin) {
            state.lastTouchTeam = attTeam;
            state.possessionTeam = attTeam;
            state.carrierId = attacker.id();
            state.carrierTeamSide = attTeam;
            var snap = state.snapshotById(attacker.id());
            if (snap != null) state.ball = BallState.at(snap.x(), snap.y());
            MovementEngine.moveCarrierTowardsGoal(state, attacker, 2.4);
        } else {
            state.lastTouchTeam = defTeam;
            state.possessionTeam = defTeam;
            state.carrierId = defender.id();
            state.carrierTeamSide = defTeam;
            var snap = state.snapshotById(defender.id());
            if (snap != null) state.ball = BallState.at(snap.x(), snap.y());
            MovementEngine.moveCarrierTowardsGoal(state, defender, 2.4);
        }
    }

    private void handleRedCard(Player player) {
        String side = state.teamSideOf(player.id());
        state.sentOffPlayers.add(player.id());
        List<Player> pitchPlayers = "HOME".equals(side) ? state.match.homeTeam().startingXI() : state.match.awayTeam().startingXI();
        pitchPlayers.remove(player);
        state.playerSnapshots.removeIf(s -> s.playerId() == player.id());
        if (state.carrierId != null && state.carrierId == player.id()) {
            state.carrierId = null;
            state.carrierTeamSide = null;
        }
        if (state.restartTakerId != null && state.restartTakerId == player.id()) {
            state.restartTakerId = null;
        }
        state.blendTargets.keySet().removeIf(id -> id == player.id());
        MovementEngine.blendToFormation(state);
    }

    private boolean isLastDefender(Player defender, String attTeam) {
        String defTeam = state.oppositeTeam(attTeam);
        List<Player> defPlayers = "HOME".equals(defTeam) ? state.homePlayers() : state.awayPlayers();
        var defSnap = state.snapshotById(defender.id());
        if (defSnap == null) return false;
        double goalX = "HOME".equals(defTeam) ? 100.0 : 0.0;
        return defPlayers.stream()
            .filter(p -> p.position() != Position.GK)
            .filter(p -> p.id() != defender.id())
            .map(p -> state.snapshotById(p.id()))
            .filter(Objects::nonNull)
            .noneMatch(s -> {
                double defenderDist = Math.abs(defSnap.x() - goalX);
                double otherDist = Math.abs(s.x() - goalX);
                return otherDist < defenderDist;
            });
    }

    // ─── LOOSE BALL ──────────────────────────────────────────

    private long resolveLooseBall() {
        // Find closest player to ball
        Map.Entry<Player, Double> closest = findClosestToBall(state.ball.x(), state.ball.y());

        if (closest == null) return -1;

        Player player = closest.getKey();
        double dist = closest.getValue();

        if (dist <= LOOSE_BALL_PICKUP) {
            state.carrierId = player.id();
            state.carrierTeamSide = state.teamSideOf(player.id());
            state.possessionTeam = state.teamSideOf(player.id());
            state.lastTouchTeam = state.teamSideOf(player.id());
            state.ball = BallState.at(state.ball.x(), state.ball.y());
            state.possessionAgeTicks = 0;
            state.possessionPhase = MatchState.PossessionPhase.BUILD_UP;
            return -1;
        } else {
            MovementEngine.movePlayerTowardsBall(state, player, LOOSE_BALL_STEP);
            return player.id();
        }
    }

    // ─── SET PIECE HANDLING ──────────────────────────────────

    private void handleGoalKick() {
        var params = SetPieceHandler.handleGoalKick(state, minute);
        String restartTeam = params.teamSide();
        state.lastTouchTeam = restartTeam;
        state.ball = BallState.at(params.ballX(), params.ballY());
        state.stoppage = params.stoppage();
        state.stoppageTicks = params.pauseTicks();
        state.carrierId = null;
        state.restartMode = params.mode();
        state.restartTakerId = params.takerId();
        state.restartTeamSide = restartTeam;
        state.restartBallX = params.ballX();
        state.restartBallY = params.ballY();

        MovementEngine.blendToSetPiece(state, "GOAL_KICK", restartTeam);

        // Move GK to ball (overrides set-piece blend for GK)
        Player gk = findGoalkeeper(state, restartTeam);
        if (gk != null) {
            MovementEngine.startBlend(state, gk.id(), params.ballX(), params.ballY(), 20);
        }
    }

    private void handleCorner() {
        if ("HOME".equals(state.lastTouchTeam)) homeCorners++;
        else awayCorners++;

        var params = SetPieceHandler.handleCorner(state, minute);
        state.ball = BallState.at(params.ballX(), params.ballY());
        state.stoppage = params.stoppage();
        state.stoppageTicks = params.pauseTicks();
        state.carrierId = null;

        MovementEngine.blendToSetPiece(state, "CORNER", params.teamSide());
    }

    private void handleThrowIn() {
        var params = SetPieceHandler.handleThrowIn(state, minute);
        String restartTeam = params.teamSide();
        state.lastTouchTeam = restartTeam;
        state.ball = BallState.at(params.ballX(), params.ballY());
        state.stoppage = params.stoppage();
        state.stoppageTicks = params.pauseTicks();
        state.carrierId = null;
        state.restartMode = params.mode();
        state.restartTakerId = params.takerId();
        state.restartTeamSide = restartTeam;
        state.restartBallX = params.ballX();
        state.restartBallY = params.ballY();

        MovementEngine.blendToSetPiece(state, "THROW_IN", restartTeam);
        Player taker = state.restartTakerId != null ? state.playerById(state.restartTakerId) : null;
        if (taker != null) {
            MovementEngine.startBlend(state, taker.id(), params.ballX(), params.ballY(), 18);
        }
    }

    private void handleFreeKick(Player victim, String attackingTeam) {
        var snap = state.snapshotById(victim.id());
        double fx = snap != null ? snap.x() : state.ball.x();
        double fy = snap != null ? snap.y() : state.ball.y();

        var params = SetPieceHandler.handleFreeKick(state, minute, fx, fy, attackingTeam);
        state.lastTouchTeam = attackingTeam;
        state.ball = BallState.at(params.ballX(), params.ballY());
        state.stoppage = params.stoppage();
        state.stoppageTicks = params.pauseTicks();
        state.carrierId = null;
        state.restartMode = params.mode();
        state.restartTakerId = params.takerId();
        state.restartTeamSide = attackingTeam;
        state.restartBallX = params.ballX();
        state.restartBallY = params.ballY();

        MovementEngine.blendToSetPiece(state, "FREE_KICK", attackingTeam);
        Player taker = state.restartTakerId != null ? state.playerById(state.restartTakerId) : null;
        if (taker != null) {
            MovementEngine.startBlend(state, taker.id(), params.ballX(), params.ballY(), 18);
        }
    }

    private void handlePenalty(long fouledPlayerId) {
        var params = SetPieceHandler.handlePenalty(state, minute, fouledPlayerId);
        state.ball = BallState.at(params.ballX(), params.ballY());
        state.stoppage = params.stoppage();
        state.stoppageTicks = params.pauseTicks();
        state.carrierId = null;

        // After pause, resolve penalty
    }

    private void resolvePenaltyShot() {
        String attTeam = state.ball.x() > 50 ? "HOME" : "AWAY";
        String defTeam = state.oppositeTeam(attTeam);

        Player taker = state.pendingPenaltyTakerId != null
            ? state.playerById(state.pendingPenaltyTakerId)
            : null;
        if (taker == null) {
            taker = findOutfieldPlayer(state, attTeam);
        }
        if (taker == null) {
            taker = findOutfieldPlayer(state, "HOME");
            if (taker == null) return;
            attTeam = "HOME";
            defTeam = "AWAY";
        }
        state.pendingPenaltyTakerId = null;

        Player goalkeeper = findGoalkeeper(state, defTeam);
        if (goalkeeper == null) {
            // No GK - auto goal
            homeGoals++;
            state.addEvent(new GoalEvent(minute, state.tick, taker.id(), taker.name(), null, null, attTeam, 0.76, homeGoals, awayGoals));
            return;
        }

        var result = DuelResolver.resolvePenalty(taker, goalkeeper);

        if (result.goal()) {
            if ("HOME".equals(attTeam)) homeGoals++;
            else awayGoals++;
            state.addEvent(new GoalEvent(minute, state.tick, taker.id(), taker.name(), null, null, attTeam, result.xG(), homeGoals, awayGoals));
        } else if (result.saved()) {
            // GK saved - loose ball or clearance
            state.carrierId = null;
        } else {
            // Missed - goal kick
            handleGoalKick();
            return;
        }

        // Kickoff restart
        String restartTeam = state.oppositeTeam(attTeam);
        state.stoppage = MatchState.StoppageType.GOAL_CELEBRATION;
        state.stoppageTicks = 26;
        state.lastTouchTeam = restartTeam;
        state.carrierId = null;
        MovementEngine.blendToFormation(state);
    }

    private void handleOutOfBounds() {
        double bx = state.ball.x();
        double by = state.ball.y();

        boolean onGoalLine = bx <= MIN_X || bx >= MAX_X;
        boolean onSideline = by <= MIN_Y || by >= MAX_Y;

        if (onGoalLine) {
            boolean homeGoalSide = bx <= MIN_X;
            String defendingTeam = homeGoalSide ? "HOME" : "AWAY";
            boolean isCorner = state.lastTouchTeam != null && state.lastTouchTeam.equals(defendingTeam);

            if (isCorner) {
                handleCorner();
            } else {
                handleGoalKick();
            }
        } else if (onSideline) {
            handleThrowIn();
        }
    }

    private void releaseBallAfterStoppage() {
        if (state.restartTakerId != null) {
            Player restartTaker = state.playerById(state.restartTakerId);
            if (restartTaker != null) {
                startRestartPlay();
                return;
            }
            state.restartTakerId = null;
        }
        if (state.lastTouchTeam == null) state.lastTouchTeam = "HOME";
        Player taker = findOutfieldPlayer(state, state.lastTouchTeam);
        if (taker != null) {
            state.carrierId = taker.id();
            state.carrierTeamSide = state.lastTouchTeam;
            var snap = state.snapshotById(taker.id());
            if (snap != null) state.ball = BallState.at(snap.x(), snap.y());
        }
    }

    private void startKickoffPlay() {
        Player taker = findKickoffTaker("HOME");
        if (taker == null) {
            releaseBallAfterStoppage();
            return;
        }
        var snap = state.snapshotById(taker.id());
        if (snap == null) {
            releaseBallAfterStoppage();
            return;
        }
        state.carrierId = taker.id();
        state.carrierTeamSide = "HOME";
        state.possessionTeam = "HOME";
        state.lastTouchTeam = "HOME";
        state.ball = BallState.at(50.0, 50.0);
        state.kickoffTakerId = taker.id();
        state.restartTakerId = taker.id();
        state.restartTeamSide = "HOME";
        state.restartMode = "KICK_OFF";
        state.restartBallX = 50.0;
        state.restartBallY = 50.0;
        state.possessionAgeTicks = 0;
        state.possessionPhase = MatchState.PossessionPhase.BUILD_UP;
    }

    private void stageKickoffPlayers() {
        if (state.kickoffTakerId == null) {
            state.kickoffTakerId = findKickoffTaker("HOME") != null ? findKickoffTaker("HOME").id() : null;
        }
        if (state.kickoffTakerId != null) {
            Player taker = state.playerById(state.kickoffTakerId);
            if (taker != null) {
                MovementEngine.movePlayerTowards(state, taker, 50.0, 50.0, 1.8);
            }
        }
    }

    private void startRestartPlay() {
        if (state.restartTakerId == null) {
            releaseBallAfterStoppage();
            return;
        }
        Player taker = state.playerById(state.restartTakerId);
        if (taker == null) {
            state.restartTakerId = null;
            releaseBallAfterStoppage();
            return;
        }
        state.carrierId = taker.id();
        state.carrierTeamSide = state.restartTeamSide != null ? state.restartTeamSide : state.teamSideOf(taker.id());
        state.possessionTeam = state.carrierTeamSide;
        state.lastTouchTeam = state.carrierTeamSide;
        state.ball = BallState.at(state.restartBallX, state.restartBallY);
        state.possessionAgeTicks = 0;
        state.possessionPhase = MatchState.PossessionPhase.BUILD_UP;
    }

    private void executeCornerDelivery() {
        String attackingTeam = state.lastTouchTeam;
        if (attackingTeam == null) { releaseBallAfterStoppage(); return; }
        String defendingTeam = state.oppositeTeam(attackingTeam);

        double targetX = "HOME".equals(attackingTeam) ? 85.0 : 15.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 28.0;

        Player taker = findOutfieldPlayer(state, attackingTeam);
        if (taker == null) { releaseBallAfterStoppage(); return; }

        // Move GK towards box target during corner delivery
        moveGKForCross(defendingTeam, targetX, targetY);

        // Start transit from corner flag to box target
        state.ballInTransit = true;
        state.transitStartX = state.ball.x();
        state.transitStartY = state.ball.y();
        state.transitTargetX = targetX;
        state.transitTargetY = targetY;
        state.transitMaxTicks = 8 + RNG.nextInt(4);
        state.transitTicks = 0;
        state.transitMode = "CORNER";
        state.transitInterceptable = false;
        state.pendingReceiverId = null;
        state.pendingPasserId = null;
        state.pendingPassTeam = null;
    }

    // ─── HELPERS ─────────────────────────────────────────────

    private Player findPressuringDefender(Player attacker) {
        if (state.tick - state.lastDuelTick < DUEL_COOLDOWN_TICKS) return null;

        String attTeam = state.teamSideOf(attacker.id());
        String defTeam = state.oppositeTeam(attTeam);
        List<Player> defenders = "HOME".equals(defTeam) ? state.homePlayers() : state.awayPlayers();
        var attSnap = state.snapshotById(attacker.id());
        if (attSnap == null) return null;

        Player closest = defenders.stream()
            .filter(p -> p.id() != attacker.id())
            .filter(p -> !attTeam.equals(state.teamSideOf(p.id())))
            .filter(p -> p.position() != Position.GK)
            .filter(p -> {
                var s = state.snapshotById(p.id());
                return s != null && attSnap.distanceTo(s) <= PRESSURE_DUEL_DISTANCE;
            })
            .min(Comparator.comparingDouble(p -> {
                var s = state.snapshotById(p.id());
                return s != null ? attSnap.distanceTo(s) : Double.MAX_VALUE;
            }))
            .orElse(null);

        if (closest == null) return null;

        var defSnap = state.snapshotById(closest.id());
        if (defSnap == null) return null;
        double dist = attSnap.distanceTo(defSnap);
        if (dist <= OVERLAP_DUEL_DISTANCE) return closest;

        double closeChance = 0.10 + (PRESSURE_DUEL_DISTANCE - dist) / (PRESSURE_DUEL_DISTANCE - OVERLAP_DUEL_DISTANCE) * 0.32;
        return RNG.nextDouble() < closeChance ? closest : null;
    }

    private Player findImmediateDefender(Player attacker) {
        String attTeam = state.teamSideOf(attacker.id());
        String defTeam = state.oppositeTeam(attTeam);
        List<Player> defenders = "HOME".equals(defTeam) ? state.homePlayers() : state.awayPlayers();
        var attSnap = state.snapshotById(attacker.id());
        if (attSnap == null) return null;

        return defenders.stream()
            .filter(p -> p.position() != Position.GK)
            .filter(p -> {
                var s = state.snapshotById(p.id());
                return s != null && attSnap.distanceTo(s) <= OVERLAP_DUEL_DISTANCE;
            })
            .min(Comparator.comparingDouble(p -> {
                var s = state.snapshotById(p.id());
                return s != null ? attSnap.distanceTo(s) : Double.MAX_VALUE;
            }))
            .orElse(null);
    }

    private Player findGoalkeeper(MatchState state, String team) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream().filter(p -> p.position() == Position.GK).findFirst().orElse(null);
    }

    private Player findOutfieldPlayer(MatchState state, String team) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream().filter(p -> p.position() != Position.GK).findFirst().orElse(null);
    }

    private Player findKickoffTaker(String team) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream()
            .filter(p -> p.position() == Position.MID || p.position() == Position.ATT)
            .min(Comparator.comparingDouble(p -> {
                var snap = state.snapshotById(p.id());
                return snap != null ? snap.distanceToPoint(50.0, 50.0) : Double.MAX_VALUE;
            }))
            .orElseGet(() -> players.stream().filter(p -> p.position() != Position.GK).findFirst().orElse(null));
    }

    // Find interceptor (defender near ball path)
    private Player findInterceptor(String passTeam) {
        String defTeam = state.oppositeTeam(passTeam);
        List<Player> defenders = "HOME".equals(defTeam) ? state.homePlayers() : state.awayPlayers();
        return defenders.stream()
            .filter(p -> p.position() != Position.GK)
            .filter(p -> {
                var s = state.snapshotById(p.id());
                return s != null && s.distanceToPoint(state.ball.x(), state.ball.y()) <= 2.8;
            })
            .min(Comparator.comparingDouble(p -> {
                var s = state.snapshotById(p.id());
                return s != null ? s.distanceToPoint(state.ball.x(), state.ball.y()) : Double.MAX_VALUE;
            }))
            .orElse(null);
    }

    private Map.Entry<Player, Double> findClosestToBall(double bx, double by) {
        return java.util.stream.Stream.concat(state.homePlayers().stream(), state.awayPlayers().stream())
            .filter(p -> p.position() != Position.GK || isBallInsidePenaltyArea(bx, by, p))
            .map(p -> {
                var s = state.snapshotById(p.id());
                double d = s != null ? s.distanceToPoint(bx, by) : Double.MAX_VALUE;
                return Map.entry(p, d);
            })
            .filter(e -> e.getValue() < Double.MAX_VALUE)
            .min(Map.Entry.comparingByValue())
            .orElse(null);
    }

    private boolean isBallInsidePenaltyArea(double bx, double by, Player gk) {
        boolean home = "HOME".equals(state.teamSideOf(gk.id()));
        double minX = home ? 0 : 84.0, maxX = home ? 16.0 : 100.0;
        return bx >= minX && bx <= maxX && by >= 22.0 && by <= 78.0;
    }

    private boolean isGoalkeeperInPosition(MatchState state, Player gk, String team) {
        var snap = state.snapshotById(gk.id());
        if (snap == null) return false;
        boolean home = "HOME".equals(team);
        double minX = home ? 1.5 : 86.5, maxX = home ? 13.5 : 98.5;
        return snap.x() >= minX && snap.x() <= maxX && snap.y() >= 36.0 && snap.y() <= 64.0;
    }

    private boolean isInPenaltyBox(MatchState state, Player player) {
        var snap = state.snapshotById(player.id());
        if (snap == null) return false;
        String team = state.teamSideOf(player.id());
        boolean homeAttack = "HOME".equals(team);
        return homeAttack
            ? snap.x() >= 80.0 && snap.y() >= 22.0 && snap.y() <= 78.0
            : snap.x() <= 20.0 && snap.y() >= 22.0 && snap.y() <= 78.0;
    }

    private boolean isOutOfBounds(PlayerSnapshot snap) {
        return snap.x() < MIN_X || snap.x() > MAX_X
            || snap.y() < MIN_Y || snap.y() > MAX_Y;
    }

    private boolean isOutOfBounds(double x, double y) {
        return x < MIN_X || x > MAX_X || y < MIN_Y || y > MAX_Y;
    }

    private boolean isNearTouchline(double y) {
        return y <= MIN_Y + 15.0 || y >= MAX_Y - 15.0;
    }

    private Long findAssistant(Player scorer, String team) {
        // If we have a recent pass to the scorer, that passer gets the assist
        if (state.lastPassToId != null && state.lastPassToId == scorer.id()
            && state.tick - state.lastPassTick <= 15) {
            return state.lastPassFromId;
        }
        return null;
    }

    private void advanceShape(Player passer, Player receiver, String team) {
        var passerSnap = state.snapshotById(passer.id());
        if (passerSnap != null) {
            double retreatX = "HOME".equals(team)
                ? Math.max(8.0, passerSnap.x() - 2.5)
                : Math.min(92.0, passerSnap.x() + 2.5);
            double retreatY = clamp(passerSnap.y() + ((passer.id() % 2 == 0) ? -1.8 : 1.8), MIN_Y, MAX_Y);
            MovementEngine.movePlayerTowards(state, passer, retreatX, retreatY, 0.95);
        }
        var receiverSnap = state.snapshotById(receiver.id());
        if (receiverSnap != null) {
            double supportX = "HOME".equals(team)
                ? Math.min(92.0, receiverSnap.x() + 2.0)
                : Math.max(8.0, receiverSnap.x() - 2.0);
            double supportY = clamp(receiverSnap.y() + ((receiver.id() % 2 == 0) ? 1.2 : -1.2), MIN_Y, MAX_Y);
            MovementEngine.movePlayerTowards(state, receiver, supportX, supportY, 0.72);
        }
    }

    private void updatePossessionState() {
        boolean activePossession = state.carrierId != null || (state.ballInTransit && state.pendingPassTeam != null);
        if (!activePossession) {
            state.possessionAgeTicks = 0;
            state.possessionPhase = MatchState.PossessionPhase.TRANSITION;
            return;
        }

        state.possessionAgeTicks = Math.min(30, state.possessionAgeTicks + 1);

        String team = state.carrierTeamSide != null ? state.carrierTeamSide : state.pendingPassTeam;
        Player carrier = state.carrierId != null ? state.playerById(state.carrierId) : null;
        PlayerSnapshot carrierSnap = carrier != null ? state.snapshotById(carrier.id()) : null;
        double bx = carrierSnap != null ? carrierSnap.x() : state.ball.x();
        double by = carrierSnap != null ? carrierSnap.y() : state.ball.y();
        double goalDist = carrier != null && team != null ? DecisionEngine.estimateGoalDistance(carrier, state, team) : 999.0;

        if (state.ballInTransit) {
            state.possessionPhase = MatchState.PossessionPhase.TRANSITION;
        } else if (goalDist <= 16.0 || (team != null && DecisionEngine.isInShotZone(team, bx))) {
            state.possessionPhase = MatchState.PossessionPhase.BOX_CHAOS;
        } else if (team != null && (("HOME".equals(team) && bx >= 66.0) || ("AWAY".equals(team) && bx <= 34.0))) {
            state.possessionPhase = MatchState.PossessionPhase.FINAL_THIRD;
        } else if (team != null && (("HOME".equals(team) && bx >= 52.0) || ("AWAY".equals(team) && bx <= 48.0))) {
            state.possessionPhase = MatchState.PossessionPhase.PROGRESSION;
        } else {
            state.possessionPhase = MatchState.PossessionPhase.BUILD_UP;
        }
    }

    private double calculatePossession(String team) {
        int total = state.homePossessionTicks + state.awayPossessionTicks;
        if (total == 0) return 50.0;
        int teamTicks = "HOME".equals(team) ? state.homePossessionTicks : state.awayPossessionTicks;
        return (double) teamTicks / total * 100.0;
    }

    private double calculateAvgRating(String team) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream()
            .mapToInt(p -> (p.skills().pace() + p.skills().shooting() + p.skills().passing()
                + p.skills().technique() + p.skills().defending() + p.skills().playmaking()
                + p.skills().goalkeeping() + p.skills().stamina()) / 8)
            .average().orElse(5.0);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private String nameOf(Long playerId) {
        if (playerId == null) return "?";
        Player player = state.playerById(playerId);
        return player != null ? player.name() : "?";
    }
}
