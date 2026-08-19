package org.example.footballmanager.demo.service.result;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.engine.*;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

import java.util.*;

/**
 * Match Simulator — orchestrator that runs a full 90-minute match.
 *
 * Takes two teams with lineups, runs the simulation tick-by-tick,
 * and produces a complete MatchResult with all stats, goals, and report.
 */
public class MatchSimulator {

    private static final int TICKS_PER_ROUND = 20;
    private static final int MATCH_MINUTES = 90;
    private static final int TICKS_PER_MINUTE = 40;

    private final long seed;
    private final SimulationRandom random;

    public MatchSimulator(long seed) {
        this.seed = seed;
        this.random = new SimulationRandom(seed);
    }

    public MatchSimulator() {
        this(System.nanoTime());
    }

    /**
     * Run a full match between two teams.
     *
     * @param homePlayers home team starting XI (11 players)
     * @param awayPlayers away team starting XI (11 players)
     * @param homeTeamName home team display name
     * @param awayTeamName away team display name
     * @return complete match result
     */
    public MatchResult simulate(List<Player> homePlayers, List<Player> awayPlayers,
                                 String homeTeamName, String awayTeamName) {
        if (homePlayers.size() != 11 || awayPlayers.size() != 11) {
            throw new IllegalArgumentException("Each team must have exactly 11 players");
        }

        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);

        Position kickoffPos = new Position(4, 3.5);
        Ball ball = new Ball(kickoffPos, kickoffPos);
        TacticsRules tactics = new TacticsRules();
        MatchRecorder recorder = new MatchRecorder();
        MatchState state = new MatchState(allPlayers, ball, tactics, random.getRandom(), recorder);

        MatchStatsCollector stats = new MatchStatsCollector(homeTeamName, awayTeamName);
        stats.registerPlayers(allPlayers);

        // Wire up engines
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);
        ExecutionQuality executionQuality = new ExecutionQuality(random.getRandom());
        ActionEngine actionEngine = new ActionEngine(state, selection, executionQuality, recorder);
        DuelEngine duelEngine = new DuelEngine(state, new DuelResolver(random.getRandom()), recorder);
        MovementEngine movementEngine = new MovementEngine(state);
        BallMovementEngine ballMovementEngine = new BallMovementEngine(state);
        TacticalIntentEngine tacticalEngine = new TacticalIntentEngine(state);
        PlaymakingDecisionEngine decisionEngine = new PlaymakingDecisionEngine(state, selection, random.getRandom());
        ThreatAssessmentService threatService = new ThreatAssessmentService(state);
        PlayerPerceptionService perceptionService = new PlayerPerceptionService(state);
        FootballRulesService rulesService = new FootballRulesService(state);
        TransitionService transitionService = new TransitionService(state, recorder);
        FatigueService fatigueService = new FatigueService(state);

        // Start match
        state.startMatchSimulation();
        state.setPhase(MatchPhase.KICK_OFF);
        state.setKickoffTeam("HOME");
        state.setKickoffPending(true);

        int totalTicks = 0;
        int maxTicks = MATCH_MINUTES * TICKS_PER_MINUTE + 3 * TICKS_PER_MINUTE;

        while (totalTicks < maxTicks && !state.isMatchFinished()) {
            // Handle kickoff
            if (state.isKickoffPending()) {
                handleKickoff(state, actionEngine, selection, homePlayers, awayPlayers, stats);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle celebration
            if (state.isCelebrating()) {
                state.setCelebrating(false);
                state.setKickoffPending(true);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle action delay
            if (state.getActionDelayTicks() > 0) {
                state.consumeActionDelayTick();
                ballMovementEngine.moveBallTowardCurrentTarget();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle corner hold
            if (state.getCornerHoldTicks() > 0) {
                state.consumeCornerHoldTick();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // No active action — need new decision
            if (!state.hasActiveAction()) {
                if (state.getCarrier() != null) {
                    // Ball carrier makes a decision
                    state.beginRound();
                    tacticalEngine.assignTargets();
                    DecisionOption chosen = decisionEngine.decide();
                    DecisionType decision = chosen.getType();

                    recordActionStats(state, stats, decision, homeTeamName, awayTeamName);

                    executeDecision(decision, state, actionEngine, selection,
                            threatService, perceptionService, stats);

                    // Check for duel
                    duelEngine.update(state.getAction());
                    if (duelEngine.getActiveDuelAttacker() != null) {
                        duelEngine.resolveActiveDuel(state.getAction());
                        duelEngine.closeAfterResolution();
                        recordDuelStats(state, stats);
                    }

                } else if (ball.getTarget() != null) {
                    // Ball in flight — advance it
                    ballMovementEngine.moveBallTowardCurrentTarget();

                    // Check for duel during flight
                    duelEngine.update(state.getAction());
                    if (duelEngine.getActiveDuelAttacker() != null) {
                        duelEngine.resolveActiveDuel(state.getAction());
                        duelEngine.closeAfterResolution();
                        recordDuelStats(state, stats);
                    }
                } else {
                    // Loose ball — find chasers
                    Player closestHome = selection.closestHomeTo(ball.getPosition());
                    Player closestAway = selection.closestTeamTo(ball.getPosition(), "AWAY");
                    state.setActiveChasers(closestHome, closestAway);
                    actionEngine.start(ActionType.CHASE, "CHASE: loose ball");
                    state.setActionDelayTicks(0);
                }
            }

            // Check action completion
            actionEngine.checkActionCompletion();

            // Handle pass in flight arrival
            if (state.hasActiveAction() && state.getAction().isInFlight()) {
                handleInFlightArrival(state, actionEngine, stats, homeTeamName, awayTeamName);
            }

            // Handle shot arrival
            if (state.hasActiveAction() && state.getAction().isShotInFlight()) {
                handleShotArrival(state, actionEngine, stats, homeTeamName, awayTeamName);
            }

            // Movement
            movementEngine.moveAllTowardTargets();

            // Fatigue
            fatigueService.updateAll();

            // Transition
            transitionService.checkTransition();
            transitionService.updatePhase();

            // Duel cooldown
            state.consumeDuelCooldownTick();

            totalTicks++;
            state.advanceMatchClock();
            state.advanceSimulationTick();
        }

        // Build result
        int matchMinutes = Math.min(MATCH_MINUTES, totalTicks / TICKS_PER_MINUTE);

        TeamMatchStats homeTeamStats = stats.buildTeamStats(homeTeamName, totalTicks, matchMinutes);
        TeamMatchStats awayTeamStats = stats.buildTeamStats(awayTeamName, totalTicks, matchMinutes);

        List<PlayerMatchStats> homePlayerStatsList = stats.buildPlayerStats(homeTeamName, matchMinutes);
        List<PlayerMatchStats> awayPlayerStatsList = stats.buildPlayerStats(awayTeamName, matchMinutes);

        String finalScore = state.getGoalCount() + " - " + state.getAwayGoalCount();

        // Find man of the match
        String motm = findMotM(homePlayerStatsList, awayPlayerStatsList);

        // Build report
        MatchReport report = buildReport(homeTeamName, awayTeamName,
                state.getGoalCount(), state.getAwayGoalCount(),
                homeTeamStats, awayTeamStats, stats.getGoals(), motm);

        // Build lineups
        List<MatchResult.LineupPlayer> homeLineup = new ArrayList<>();
        List<MatchResult.LineupPlayer> awayLineup = new ArrayList<>();
        for (int i = 0; i < homePlayers.size(); i++) {
            homeLineup.add(MatchResult.LineupPlayer.from(homePlayers.get(i), i + 1));
        }
        for (int i = 0; i < awayPlayers.size(); i++) {
            awayLineup.add(MatchResult.LineupPlayer.from(awayPlayers.get(i), i + 1));
        }

        return new MatchResult(
                homeTeamName, awayTeamName,
                state.getGoalCount(), state.getAwayGoalCount(), finalScore,
                "4-4-2",
                homeLineup, awayLineup,
                homeTeamStats, awayTeamStats,
                homePlayerStatsList, awayPlayerStatsList,
                stats.getGoals(), report, seed
        );
    }

    // --- Private helpers ---

    private void handleKickoff(MatchState state, ActionEngine actionEngine,
                                PlayerSelectionEngine selection,
                                List<Player> homePlayers, List<Player> awayPlayers,
                                MatchStatsCollector stats) {
        String kickoffTeam = state.getKickoffTeam();
        List<Player> teamPlayers = "HOME".equals(kickoffTeam) ? homePlayers : awayPlayers;

        // Find a midfielder or attacker to kick off
        Player kicker = teamPlayers.stream()
                .filter(p -> "MID".equals(p.getRole()) || "ATT".equals(p.getRole()))
                .findFirst()
                .orElse(teamPlayers.get(0));

        state.getBall().setPosition(new Position(4, 3.5));
        state.getBall().setCarrier(kicker);
        state.setCarrier(kicker);
        state.setKickoffPending(false);
        state.setPhase(MatchPhase.OPEN_PLAY);
        state.setStatus("KICK OFF: " + kicker.getLabel());
    }

    private void executeDecision(DecisionType decision, MatchState state,
                                  ActionEngine actionEngine, PlayerSelectionEngine selection,
                                  ThreatAssessmentService threatService,
                                  PlayerPerceptionService perceptionService,
                                  MatchStatsCollector stats) {
        Player carrier = state.getCarrier();
        String team = carrier.getTeam();

        switch (decision) {
            case PASS -> {
                List<Player> nearest = selection.nearestTeamTo(carrier, 6);
                Player receiver = nearest.isEmpty() ? null : nearest.get(state.getRandom().nextInt(nearest.size()));
                if (receiver != null) {
                    stats.onPassAttempt(team, carrier.getId());
                    actionEngine.executePassTo(receiver);
                } else {
                    actionEngine.executeClearance();
                }
            }
            case THRU -> {
                Player runner = findThruRunner(carrier, selection);
                if (runner != null) {
                    stats.onPassAttempt(team, carrier.getId());
                    actionEngine.executeThruPass(runner);
                } else {
                    actionEngine.executePass();
                }
            }
            case CARRY -> {
                double dir = "HOME".equals(carrier.getTeam()) ? 1 : -1;
                Position dest = new Position(
                        SimUtils.clamp(carrier.getPosition().getRow() + dir * 2, 1, 7),
                        SimUtils.clamp(carrier.getPosition().getColumn() + (state.getRandom().nextDouble() * 2 - 1), 1, 6));
                actionEngine.executeCarry();
            }
            case SHOT -> actionEngine.executeShot();
            case CROSS -> actionEngine.executeCross();
            case CENTER -> actionEngine.executeCenter();
            case CLEAR -> actionEngine.executeClearance();
            default -> actionEngine.executeClearance();
        }
    }

    private Player findThruRunner(Player carrier, PlayerSelectionEngine selection) {
        boolean home = "HOME".equals(carrier.getTeam());
        List<Player> nearest = selection.nearestTeamTo(carrier, 6);
        for (Player p : nearest) {
            if (p == carrier || "GK".equals(p.getRole())) continue;
            boolean ahead = home
                    ? p.getPosition().getRow() > carrier.getPosition().getRow()
                    : p.getPosition().getRow() < carrier.getPosition().getRow();
            if (ahead) return p;
        }
        return null;
    }

    private void handleInFlightArrival(MatchState state, ActionEngine actionEngine,
                                         MatchStatsCollector stats,
                                         String homeTeamName, String awayTeamName) {
        Action action = state.getAction();
        if (action == null || !action.isInFlight()) return;

        Ball ball = state.getBall();
        Position target = ball.getTarget();
        if (target == null) return;

        BallMovementEngine.moveBallToward(ball, target, BallMovementEngine.BALL_SPEED);

        if (SimUtils.distance(ball.getPosition(), target) <= BallMovementEngine.PICKUP_DISTANCE) {
            ball.setPosition(target);
            ball.setTarget(null);

            if (action.isPassInFlight()) {
                Player receiver = action.getTargetPlayer();
                if (receiver != null && !receiver.isLocked()
                        && SimUtils.distance(receiver.getPosition(), ball.getPosition()) <= 0.5) {
                    // Pass received
                    state.getBall().setCarrier(receiver);
                    state.setCarrier(receiver);
                    receiver.setTarget(null);
                    stats.onPassCompleted(action.getActingPlayer().getTeam(),
                            action.getActingPlayer().getId(), receiver.getId());
                    actionEngine.complete("PASS -> " + receiver.getLabel() + " | RECEIVED");
                } else {
                    // Pass failed — clear action and make ball loose
                    if (receiver != null) receiver.setLocked(false);
                    state.getBall().setCarrier(null);
                    state.getBall().setTarget(null);
                    state.setCarrier(null);
                    actionEngine.complete("PASS -> LOOSE BALL");
                }
            }
        }
    }

    private void handleShotArrival(MatchState state, ActionEngine actionEngine,
                                    MatchStatsCollector stats,
                                    String homeTeamName, String awayTeamName) {
        Action action = state.getAction();
        if (action == null || !action.isShotInFlight()) return;

        Ball ball = state.getBall();
        Player shooter = action.getActingPlayer();
        Position goal = ActionEngine.goalPositionFor(shooter.getTeam());
        double distToGoal = SimUtils.distance(ball.getPosition(), goal);

        boolean onTarget = distToGoal < 2.0;
        stats.onShot(shooter.getTeam(), shooter.getId(), onTarget);

        if (action.isGoodExecution() || distToGoal < 1.0) {
            // Check for goalkeeper save
            String opposingTeam = "HOME".equals(shooter.getTeam()) ? awayTeamName : homeTeamName;
            Player keeper = findKeeper(state, opposingTeam);
            if (keeper != null && SimUtils.distance(keeper.getPosition(), ball.getPosition()) < 2.0) {
                double saveChance = keeper.getSkills().keeper() / 20.0 * 0.4;
                if (state.getRandom().nextDouble() < saveChance) {
                    actionEngine.shotSaved(keeper);
                    return;
                }
            }

            // GOAL!
            String assistId = stats.getLastPasserId();
            String assistName = null;
            if (assistId != null && stats.getLastPasserTeam().equals(shooter.getTeam())) {
                for (Player p : state.getPlayers()) {
                    if (p.getId().equals(assistId)) { assistName = p.getLabel(); break; }
                }
            }

            int homeScore = "HOME".equals(shooter.getTeam())
                    ? state.getGoalCount() + 1 : state.getGoalCount();
            int awayScore = "HOME".equals(shooter.getTeam())
                    ? state.getAwayGoalCount() : state.getAwayGoalCount() + 1;

            stats.onGoal(shooter.getTeam(), shooter.getId(), shooter.getLabel(),
                    assistId, assistName,
                    state.matchMinute(), homeScore, awayScore);

            actionEngine.goalScored();
        } else {
            actionEngine.shotMissed();
        }
    }

    private Player findKeeper(MatchState state, String team) {
        return state.getPlayers().stream()
                .filter(p -> team.equals(p.getTeam()) && "GK".equals(p.getRole()))
                .findFirst().orElse(null);
    }

    private void recordActionStats(MatchState state, MatchStatsCollector stats,
                                    DecisionType decision, String homeTeamName, String awayTeamName) {
        // Stats are recorded in executeDecision and handleShotArrival
    }

    private void recordDuelStats(MatchState state, MatchStatsCollector stats) {
        // Duel stats can be tracked here if needed
    }

    private String findMotM(List<PlayerMatchStats> home, List<PlayerMatchStats> away) {
        String bestName = "";
        double bestRating = -1;
        for (PlayerMatchStats p : home) {
            if (p.rating() > bestRating) {
                bestRating = p.rating();
                bestName = p.playerName();
            }
        }
        for (PlayerMatchStats p : away) {
            if (p.rating() > bestRating) {
                bestRating = p.rating();
                bestName = p.playerName();
            }
        }
        return bestName;
    }

    private MatchReport buildReport(String homeTeam, String awayTeam,
                                     int homeGoals, int awayGoals,
                                     TeamMatchStats homeStats, TeamMatchStats awayStats,
                                     List<GoalDetail> goals, String motm) {
        StringBuilder summary = new StringBuilder();
        summary.append(homeTeam).append(" ").append(homeGoals)
               .append(" - ").append(awayGoals).append(" ").append(awayTeam).append(". ");

        if (homeGoals > awayGoals) {
            summary.append(homeTeam).append(" wins!");
        } else if (awayGoals > homeGoals) {
            summary.append(awayTeam).append(" wins!");
        } else {
            summary.append("Draw!");
        }

        summary.append(" Possession: ")
               .append(String.format("%.0f%% - %.0f%%", homeStats.possessionPercent(), awayStats.possessionPercent()));
        summary.append(". Shots: ").append(homeStats.shots()).append(" - ").append(awayStats.shots());
        summary.append(". Passes: ").append(homeStats.passesCompleted()).append("/").append(homeStats.passesAttempted())
               .append(" (").append(homeStats.passAccuracy()).append("%) - ")
               .append(awayStats.passesCompleted()).append("/").append(awayStats.passesAttempted())
               .append(" (").append(awayStats.passAccuracy()).append("%)");

        List<String> keyEvents = new ArrayList<>();
        for (GoalDetail g : goals) {
            keyEvents.add(g.description());
        }
        keyEvents.add("Man of the Match: " + motm);

        String headline = homeTeam + " " + homeGoals + " - " + awayGoals + " " + awayTeam;

        return new MatchReport(headline, summary.toString(),
                homeTeam, awayTeam, homeGoals, awayGoals,
                homeStats.possessionPercent(), awayStats.possessionPercent(),
                keyEvents, motm, "");
    }
}
