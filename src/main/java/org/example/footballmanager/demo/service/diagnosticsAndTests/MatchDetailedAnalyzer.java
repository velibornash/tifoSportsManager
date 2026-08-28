package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.engine.*;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.result.*;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

import java.util.*;

/**
 * Comprehensive action-by-action match analyzer.
 * At each decision point captures full pitch state, all options with scores,
 * distances, lane clearance, offside status, shot zone analysis.
 */
public class MatchDetailedAnalyzer {

    private static final int ANALYSIS_MINUTES = 10;
    private static final int TICKS_PER_MINUTE = 120;

    private FatigueService fatigueService;

    public static void main(String[] args) {
        long seed = 42L;
        if (args.length > 0) {
            try { seed = Long.parseLong(args[0]); } catch (NumberFormatException ignored) {}
        }
        new MatchDetailedAnalyzer().run(seed);
    }

    public void run(long seed) {
        Random random = new Random(seed);
        List<Player> homePlayers = MatchSimulationController.generateTeam("HOME", "Home");
        List<Player> awayPlayers = MatchSimulationController.generateTeam("AWAY", "Away");
        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);

        Position kickoffPos = new Position(4, 3.5);
        Ball ball = new Ball(kickoffPos, kickoffPos);
        TacticsRules tactics = new TacticsRules();
        MatchRecorder recorder = new MatchRecorder();
        MatchState state = new MatchState(allPlayers, ball, tactics, random, recorder);
        this.fatigueService = new FatigueService(state);

        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);
        ExecutionQuality execQuality = new ExecutionQuality(random);
        ActionEngine actionEngine = new ActionEngine(state, selection, execQuality, recorder);
        MovementEngine movementEngine = new MovementEngine(state);
        BallMovementEngine ballMovementEngine = new BallMovementEngine(state);
        ActionLogService logger = new ActionLogService();
        TacticalIntentEngine tacticalEngine = new TacticalIntentEngine(state, logger);
        ThreatAssessmentService threatService = new ThreatAssessmentService(state);
        PlayerPerceptionService perceptionService = new PlayerPerceptionService(state);
        FootballRulesService rulesService = new FootballRulesService(state);
        DuelEngine duelEngine = new DuelEngine(state, new DuelResolver(random), recorder);
        PlaymakingDecisionEngine decisionEngine = new PlaymakingDecisionEngine(
                state, selection, threatService, perceptionService, random);

        state.startMatchSimulation();
        state.setPhase(MatchPhase.KICK_OFF);
        state.setKickoffTeam("HOME");
        state.setKickoffPending(true);

        int cutoffTicks = ANALYSIS_MINUTES * TICKS_PER_MINUTE;
        int totalTicks = 0;
        int decisionCount = 0;

        System.out.println("=== MATCH ACTION-BY-ACTION ANALYSIS (10 minutes) ===");
        System.out.println("Seed: " + seed);
        System.out.println();

        while (totalTicks < cutoffTicks && !state.isMatchFinished()) {
            if (state.isHalfTime()) {
                state.startSecondHalf();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }
            if (state.isKickoffPending()) {
                handleKickoff(state, actionEngine, selection, homePlayers, awayPlayers, logger);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }
            if (state.isCelebrating()) {
                recorder.captureSnapshot(state);
                state.setCelebrating(false);
                state.setKickoffPending(true);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }
            if (state.getActionDelayTicks() > 0) {
                state.consumeActionDelayTick();
                ballMovementEngine.moveBallTowardCurrentTarget();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }
            if (state.getCornerHoldTicks() > 0) {
                state.consumeCornerHoldTick();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }
            if (state.getRestartHoldTicks() > 0) {
                state.consumeRestartHoldTick();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            if (!state.hasActiveAction()) {
                if (state.getCarrier() != null) {
                    Player carrier = state.getCarrier();
                    String team = carrier.getTeam();
                    String minute = formatMinute(state.getMatchTicks());

                    state.beginRound();
                    tacticalEngine.assignTargets();

                    decisionCount++;
                    printHeader(decisionCount, minute, carrier, team, state);

                    DecisionOption chosen = decisionEngine.decide();
                    List<DecisionOption> allOptions = decisionEngine.getLastScoredOptions();

                    printOptions(allOptions, chosen, carrier, state, rulesService);

                    System.out.printf("  >>> CHOSEN: %s (score=%.3f)%n",
                            chosen.getType(), chosen.getScore());
                    System.out.println();

                    executeDecision(chosen.getType(), chosen, state, actionEngine, selection,
                            decisionEngine, logger, rulesService);

                    state.setKickoffActionPending(false);
                    state.setSetPiecePending(false);

                    boolean isThruPass = state.hasActiveAction()
                            && state.getAction().getPassLength() == PassLength.THRU
                            && state.getAction().isPassInFlight();
                    if (!isThruPass) {
                        duelEngine.update(state.getAction());
                        if (duelEngine.getActiveDuelAttacker() != null) {
                            Player da = duelEngine.getActiveDuelAttacker();
                            Player dd = duelEngine.getActiveDuelDefender();
                            DuelType dt = duelEngine.getActiveDuelType();
                            DuelResolver.DuelResult dr = duelEngine.resolveActiveDuel(state.getAction());
                            duelEngine.closeAfterResolution();
                            if (dr != null) {
                                System.out.printf("  DUEL %s: %s (%d) vs %s (%d) -> %s [%s]%n",
                                        dt, da.getLabel(), dr.attackerPower(),
                                        dd.getLabel(), dr.defenderPower(),
                                        dr.winner().getLabel(), dr.outcome());
                            }
                        }
                    }

                } else if (ball.getTarget() != null) {
                    ballMovementEngine.moveBallTowardCurrentTarget();
                } else {
                    handleLooseBall(state, actionEngine, selection, logger);
                }
            } else {
                Action action = state.getAction();
                if (action.isPassInFlight() || action.isCrossInFlight()) {
                    handleInFlightArrival(state, actionEngine, logger, rulesService);
                } else if (action.isShotInFlight()) {
                    handleShotArrival(state, actionEngine, logger, rulesService);
                } else {
                    // Refresh tactical targets when ball crossed a new grid cell
                    // (corePrinciples §19 step 3 — same fix as MatchSimulator)
                    tacticalEngine.refreshTargetsIfBallStateChanged();
                    ballMovementEngine.followCarrier();
                }
            }

            // Movement — ALWAYS called, same as MatchSimulator
            movementEngine.moveAllTowardTargets();
            fatigueService.updateAll();

            totalTicks++;
            state.advanceMatchClock();
            state.advanceSimulationTick();
        }

        System.out.println("=== END OF ANALYSIS ===");
        System.out.println("Decisions analyzed: " + decisionCount);
        System.out.println("Score: " + state.getGoalCount() + "-" + state.getAwayGoalCount());
    }

    private void printHeader(int num, String minute, Player carrier, String team, MatchState state) {
        System.out.println("------------------------------------------------------------");
        System.out.printf("#%d [%s] %s (%s) has the ball%n", num, minute, carrier.getLabel(), team);
        System.out.printf("   Ball: (%.1f, %.1f) | Phase: %s | Fatigue: %.0f%% | Skills: PM=%d PASS=%d STR=%d%n",
                state.getBall().getPosition().getRow(),
                state.getBall().getPosition().getColumn(),
                state.getPhase(),
                carrier.getFatigue() * 100,
                (int) carrier.getSkills().playmaking(),
                (int) carrier.getSkills().passing(),
                (int) carrier.getSkills().striker());

        // Print simplified pitch
        System.out.println();
        System.out.println("   POSITIONS (HOME=UPPER, away=lower, *=carrier):");
        for (int row = 7; row >= 0; row--) {
            StringBuilder sb = new StringBuilder("   ");
            sb.append(String.format("R%d ", row));
            for (int col = 0; col <= 6; col++) {
                String slot = " .  ";
                for (Player p : state.getPlayers()) {
                    if (p.isUnavailable()) continue;
                    if (Math.abs(p.getPosition().getRow() - row) < 0.5
                            && Math.abs(p.getPosition().getColumn() - col) < 0.5) {
                        String n = p.getLabel();
                        if (n.length() > 4) n = n.substring(0, 4);
                        String prefix = p == carrier ? "*" : "";
                        if ("HOME".equals(p.getTeam())) {
                            slot = prefix + String.format("%-4s", n.toUpperCase());
                        } else {
                            slot = prefix + String.format("%-4s", n.toLowerCase());
                        }
                        break;
                    }
                }
                sb.append(slot).append(" ");
            }
            System.out.println(sb);
        }
        System.out.println();
    }

    private void printOptions(List<DecisionOption> options, DecisionOption chosen,
                               Player carrier, MatchState state, FootballRulesService rulesService) {
        System.out.println("   OPTIONS:");
        for (DecisionOption opt : options) {
            String targetName = opt.getTarget() != null ? opt.getTarget().getLabel() : "---";
            String marker = opt == chosen ? " <<<" : "";
            System.out.printf("     %-8s %6.2f  %s  [%s]%s%n",
                    opt.getType(), opt.getScore(), targetName, opt.getReason(), marker);

            if (opt.getType() == DecisionType.PASS && opt.getTarget() != null) {
                Player receiver = opt.getTarget();
                double dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
                boolean clearLane = isPassingLaneClear(carrier, receiver, state);
                int defendersAhead = countNonGkDefendersAhead(receiver, state);
                int skill = Math.max(1, Math.min(20, (int) Math.round(carrier.getSkills().passing())));
                double maxDev = (20 - skill) * 0.10 * 0.6;
                boolean offside = rulesService.isOffside(receiver, carrier.getPosition(),
                        state.getBall().getPosition());
                System.out.printf("       dist=%.2f | lane=%s | defendersAhead=%d | offside=%s | passSkill=%d | maxDev=%.2f%n",
                        dist, clearLane ? "CLEAR" : "BLOCKED",
                        defendersAhead, offside ? "YES" : "no", skill, maxDev);
            }

            if (opt.getType() == DecisionType.THRU && opt.getTarget() != null) {
                Player runner = opt.getTarget();
                double dist = SimUtils.distance(carrier.getPosition(), runner.getPosition());
                int defendersAhead = countNonGkDefendersAhead(runner, state);
                System.out.printf("       dist=%.2f | defendersAhead=%d | pace=%d%n",
                        dist, defendersAhead, (int) runner.getSkills().pace());
            }

            if (opt.getType() == DecisionType.SHOT) {
                Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
                double distToGoal = SimUtils.distance(carrier.getPosition(), goal);
                int striker = (int) Math.round(carrier.getSkills().striker());
                double maxShotDev = (20 - striker) * 0.22;
                System.out.printf("       distToGoal=%.2f | striker=%d | maxDev=%.2f%n",
                        distToGoal, striker, maxShotDev);
            }

            if (opt.getType() == DecisionType.CARRY) {
                double openSpace = openSpaceAround(carrier, state);
                System.out.printf("       openSpace=%.1f | technique=%d | fatigue=%.0f%%%n",
                        openSpace, (int) carrier.getSkills().technique(),
                        carrier.getFatigue() * 100);
            }
        }
    }

    private boolean isPassingLaneClear(Player carrier, Player receiver, MatchState state) {
        Position a = carrier.getPosition();
        Position b = receiver.getPosition();
        for (Player p : state.getPlayers()) {
            if (p == carrier || p == receiver) continue;
            if (!p.getTeam().equals(carrier.getTeam())) {
                double dist = pointToLineDistance(p.getPosition(), a, b);
                if (dist < 0.8) return false;
            }
        }
        return true;
    }

    private int countNonGkDefendersAhead(Player receiver, MatchState state) {
        boolean home = "HOME".equals(receiver.getTeam());
        int count = 0;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(receiver.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            boolean goalSide = home
                    ? p.getPosition().getRow() >= receiver.getPosition().getRow()
                    : p.getPosition().getRow() <= receiver.getPosition().getRow();
            if (goalSide) count++;
        }
        return count;
    }

    private double openSpaceAround(Player carrier, MatchState state) {
        double minDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p == carrier) continue;
            double dist = SimUtils.distance(carrier.getPosition(), p.getPosition());
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 40;
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }

    private static double pointToLineDistance(Position p, Position a, Position b) {
        double dx = b.getColumn() - a.getColumn();
        double dy = b.getRow() - a.getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) return SimUtils.distance(p, a);
        double t = ((p.getColumn() - a.getColumn()) * dx + (p.getRow() - a.getRow()) * dy) / (len * len);
        t = Math.max(0, Math.min(1, t));
        double projX = a.getColumn() + t * dx;
        double projY = a.getRow() + t * dy;
        return SimUtils.distance(p, new Position(projY, projX));
    }

    private String formatMinute(int ticks) {
        int minute = ticks / TICKS_PER_MINUTE;
        int second = (int) Math.round((ticks % TICKS_PER_MINUTE) * 60.0 / TICKS_PER_MINUTE);
        return minute + ":" + String.format("%02d", second);
    }

    private void handleKickoff(MatchState state, ActionEngine actionEngine,
                               PlayerSelectionEngine selection,
                               List<Player> homePlayers, List<Player> awayPlayers,
                               ActionLogService logger) {
        String team = state.getKickoffTeam();
        List<Player> teamPlayers = "HOME".equals(team) ? homePlayers : awayPlayers;
        Player kicker = teamPlayers.get(1);
        state.setCarrier(kicker);
        state.getBall().setCarrier(kicker);
        state.setKickoffPending(false);
        state.setKickoffActionPending(true);
        state.setPhase(MatchPhase.OPEN_PLAY);
        state.incrementRound();
        Position kickoffPos = new Position(4, 3.5);
        kicker.setPosition(kickoffPos);
        state.getBall().setPosition(kickoffPos);
        for (Player p : state.getPlayers()) {
            if (!p.isUnavailable() && "HOME".equals(p.getTeam()) && !p.equals(kicker)) {
                p.setPosition(new Position(
                        Math.min(p.getPosition().getRow(), 3.0),
                        p.getPosition().getColumn()));
            }
        }
        System.out.println("  >> KICKOFF by " + kicker.getLabel() + " (" + team + ")");
    }

    private void handleLooseBall(MatchState state, ActionEngine actionEngine,
                                  PlayerSelectionEngine selection, ActionLogService logger) {
        Player closestHome = selection.closestOutfieldTeamTo(state.getBall().getPosition(), "HOME");
        Player closestAway = selection.closestOutfieldTeamTo(state.getBall().getPosition(), "AWAY");
        if (closestHome != null && closestAway != null) {
            double dHome = SimUtils.distance(closestHome.getPosition(), state.getBall().getPosition());
            double dAway = SimUtils.distance(closestAway.getPosition(), state.getBall().getPosition());
            Player winner = dHome <= dAway ? closestHome : closestAway;
            actionEngine.giveBallTo(winner, "loose ball recovered");
        }
    }

    private void executeDecision(DecisionType decision, DecisionOption chosen, MatchState state,
                                  ActionEngine actionEngine, PlayerSelectionEngine selection,
                                  PlaymakingDecisionEngine decisionEngine,
                                  ActionLogService logger, FootballRulesService rulesService) {
        Player carrier = state.getCarrier();
        String team = carrier.getTeam();

        switch (decision) {
            case PASS -> {
                Player receiver = chosen.getTarget();
                if (receiver == null || receiver == carrier) {
                    DecisionOption fallback = decisionEngine.getBestPassFallback();
                    if (fallback != null && fallback.getTarget() != null
                            && fallback.getTarget() != carrier) {
                        receiver = fallback.getTarget();
                    } else {
                        receiver = null;
                    }
                }
                if (receiver != null) {
                    if (!state.isKickoffActionPending()
                            && SimUtils.distance(receiver.getPosition(), carrier.getPosition()) > 2.0
                            && rulesService.isOffside(receiver, carrier.getPosition(),
                                    state.getBall().getPosition())) {
                        String defendingTeam = "HOME".equals(team) ? "AWAY" : "HOME";
                        Player fkTaker = findNearestNonGoalkeeperTo(state, receiver.getPosition(), defendingTeam);
                        if (fkTaker != null) {
                            actionEngine.giveBallTo(fkTaker, "offside -> free kick");
                            state.setSetPiecePending(true);
                            System.out.println("     => OFFSIDE -> free kick for " + defendingTeam);
                        } else {
                            actionEngine.executeClearance();
                        }
                    } else {
                        actionEngine.executePassTo(receiver);
                        decisionEngine.recordPassExchange(carrier.getId(), receiver.getId());
                    }
                } else {
                    actionEngine.executeClearance();
                }
            }
            case THRU -> {
                Player runner = chosen.getTarget();
                if (runner != null && runner != carrier) {
                    actionEngine.executeThruPass(runner);
                    decisionEngine.recordPassExchange(carrier.getId(), runner.getId());
                } else {
                    actionEngine.executeClearance();
                }
            }
            case CARRY -> {
                double dir = "HOME".equals(carrier.getTeam()) ? 1 : -1;
                Position dest = new Position(
                        SimUtils.clamp(carrier.getPosition().getRow() + dir * 2, 1, 7),
                        SimUtils.clamp(carrier.getPosition().getColumn()
                                + (state.getRandom().nextDouble() * 2 - 1), 1, 6));
                actionEngine.executeCarry();
            }
            case SHOT -> actionEngine.executeShot();
            case CROSS -> actionEngine.executeCross();
            case CENTER -> actionEngine.executeCenter();
            case CLEAR -> actionEngine.executeClearance();
            default -> actionEngine.executeClearance();
        }
    }

    private void handleInFlightArrival(MatchState state, ActionEngine actionEngine,
                                        ActionLogService logger, FootballRulesService rulesService) {
        Action action = state.getAction();
        if (action == null || !action.isInFlight()) return;
        Ball ball = state.getBall();
        Position target = ball.getTarget();
        if (target == null) return;
        BallMovementEngine.moveBallToward(ball, target, BallMovementEngine.BALL_SPEED);
        if (SimUtils.distance(ball.getPosition(), target) <= BallMovementEngine.PICKUP_DISTANCE) {
            ball.setPosition(target);
            if (action.getPassLength() == PassLength.THRU && action.isGoodExecution()
                    && action.getTargetPlayer() != null) {
                Player receiver = action.getTargetPlayer();
                double distToReceiver = SimUtils.distance(receiver.getPosition(), ball.getPosition());
                if (distToReceiver > ExecutionQuality.THRU_SUCCESS_THRESHOLD) {
                    if (state.getThruBallArrivalTick() < 0) {
                        state.setThruBallArrivalTick(state.getSimulationTick());
                        state.addActiveChaser(receiver);
                    }
                    long elapsed = state.getSimulationTick() - state.getThruBallArrivalTick();
                    if (elapsed <= 30) {
                        if (elapsed > 0 && elapsed % 10 == 0) state.beginRound();
                        return;
                    }
                    state.removeActiveChaser(receiver);
                    state.setThruBallArrivalTick(-1);
                } else {
                    state.removeActiveChaser(receiver);
                    state.setThruBallArrivalTick(-1);
                }
            }
            ball.setTarget(null);
            Player receiver = action.getTargetPlayer();
            if (receiver != null) {
                boolean completed = actionEngine.pickupPass();
                if (completed) {
                    System.out.println("     => PASS RECEIVED by " + receiver.getLabel());
                } else {
                    System.out.println("     => PASS LOOSE (no receiver in range)");
                }
            } else {
                actionEngine.passFailed();
                System.out.println("     => PASS FAILED (no receiver)");
            }
        }
    }

    private void handleShotArrival(MatchState state, ActionEngine actionEngine,
                                    ActionLogService logger, FootballRulesService rulesService) {
        Action action = state.getAction();
        if (action == null || !action.isShotInFlight()) return;
        Ball ball = state.getBall();
        Position shotTarget = action.getActualTarget();
        if (shotTarget == null) return;
        BallMovementEngine.moveBallToward(ball, shotTarget, BallMovementEngine.BALL_SPEED);
        if (SimUtils.distance(ball.getPosition(), shotTarget) <= BallMovementEngine.PICKUP_DISTANCE) {
            ball.setPosition(shotTarget);
            ball.setTarget(null);
            if (action.isGoodExecution()) {
                System.out.println("     => SHOT ON TARGET! -> possible goal/save");
                actionEngine.complete("SHOT on target");
            } else {
                System.out.println("     => SHOT MISSED (off target)");
                actionEngine.shotMissed();
            }
        }
    }

    private Player findNearestNonGoalkeeperTo(MatchState state, Position pos, String team) {
        Player nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!team.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isUnavailable()) continue;
            double dist = SimUtils.distance(p.getPosition(), pos);
            if (dist < minDist) {
                minDist = dist;
                nearest = p;
            }
        }
        return nearest;
    }
}
