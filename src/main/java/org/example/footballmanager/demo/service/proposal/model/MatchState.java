package org.example.footballmanager.demo.service.proposal.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative match state — single source of truth.
 * All engines read from match state, write to it through documented operations.
 */
public class MatchState {

    private final String matchId;
    private int matchTicks;           // 0..10800 (90 min @ 40 TPM)
    private boolean stopped;          // true = clock paused (half time, etc.)

    // VAR review state
    private boolean varReviewActive = false;
    private int varDelayTicks = 0;    // delay counter during review

    // Score
    private int homeGoals;
    private int awayGoals;

    // Players (22 outfield + 2 GK)
    private final List<Player> players;
    private final Map<String, Position> roundStartPositions = new HashMap<>();
    private final Map<String, Integer> roundPaceSkills = new HashMap<>();

    // Ball
    private final Ball ball;
    private Player carrier;           // null = no carrier (transition/loose)
    private Player pendingReceiver;   // who should receive the in-flight pass
    private Position receivePoint;    // where the in-flight pass will land (receiver runs onto it)
    private String lastTouchTeam;     // team that last touched the ball
    private Player lastTouchPlayer;   // player who last touched the ball (for deflection exclusion)

    // OOB hold state (4-tick visible hold before restart)
    private String oobPending;        // restart type while hold active
    private int oobHoldTicks;         // remaining hold ticks

    // Pitch environment (goals, lines, OOB zones)
    private final PitchEnvironment environment;

    // Engine references (for execution)
    private BallEngine ballEngine;

    // Action
    private Action currentAction;

    // Tactical / phase
    private MatchPhase phase;
    private String setPieceType;      // CORNER, GOAL_KICK, THROW_IN, PENALTY, FREE_KICK
    private String restartTeam;
    private Player restartTaker;      // walks to the ball during a restart
    private boolean kickoffPending;   // true right after kickoff (match start / after goal)

    // Statistics
    private int passAttempts;
    private int passesCompleted;
    private int shots;
    private int shotsOnTarget;
    private int fouls;
    private int yellowCards;
    private int redCards;

    // Decision tracking
    private double lastDecisionScore;
    private String lastDecisionReason;

    public MatchState() {
        this.matchId = UUID.randomUUID().toString();
        this.matchTicks = 0;
        this.stopped = false;
        this.varReviewActive = false;
        this.varDelayTicks = 0;
        this.homeGoals = 0;
        this.awayGoals = 0;
        this.players = new ArrayList<>();
        this.ball = new Ball(new Position(CENTER_ROW, CENTER_COL));
        this.carrier = null;
        this.pendingReceiver = null;
        this.lastTouchTeam = null;
        this.oobPending = null;
        this.oobHoldTicks = 0;
        this.environment = new PitchEnvironment();
        this.currentAction = null;
        this.phase = MatchPhase.OPEN_PLAY;
        this.setPieceType = null;
        this.restartTeam = null;
    }

    // --- constants for kickoff ---
    public static final double CENTER_ROW = 4.5;
    public static final double CENTER_COL = 4.0;

    // === CLOCK CONTROL ===

    /** Called every simulation tick. Clock always advances unless stopped. */
    public void advanceTick() {
        if (!stopped) {
            matchTicks++;
        }
    }

    public int getMatchTicks() { return matchTicks; }
    public boolean isStopped() { return stopped; }
    public void setStopped(boolean stopped) { this.stopped = stopped; }

    // === VAR CONTROL ===

    /** Check if VAR review is currently active (pausing re-decision). */
    public boolean isVARReviewActive() {
        return varReviewActive && varDelayTicks > 0;
    }

    /** Start a VAR review. */
    public void startVARReview(int delayTicks) {
        this.varReviewActive = true;
        this.varDelayTicks = delayTicks;
    }

    /** Decrement VAR delay counter. */
    public void decrementVAR() {
        if (varDelayTicks > 0) {
            varDelayTicks--;
            if (varDelayTicks == 0) {
                varReviewActive = false;
            }
        }
    }

    // === GOALS ===
    public int getHomeGoals() { return homeGoals; }
    public int getAwayGoals() { return awayGoals; }
    public void addHomeGoal() { this.homeGoals++; }
    public void addAwayGoal() { this.awayGoals++; }

    // === PLAYERS ===
    public List<Player> getPlayers() { return players; }

    public Ball getBall() { return ball; }

    public Player getPendingReceiver() { return pendingReceiver; }
    public void setPendingReceiver(Player pendingReceiver) { this.pendingReceiver = pendingReceiver; }

    /** Where the in-flight pass is heading — the receiver runs onto it. */
    public Position getReceivePoint() { return receivePoint; }
    public void setReceivePoint(Position receivePoint) { this.receivePoint = receivePoint; }

    public Player getCarrier() { return carrier; }
    public void setCarrier(Player carrier) { this.carrier = carrier; }

    /** Team that last touched the ball (for goal/OOB attribution). */
    public String getLastTouchTeam() { return lastTouchTeam; }
    public void setLastTouchTeam(String team) { this.lastTouchTeam = team; }

    /** Player who last touched the ball (for deflection exclusion). */
    public Player getLastTouchPlayer() { return lastTouchPlayer; }
    public void setLastTouchPlayer(Player p) { this.lastTouchPlayer = p; }

    // === OOB HOLD ===
    public String getOobPending() { return oobPending; }
    public void setOobPending(String type) { this.oobPending = type; }
    public int getOobHoldTicks() { return oobHoldTicks; }
    public void setOobHoldTicks(int ticks) { this.oobHoldTicks = ticks; }
    public void decrementOobHold() { if (oobHoldTicks > 0) oobHoldTicks--; }
    public void clearOobPending() { this.oobPending = null; this.oobHoldTicks = 0; }

    // === ENVIRONMENT ===
    public PitchEnvironment getEnvironment() { return environment; }

    public BallEngine getBallEngine() { return ballEngine; }
    public void setBallEngine(BallEngine engine) { this.ballEngine = engine; }

    public Action getCurrentAction() { return currentAction; }
    public void setCurrentAction(Action currentAction) { this.currentAction = currentAction; }

    public MatchPhase getPhase() { return phase; }
    public void setPhase(MatchPhase phase) { this.phase = phase; }

    public String getSetPieceType() { return setPieceType; }
    public void setSetPieceType(String setPieceType) { this.setPieceType = setPieceType; }

    public String getRestartTeam() { return restartTeam; }
    public void setRestartTeam(String restartTeam) { this.restartTeam = restartTeam; }

    public Player getRestartTaker() { return restartTaker; }
    public void setRestartTaker(Player restartTaker) { this.restartTaker = restartTaker; }

    public boolean isKickoffPending() { return kickoffPending; }
    public void setKickoffPending(boolean kickoffPending) { this.kickoffPending = kickoffPending; }

    // === ROUND START POSITIONS ===
    public Position getRoundStartPosition(Player p) {
        return roundStartPositions.get(p.getId());
    }

    public void setRoundStartPosition(String playerId, Position pos) {
        roundStartPositions.put(playerId, pos);
    }

    public int getRoundPaceSkill(Player p) {
        return roundPaceSkills.getOrDefault(p.getId(), (int) Math.round(p.getSkills().pace()));
    }

    public void setRoundPaceSkill(String playerId, int skill) {
        roundPaceSkills.put(playerId, skill);
    }

    // === STATISTICS ===
    public int getPassAttempts() { return passAttempts; }
    public void incrementPassAttempts() { this.passAttempts++; }
    public int getPassesCompleted() { return passesCompleted; }
    public void incrementPassesCompleted() { this.passesCompleted++; }
    public int getShots() { return shots; }
    public void incrementShots() { this.shots++; }
    public int getShotsOnTarget() { return shotsOnTarget; }
    public void incrementShotsOnTarget() { this.shotsOnTarget++; }
    public int getFouls() { return fouls; }
    public void incrementFouls() { this.fouls++; }
    public int getYellowCards() { return yellowCards; }
    public void incrementYellowCards() { this.yellowCards++; }
    public int getRedCards() { return redCards; }
    public void incrementRedCards() { this.redCards++; }

    // === DECISION TRACKING ===
    public double getLastDecisionScore() { return lastDecisionScore; }
    public void setLastDecisionScore(double score) { this.lastDecisionScore = score; }
    public String getLastDecisionReason() { return lastDecisionReason; }
    public void setLastDecisionReason(String reason) { this.lastDecisionReason = reason; }

    // === UTILITY ===
    public String getMatchId() { return matchId; }
    public boolean isHome(String team) { return "HOME".equals(team); }
    public String getCarrierTeam() {
        return carrier != null ? carrier.getTeam() : null;
    }
}