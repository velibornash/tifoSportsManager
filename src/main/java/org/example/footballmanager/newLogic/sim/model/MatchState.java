package org.example.footballmanager.newLogic.sim.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.example.footballmanager.newLogic.sim.engine.ActionLogService;

/**
 * Authoritative match state — single source of truth.
 * All engines read from match state, write to it through documented operations.
 */
public class MatchState {

    private final String matchId;
    private int matchTicks;           // 0..10800 (90 min @ 40 TPM)
    private boolean stopped;          // true = clock paused (half time, etc.)
    private boolean halfTime;         // half time reached (replay overlay)
    private boolean matchFinished;    // full time reached (replay overlay)

    // VAR review state
    private boolean varReviewActive = false;
    private int varDelayTicks = 0;    // delay counter during review

    // Pending VAR review (deferred decision — offside check held until the
    // next action boundary resolves it).
    private String pendingVARReviewType;   // OFFSIDE / ONSIDE_CHECK / GOAL / RED_CARD / PENALTY / YELLOW_CARD
    private Player pendingVARReviewPlayer; // receiver subject to the review
    private String pendingVARReviewTeam;   // team of the player under review (carrier team)

    // Offside deferral state (marginal call — play continues, flag held).
    private boolean offsideDeferred;              // flag is physically held
    private double offsideDeferredMargin;         // margin when the flag was held (cells)
    private boolean offsideLedToGoal;             // deferred call consumed by a goal path
    private int offsideDeferredActionCount;       // action count at defer time
    private boolean offsideDeferredDecisionForward; // the deferred(next) action attacked forward

    // Offside whistle-at-reception flag (user rule 2026-09-23): set at pass-moment
    // when the intended receiver is (clear or marginal) offside. The pass flies
    // NORMALLY — no block, no teleport, the ball never accelerates. The whistle
    // fires only when that receiver actually TOUCHES the ball (BallResultHandler
    // RECEIVE); if anyone else reaches the ball first (intercept/save/block/
    // deflect/OOB/dead ball) the flag is cleared and play continues (no offense).
    private Player offsideFlaggedReceiver;

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

    // Shared action logger — every engine that makes a decision or executes an
    // action writes through this single shared service so the app log shows the
    // full decision→execution trace (who decided, why, where the ball went).
    // Mirrors the setBallEngine wiring pattern (AGENTS.md "mirror setBallEngine
    // at MatchState.java 233–234").
    private ActionLogService actionLogger;

    // Action
    private Action currentAction;
    private int actionCount; // total decisions executed (for offside-deferral boundary)

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

    // Last action (for shot outcome attribution)
    private ActionType lastActionType;     // what the last decision executed (SHOT/PASS/DRIBBLE/CLEAR)
    private Player lastShooter;            // who fired the last shot (carrier is null during flight)
    private boolean lastShotOnTarget;      // predicted on-target by ExecutionQuality at fire time

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

    // === MATCH PHASE MARKERS (replay/report facing) ===
    // Set by MatchClockService. The replay viewer keys its HALF TIME and
    // FULL TIME overlays off these, so they must reflect reality — the
    // recorder used to hardcode `false, false`, which meant the overlays
    // could never fire in the proposal viewer.

    /** True from the moment the clock reaches half time onwards. */
    public boolean isHalfTime() { return halfTime; }
    public void setHalfTime(boolean halfTime) { this.halfTime = halfTime; }

    /** True once the full 90 minutes have been played. */
    public boolean isMatchFinished() { return matchFinished; }
    public void setMatchFinished(boolean matchFinished) { this.matchFinished = matchFinished; }

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

    /** Is there a held pending-VAR review (type/player/team set)? */
    public boolean hasPendingVARReview() {
        return pendingVARReviewType != null;
    }

    /** Type of the held review — OFFSIDE / OFF_SIDE / ONSIDE_CHECK / GOAL / ... */
    public String getPendingVARReviewType() { return pendingVARReviewType; }

    /** Player under review (the flagged receiver). */
    public Player getPendingVARReviewPlayer() { return pendingVARReviewPlayer; }

    /** Team of the player under review (the attacking/carrying team). */
    public String getPendingVARReviewTeam() { return pendingVARReviewTeam; }

    /** Hold a pending VAR review for a marginal (deferred) call. */
    public void setPendingVARReview(String type, Player player, String team) {
        this.pendingVARReviewType = type;
        this.pendingVARReviewPlayer = player;
        this.pendingVARReviewTeam = team;
    }

    /** Clear the held pending-VAR review (whistled or dropped). */
    public void clearPendingVARReview() {
        this.pendingVARReviewType = null;
        this.pendingVARReviewPlayer = null;
        this.pendingVARReviewTeam = null;
    }

    // === OFF-SIDE DEFERRAL (marginal-call hold) ===
    public boolean isOffsideDeferred() { return offsideDeferred; }
    public void setOffsideDeferred(boolean deferred) { this.offsideDeferred = deferred; }
    public double getOffsideDeferredMargin() { return offsideDeferredMargin; }
    public void setOffsideDeferredMargin(double margin) { this.offsideDeferredMargin = margin; }
    public boolean isOffsideLedToGoal() { return offsideLedToGoal; }
    public void setOffsideLedToGoal(boolean ledToGoal) { this.offsideLedToGoal = ledToGoal; }
    public int getOffsideDeferredActionCount() { return offsideDeferredActionCount; }
    public void setOffsideDeferredActionCount(int count) { this.offsideDeferredActionCount = count; }
    public boolean isOffsideDeferredDecisionForward() { return offsideDeferredDecisionForward; }
    public void setOffsideDeferredDecisionForward(boolean forward) { this.offsideDeferredDecisionForward = forward; }

    // === OFFSIDE WHISTLE-AT-RECEPTION ===
    public Player getOffsideFlaggedReceiver() { return offsideFlaggedReceiver; }
    public void setOffsideFlaggedReceiver(Player offsideFlaggedReceiver) { this.offsideFlaggedReceiver = offsideFlaggedReceiver; }

    // === GOALS ===
    public int getHomeGoals() { return homeGoals; }
    public int getAwayGoals() { return awayGoals; }
    public void addHomeGoal() { this.homeGoals++; }
    public void addAwayGoal() { this.awayGoals++; }

    // === PLAYERS ===
    public List<Player> getPlayers() { return players; }

    public Ball getBall() { return ball; }

    // === PASS / ASSIST CONTEXT ===
    private String pendingPasserId;
    private String pendingPasserName;
    private String pendingPasserTeam;
    private String pendingPassReceiverId;
    private String lastCompletedPasserId;
    private String lastCompletedPasserName;
    private String lastCompletedPasserTeam;
    private String lastCompletedPasserRole;

    public void beginPass(Player passer, Player receiver) {
        pendingPasserId = passer != null ? passer.getId() : null;
        pendingPasserName = passer != null ? passer.getLabel() : null;
        pendingPasserTeam = passer != null ? passer.getTeam() : null;
        pendingPassReceiverId = receiver != null ? receiver.getId() : null;
        lastCompletedPasserId = null;
        lastCompletedPasserName = null;
        lastCompletedPasserTeam = null;
        lastCompletedPasserRole = null;
    }

    public String completePass(Player receiver) {
        String completedPasserId = null;
        if (pendingPasserId != null && receiver != null
                && receiver.getId().equals(pendingPassReceiverId)
                && receiver.getTeam().equals(pendingPasserTeam)) {
            completedPasserId = pendingPasserId;
            lastCompletedPasserId = pendingPasserId;
            lastCompletedPasserName = pendingPasserName;
            lastCompletedPasserTeam = pendingPasserTeam;
            lastCompletedPasserRole = findRole(pendingPasserId);
        } else {
            clearCompletedPass();
        }
        clearPendingPass();
        return completedPasserId;
    }

    public void clearPendingPass() {
        pendingPasserId = null;
        pendingPasserName = null;
        pendingPasserTeam = null;
        pendingPassReceiverId = null;
    }

    public void clearPassContext() {
        clearPendingPass();
        lastCompletedPasserId = null;
        lastCompletedPasserName = null;
        lastCompletedPasserTeam = null;
        lastCompletedPasserRole = null;
    }

    public String assistIdFor(Player scorer) {
        if (scorer == null || scorer.isGoalkeeper() || lastCompletedPasserId == null
                || !scorer.getTeam().equals(lastCompletedPasserTeam)
                || scorer.getId().equals(lastCompletedPasserId)
                || "GK".equals(lastCompletedPasserRole)) return null;
        return lastCompletedPasserId;
    }

    public String assistNameFor(Player scorer) {
        return assistIdFor(scorer) == null ? null : lastCompletedPasserName;
    }

    public void clearCompletedPass() {
        lastCompletedPasserId = null;
        lastCompletedPasserName = null;
        lastCompletedPasserTeam = null;
        lastCompletedPasserRole = null;
    }

    public String getPendingPasserId() { return pendingPasserId; }
    public String getPendingPasserName() { return pendingPasserName; }
    public String getPendingPasserTeam() { return pendingPasserTeam; }
    public String getPendingPassReceiverId() { return pendingPassReceiverId; }
    public String getLastCompletedPasserId() { return lastCompletedPasserId; }
    public String getLastCompletedPasserName() { return lastCompletedPasserName; }
    public String getLastCompletedPasserTeam() { return lastCompletedPasserTeam; }

    private String findRole(String playerId) {
        for (Player p : players) {
            if (p.getId().equals(playerId)) return p.getRole();
        }
        return null;
    }

    // === PENALTY COUNTERS ===
    private int homePenalties;
    private int awayPenalties;

    public int getHomePenalties() { return homePenalties; }
    public void setHomePenalties(int homePenalties) { this.homePenalties = homePenalties; }
    public void incrementHomePenalties() { this.homePenalties++; }
    public int getAwayPenalties() { return awayPenalties; }
    public void setAwayPenalties(int awayPenalties) { this.awayPenalties = awayPenalties; }
    public void incrementAwayPenalties() { this.awayPenalties++; }

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

    /** Shared decision/action logger — single owner injected by MatchOrchestrator
     *  ctor (mirror of setBallEngine). Every engine writes through this so the
     *  app log shows the full decision trace, not just the change-gated tags. */
    public ActionLogService getActionLogger() { return actionLogger; }
    public void setActionLogger(ActionLogService logger) { this.actionLogger = logger; }

    public Action getCurrentAction() { return currentAction; }
    public void setCurrentAction(Action currentAction) { this.currentAction = currentAction; }

    /** Number of decisions executed so far — offside deferrals wait for the NEXT action boundary. */
    public int getActionCount() { return actionCount; }
    public void incrementActionCount() { this.actionCount++; }

    public MatchPhase getPhase() { return phase; }
    public void setPhase(MatchPhase phase) { this.phase = phase; }

    public String getSetPieceType() { return setPieceType; }
    public void setSetPieceType(String setPieceType) { this.setPieceType = setPieceType; }
    public void clearSetPieceType() { this.setPieceType = null; }

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

    // === LAST ACTION (shot outcome attribution) ===
    public ActionType getLastActionType() { return lastActionType; }
    public void setLastActionType(ActionType lastActionType) { this.lastActionType = lastActionType; }
    public Player getLastShooter() { return lastShooter; }
    public void setLastShooter(Player lastShooter) { this.lastShooter = lastShooter; }
    public boolean isLastShotOnTarget() { return lastShotOnTarget; }
    public void setLastShotOnTarget(boolean onTarget) { this.lastShotOnTarget = onTarget; }

    // === UTILITY ===
    public String getMatchId() { return matchId; }
    public boolean isHome(String team) { return "HOME".equals(team); }
    public String getCarrierTeam() {
        return carrier != null ? carrier.getTeam() : null;
    }
}