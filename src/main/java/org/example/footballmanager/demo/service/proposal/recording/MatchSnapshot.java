package org.example.footballmanager.demo.service.proposal.recording;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.footballmanager.demo.service.proposal.model.Ball;
import org.example.footballmanager.demo.service.proposal.model.MatchPhase;
import org.example.footballmanager.demo.service.proposal.model.Position;

import java.util.List;

/**
 * Snapshot of the entire match state at a single tick.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatchSnapshot {
    private final long tick;
    private final int round;
    private final List<PlayerSnapshot> players;
    private final Position ballPosition;
    private final Position ballTarget;
    private final Ball.BallState ballState;
    private final String ballCarrierId;
    private final String actionId;
    private final String actionType;
    private final String actingPlayerId;
    private final String targetPlayerId;
    private final Position intendedTarget;
    private final Position actualTarget;
    private final MatchPhase phase;
    private final int homeGoals;
    private final int awayGoals;
    private final int matchTicks;
    private final boolean halfTime;
    private final boolean matchFinished;
    private final int passAttempts;
    private final int passCompletions;
    private final int shotsOnTarget;

    public MatchSnapshot(long tick, int round, List<PlayerSnapshot> players,
                         Position ballPosition, Position ballTarget,
                         Ball.BallState ballState, String ballCarrierId,
                         String actionId, String actionType, String actingPlayerId,
                         String targetPlayerId, Position intendedTarget,
                         Position actualTarget,
                         MatchPhase phase, int homeGoals, int awayGoals,
                         int matchTicks, boolean halfTime, boolean matchFinished,
                         int passAttempts, int passCompletions, int shotsOnTarget) {
        this.tick = tick;
        this.round = round;
        this.players = players;
        this.ballPosition = ballPosition;
        this.ballTarget = ballTarget;
        this.ballState = ballState;
        this.ballCarrierId = ballCarrierId;
        this.actionId = actionId;
        this.actionType = actionType;
        this.actingPlayerId = actingPlayerId;
        this.targetPlayerId = targetPlayerId;
        this.intendedTarget = intendedTarget;
        this.actualTarget = actualTarget;
        this.phase = phase;
        this.homeGoals = homeGoals;
        this.awayGoals = awayGoals;
        this.matchTicks = matchTicks;
        this.halfTime = halfTime;
        this.matchFinished = matchFinished;
        this.passAttempts = passAttempts;
        this.passCompletions = passCompletions;
        this.shotsOnTarget = shotsOnTarget;
    }

    public long getTick() { return tick; }
    public int getRound() { return round; }
    public List<PlayerSnapshot> getPlayers() { return players; }
    public Position getBallPosition() { return ballPosition; }
    public Position getBallTarget() { return ballTarget; }
    public Ball.BallState getBallState() { return ballState; }
    public String getBallCarrierId() { return ballCarrierId; }
    public String getActionId() { return actionId; }
    public String getActionType() { return actionType; }
    public String getActingPlayerId() { return actingPlayerId; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public Position getIntendedTarget() { return intendedTarget; }
    public Position getActualTarget() { return actualTarget; }
    public MatchPhase getPhase() { return phase; }
    public int getHomeGoals() { return homeGoals; }
    public int getAwayGoals() { return awayGoals; }
    public int getMatchTicks() { return matchTicks; }
    public boolean isHalfTime() { return halfTime; }
    public boolean isMatchFinished() { return matchFinished; }
    public int getPassAttempts() { return passAttempts; }
    public int getPassCompletions() { return passCompletions; }
    public int getShotsOnTarget() { return shotsOnTarget; }
}