package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

import java.util.*;

/**
 * Central mutable state for a headless match simulation.
 * All engines read/write through this object.
 */
public class MatchState {

    public static final int SIMULATION_TICKS_PER_SECOND = 20;
    public static final int MATCH_TICKS_PER_MINUTE = 40;
    public static final int REGULATION_MINUTES = 90;
    public static final int EXTRA_TIME_MINUTES = 3;
    public static final int DUEL_LOSS_TICKS = 60;
    public static final int SET_PIECE_HOLD_TICKS = 60;
    public static final int CORNER_TAKER_HOLD_TICKS = 40;

    public static final String TEAM_HOME = "HOME";

    private final List<Player> players;
    private final List<Position> initialPositions;
    private final Ball ball;
    private final TacticsRules tacticsRules;
    private final Random random;
    private final MatchRecorder recorder;

    private Player carrier;
    private Action action;

    private String status = "ready";
    private int goalCount;
    private int awayGoalCount;
    private int actionCount;
    private int shotCount;
    private int round;
    private int matchTicks;
    private boolean halfTime;
    private boolean matchFinished;
    private boolean matchStarted;
    private boolean simulationRunning;
    private int passAttempts;
    private int passCompletions;
    private int shotsOnTarget;
    private int awayPassAttempts;
    private int awayPassCompletions;
    private int awayShotsOnTarget;
    private long simulationTick;
    private long nextActionSequence = 1;
    private boolean celebrating;
    private String celebratingTeam;
    private boolean awayRestartPending;
    private Player returningPlayer;
    private Position pendingRestartPosition;
    private Player pendingRestartPlayer;
    private boolean restartPassToHomeGoalkeeper;
    private int actionDelayTicks;
    private int restartHoldTicks;
    private final Map<Player, Integer> duelCooldownTicks = new HashMap<>();
    private boolean pendingCorner;
    private boolean pendingCornerRight;
    private Player cornerTaker;
    private String cornerTeam;
    private MatchPhase phase = MatchPhase.OPEN_PLAY;
    private String kickoffTeam = TEAM_HOME;
    private boolean kickoffPending = true;
    private boolean kickoffActionPending = false;
    private int cornerHoldTicks;
    private final Set<Player> activeChasers = new HashSet<>();
    private final List<GoalRecord> goals = new ArrayList<>();

    private final Map<Player, Position> roundStartPositions = new HashMap<>();
    private final Map<Player, Position> roundEndPositions = new HashMap<>();
    private final Map<Player, Position> desiredPositions = new HashMap<>();
    private final Map<Player, Position> tacticalDesiredPositions = new HashMap<>();
    private final Map<Player, Integer> roundPaceSkills = new HashMap<>();
    private Position roundStartBallPosition;
    private Position roundEndBallPosition;
    private Position tacticalBallPosition;
    private String lastTacticalBallStateKey;
    private boolean roundComplete = true;

    public MatchState(List<Player> players, Ball ball, TacticsRules tacticsRules,
                      Random random, MatchRecorder recorder) {
        this.players = players;
        this.ball = ball;
        this.tacticsRules = tacticsRules;
        this.random = random;
        this.recorder = recorder;
        this.initialPositions = new ArrayList<>(players.size());
        this.roundStartBallPosition = ball.getPosition();
        this.roundEndBallPosition = ball.getPosition();
        this.tacticalBallPosition = ball.getPosition();
        this.lastTacticalBallStateKey = TacticsRules.ballStateKey(ball.getPosition());
        for (Player p : players) {
            initialPositions.add(p.getPosition());
            roundStartPositions.put(p, p.getPosition());
            roundEndPositions.put(p, p.getPosition());
            desiredPositions.put(p, p.getPosition());
            tacticalDesiredPositions.put(p, p.getPosition());
        }
    }

    // --- Accessors ---

    public List<Player> getPlayers() { return players; }
    public Ball getBall() { return ball; }
    public TacticsRules getTacticsRules() { return tacticsRules; }
    public Random getRandom() { return random; }
    public MatchRecorder getRecorder() { return recorder; }

    public Player getCarrier() { return carrier; }
    public void setCarrier(Player carrier) { this.carrier = carrier; }

    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public boolean hasActiveAction() { return action != null; }

    // --- Counters ---

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getGoalCount() { return goalCount; }
    public int getAwayGoalCount() { return awayGoalCount; }
    public void incrementGoalCount() { goalCount++; }
    public void incrementAwayGoalCount() { awayGoalCount++; }
    public int getActionCount() { return actionCount; }
    public void incrementActionCount() { actionCount++; }
    public int getShotCount() { return shotCount; }
    public void incrementShotCount() { shotCount++; }
    public int getRound() { return round; }
    public int getMatchTicks() { return matchTicks; }
    public boolean isHalfTime() { return halfTime; }
    public boolean isMatchFinished() { return matchFinished; }
    public boolean isMatchStarted() { return matchStarted; }
    public List<GoalRecord> getGoals() { return List.copyOf(goals); }
    public int getPassAttempts() { return passAttempts; }
    public int getPassCompletions() { return passCompletions; }
    public int getShotsOnTarget() { return shotsOnTarget; }
    public int getAwayPassAttempts() { return awayPassAttempts; }
    public int getAwayPassCompletions() { return awayPassCompletions; }
    public int getAwayShotsOnTarget() { return awayShotsOnTarget; }

    public void incrementPassAttempts() { passAttempts++; }
    public void incrementPassCompletions() { passCompletions++; }
    public void incrementShotsOnTarget() { shotsOnTarget++; }
    public void incrementPassAttempts(String team) {
        if (TEAM_HOME.equals(team)) passAttempts++; else awayPassAttempts++;
    }
    public void incrementPassCompletions(String team) {
        if (TEAM_HOME.equals(team)) passCompletions++; else awayPassCompletions++;
    }
    public void incrementShotsOnTarget(String team) {
        if (TEAM_HOME.equals(team)) shotsOnTarget++; else awayShotsOnTarget++;
    }

    public long getSimulationTick() { return simulationTick; }
    public void advanceSimulationTick() { simulationTick++; }
    public String nextActionId() { return "A-" + nextActionSequence++; }

    // --- Match clock ---

    public void advanceMatchClock() {
        if (!matchStarted || halfTime || matchFinished || !simulationRunning) return;
        matchTicks++;
        if (matchTicks == 45 * MATCH_TICKS_PER_MINUTE) {
            enterHalfTime();
        } else if (matchTicks >= (REGULATION_MINUTES + EXTRA_TIME_MINUTES) * MATCH_TICKS_PER_MINUTE) {
            matchFinished = true;
            status = "MATCH FINISHED";
        }
    }

    public String matchClockLabel() {
        int minute = matchTicks / MATCH_TICKS_PER_MINUTE;
        int second = (int) Math.round((matchTicks % MATCH_TICKS_PER_MINUTE) * 60.0 / MATCH_TICKS_PER_MINUTE);
        if (minute > REGULATION_MINUTES) {
            return REGULATION_MINUTES + "+" + (minute - REGULATION_MINUTES)
                    + ":" + String.format("%02d", second);
        }
        return minute + ":" + String.format("%02d", second);
    }

    public int matchMinute() {
        return Math.max(1, (matchTicks + MATCH_TICKS_PER_MINUTE - 1) / MATCH_TICKS_PER_MINUTE);
    }

    // --- Match lifecycle ---

    public void startMatchSimulation() {
        if (matchFinished) return;
        matchStarted = true;
        simulationRunning = true;
        status = "MATCH STARTED";
    }

    public void startSecondHalf() {
        if (!halfTime) return;
        halfTime = false;
        matchTicks = 46 * MATCH_TICKS_PER_MINUTE;
        kickoffTeam = "AWAY";
        kickoffPending = true;
        status = "SECOND HALF";
    }

    // --- Goal recording ---

    public void recordGoal(Player scorer) {
        goals.add(new GoalRecord(matchMinute(), scorer.getId(), scorer.getLabel(), scorer.getTeam()));
    }

    // --- Celebration ---

    public boolean isCelebrating() { return celebrating; }
    public void setCelebrating(boolean celebrating) { this.celebrating = celebrating; }
    public String getCelebratingTeam() { return celebratingTeam; }
    public void setCelebratingTeam(String celebratingTeam) { this.celebratingTeam = celebratingTeam; }

    // --- Kickoff ---

    public boolean isKickoffPending() { return kickoffPending; }
    public void setKickoffPending(boolean value) { kickoffPending = value; }
    public boolean isKickoffActionPending() { return kickoffActionPending; }
    public void setKickoffActionPending(boolean value) { kickoffActionPending = value; }
    public String getKickoffTeam() { return kickoffTeam; }
    public void setKickoffTeam(String team) { kickoffTeam = team; }

    // --- Match phase ---

    public MatchPhase getPhase() { return phase; }
    public void setPhase(MatchPhase phase) { this.phase = phase; }

    // --- Active chasers ---

    public void clearActiveChasers() { activeChasers.clear(); }
    public void setActiveChasers(Player first, Player second) {
        activeChasers.clear();
        if (first != null) activeChasers.add(first);
        if (second != null) activeChasers.add(second);
    }
    public boolean isActiveChaser(Player player) { return activeChasers.contains(player); }
    public Set<Player> getActiveChasers() { return Set.copyOf(activeChasers); }

    // --- Duel cooldown ---

    public void blockAfterDuel(Player player) {
        if (player != null) duelCooldownTicks.put(player, DUEL_LOSS_TICKS);
    }
    public boolean isBlockedAfterDuel(Player player) {
        return duelCooldownTicks.getOrDefault(player, 0) > 0;
    }
    public void consumeDuelCooldownTick() {
        duelCooldownTicks.replaceAll((player, ticks) -> Math.max(0, ticks - 1));
        duelCooldownTicks.entrySet().removeIf(entry -> entry.getValue() == 0);
    }

    // --- Corner ---

    public boolean isPendingCorner() { return pendingCorner; }
    public void setPendingCorner(boolean value) { pendingCorner = value; }
    public boolean isPendingCornerRight() { return pendingCornerRight; }
    public void setPendingCornerRight(boolean value) { pendingCornerRight = value; }
    public Player getCornerTaker() { return cornerTaker; }
    public void setCornerTaker(Player player) { cornerTaker = player; }
    public String getCornerTeam() { return cornerTeam; }
    public void setCornerTeam(String team) { cornerTeam = team; }
    public int getCornerHoldTicks() { return cornerHoldTicks; }
    public void setCornerHoldTicks(int ticks) { cornerHoldTicks = Math.max(0, ticks); }
    public void consumeCornerHoldTick() { if (cornerHoldTicks > 0) cornerHoldTicks--; }

    // --- Restart ---

    public boolean isAwayRestartPending() { return awayRestartPending; }
    public void setAwayRestartPending(boolean pending) { awayRestartPending = pending; }
    public Player getReturningPlayer() { return returningPlayer; }
    public void setReturningPlayer(Player player) { returningPlayer = player; }
    public Position getPendingRestartPosition() { return pendingRestartPosition; }
    public void setPendingRestartPosition(Position position) { pendingRestartPosition = position; }
    public Player getPendingRestartPlayer() { return pendingRestartPlayer; }
    public void setPendingRestartPlayer(Player player) { pendingRestartPlayer = player; }
    public boolean isRestartPassToHomeGoalkeeper() { return restartPassToHomeGoalkeeper; }
    public void setRestartPassToHomeGoalkeeper(boolean value) { restartPassToHomeGoalkeeper = value; }
    public int getActionDelayTicks() { return actionDelayTicks; }
    public void setActionDelayTicks(int ticks) { actionDelayTicks = Math.max(0, ticks); }
    public void consumeActionDelayTick() { if (actionDelayTicks > 0) actionDelayTicks--; }
    public int getRestartHoldTicks() { return restartHoldTicks; }
    public void setRestartHoldTicks(int ticks) { restartHoldTicks = Math.max(0, ticks); }
    public void consumeRestartHoldTick() { if (restartHoldTicks > 0) restartHoldTicks--; }

    // --- Round tracking ---

    public Position getRoundStartPosition(Player p) {
        return roundStartPositions.getOrDefault(p, p.getPosition());
    }
    public Position getDesiredPosition(Player p) {
        return desiredPositions.getOrDefault(p, p.getPosition());
    }
    public Position getRoundEndPosition(Player p) {
        return roundEndPositions.getOrDefault(p, p.getPosition());
    }
    public void setRoundEndPosition(Player p, Position pos) {
        roundEndPositions.put(p, pos);
    }
    public Position getTacticalDesiredPosition(Player p) {
        return tacticalDesiredPositions.getOrDefault(p, p.getPosition());
    }
    public void setTacticalDesiredPosition(Player p, Position pos) {
        tacticalDesiredPositions.put(p, pos);
    }
    public Position getRoundStartBallPosition() { return roundStartBallPosition; }
    public void setRoundStartBallPosition(Position pos) { roundStartBallPosition = pos; }
    public Position getRoundEndBallPosition() { return roundEndBallPosition; }
    public void setRoundEndBallPosition(Position pos) { roundEndBallPosition = pos; }
    public Position getTacticalBallPosition() { return tacticalBallPosition; }
    public void setTacticalBallPosition(Position pos) { tacticalBallPosition = pos; }
    public String getLastTacticalBallStateKey() { return lastTacticalBallStateKey; }
    public void setLastTacticalBallStateKey(String key) { lastTacticalBallStateKey = key; }
    public boolean isRoundComplete() { return roundComplete; }
    public void setRoundComplete(boolean roundComplete) { this.roundComplete = roundComplete; }

    public int getRoundPaceSkill(Player p) {
        return roundPaceSkills.getOrDefault(p, 20);
    }

    public void beginRound() {
        roundComplete = false;
        roundStartBallPosition = ball.getPosition();
        roundEndBallPosition = ball.getPosition();
        roundPaceSkills.clear();
        for (Player p : players) {
            Position pos = p.getPosition();
            roundStartPositions.put(p, pos);
            roundEndPositions.put(p, pos);
            roundPaceSkills.put(p, random.nextInt(20) + 1);
        }
    }

    public void recordDesiredPositions() {
        for (Player p : players) {
            Position target = p.getTarget();
            desiredPositions.put(p, target != null ? target : p.getPosition());
        }
    }

    public void incrementRound() { round++; }

    // --- Reset ---

    private void enterHalfTime() {
        halfTime = true;
        status = "HALF-TIME";
        resetPositionsOnly();
    }

    public void resetMatch() {
        resetPositionsOnly();
        matchTicks = 0;
        halfTime = false;
        matchFinished = false;
        matchStarted = false;
        passAttempts = 0;
        passCompletions = 0;
        shotsOnTarget = 0;
        awayPassAttempts = 0;
        awayPassCompletions = 0;
        awayShotsOnTarget = 0;
        goals.clear();
        goalCount = 0;
        awayGoalCount = 0;
        kickoffTeam = TEAM_HOME;
        kickoffPending = true;
        kickoffActionPending = false;
        simulationRunning = false;
        activeChasers.clear();
        phase = MatchPhase.OPEN_PLAY;
        status = "ready";
    }

    private void resetPositionsOnly() {
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.setPosition(initialPositions.get(i));
            p.setTarget(null);
            p.setLocked(false);
            p.setVelX(0);
            p.setVelY(0);
        }
        ball.setPosition(ball.getInitialPosition());
        ball.setTarget(null);
        ball.setCarrier(null);
        carrier = null;
        action = null;
        celebrating = false;
        celebratingTeam = null;
        awayRestartPending = false;
        kickoffPending = true;
        kickoffActionPending = false;
        returningPlayer = null;
        pendingRestartPosition = null;
        pendingRestartPlayer = null;
        restartPassToHomeGoalkeeper = false;
        actionDelayTicks = 0;
        restartHoldTicks = 0;
        duelCooldownTicks.clear();
        pendingCorner = false;
        pendingCornerRight = false;
        cornerTaker = null;
        cornerTeam = null;
        cornerHoldTicks = 0;
        roundComplete = true;
        roundStartBallPosition = ball.getInitialPosition();
        roundEndBallPosition = ball.getInitialPosition();
        tacticalBallPosition = ball.getInitialPosition();
        lastTacticalBallStateKey = TacticsRules.ballStateKey(ball.getInitialPosition());
        roundPaceSkills.clear();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            roundStartPositions.put(p, p.getPosition());
            roundEndPositions.put(p, p.getPosition());
            desiredPositions.put(p, p.getPosition());
            tacticalDesiredPositions.put(p, p.getPosition());
            roundPaceSkills.put(p, 20);
        }
    }
}
