package org.tifo.footballmanager.newLogic.sim.recording;

import org.tifo.footballmanager.newLogic.sim.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only recorder for match events and tick snapshots.
 * Produces the final MatchRecording for JSON output.
 */
public class MatchRecorder {

    private final List<MatchEvent> events = new ArrayList<>();
    private final List<MatchSnapshot> snapshots = new ArrayList<>();

    /**
     * Whether tick snapshots are recorded. Events are always recorded (they feed the post-match
     * report); only the per-tick position snapshots can be skipped, and they are the bulk of the
     * per-match memory. Disabled for matches nobody will replay (bot-vs-bot with no replay store).
     */
    private volatile boolean snapshotsEnabled = true;

    public void setSnapshotsEnabled(boolean enabled) { this.snapshotsEnabled = enabled; }
    public boolean isSnapshotsEnabled() { return snapshotsEnabled; }

    public void appendEvent(long tick, String type, String description) {
        events.add(new MatchEvent(tick, type, description, null, null, null, null, null, null, null, type));
    }

    public void appendEvent(long tick, String type, String description, MatchState state) {
        appendEvent(tick, type, description, state.getCarrier(), state.getPendingReceiver());
    }

    /** Append an enriched event with an explicit acting player + target (carrier may already be null). */
    public void appendEvent(long tick, String type, String description, Player acting, Player target) {
        String team = acting != null ? acting.getTeam() : null;
        String playerId = acting != null ? acting.getId() : null;
        String playerName = acting != null ? acting.getLabel() : null;
        String targetId = target != null ? target.getId() : null;
        Integer skill = acting != null ? (int) acting.getSkills().technique() : null;
        Double posRow = acting != null ? acting.getPosition().getRow() : null;
        Double posCol = acting != null ? acting.getPosition().getColumn() : null;
        events.add(new MatchEvent(tick, type, description, team, playerId, playerName, targetId, posRow, posCol, skill, type));
    }

    public void appendEvent(MatchEvent event) {
        events.add(event);
    }

    public void appendEvent(long tick, String type, String description,
                            Player acting, Player target,
                            String assistantId, String assistantName,
                            Integer homeScoreAfter, Integer awayScoreAfter,
                            String cardType, Boolean penaltyFoul,
                            String takerId, String takerName,
                            String varType, String varDecision) {
        appendEvent(tick, type, description, acting != null ? acting.getTeam() : null,
                acting, target, assistantId, assistantName, homeScoreAfter, awayScoreAfter,
                cardType, penaltyFoul, takerId, takerName, varType, varDecision);
    }

    public void appendEvent(long tick, String type, String description,
                            String team, Player acting, Player target,
                            String assistantId, String assistantName,
                            Integer homeScoreAfter, Integer awayScoreAfter,
                            String cardType, Boolean penaltyFoul,
                            String takerId, String takerName,
                            String varType, String varDecision) {
        String playerId = acting != null ? acting.getId() : null;
        String playerName = acting != null ? acting.getLabel() : null;
        String targetId = target != null ? target.getId() : null;
        Integer skill = acting != null ? (int) acting.getSkills().technique() : null;
        Double posRow = acting != null ? acting.getPosition().getRow() : null;
        Double posCol = acting != null ? acting.getPosition().getColumn() : null;
        events.add(new MatchEvent(tick, type, description, team, playerId, playerName,
                targetId, posRow, posCol, skill, type,
                assistantId, assistantName, homeScoreAfter, awayScoreAfter,
                cardType, penaltyFoul, takerId, takerName, varType, varDecision));
    }

    public void captureSnapshot(MatchState state) {
        if (!snapshotsEnabled) {
            return;
        }
        List<PlayerSnapshot> playerSnapshots = state.getPlayers().stream()
                // ME-1 (owner 2026-10-23): a red card REMOVES the player from the pitch. He used to
                // freeze on his last spot and stay in every snapshot for the rest of the match —
                // a ghost circle the other eleven kept passing to.
                .filter(p -> !p.isSentOff())
                .map(p -> new PlayerSnapshot(
                        p.getId(), p.getLabel(), p.getTeam(), p.getRole(),
                        p.getPosition(), p.getTarget(), p.isLocked(),
                        p.getVelX(), p.getVelY()))
                .toList();

        // WHO the ball in flight is aimed at. These were all null, so a replay
        // had no idea what a pass was for: the viewer could only interpolate
        // between ball positions, and with the replay's 1:10 downsample the ball
        // travels 8+ cells between snapshots — more than the length of the pitch
        // — so a pass drawn across that gap appeared to go to a completely
        // different player than the one in the log.
        String targetPlayerId = state.getPendingReceiver() != null
                ? state.getPendingReceiver().getId() : null;
        String actionType = targetPlayerId != null ? "PASS" : null;
        Position aim = state.getReceivePoint();
        String actingPlayerId = state.getLastTouchPlayer() != null
                ? state.getLastTouchPlayer().getId() : null;

        snapshots.add(new MatchSnapshot(
                state.getMatchTicks(), 0, playerSnapshots,
                state.getBall().getPosition(), null,
                state.getBall().getBallState(state.getCarrier()),
                state.getCarrier() == null ? null : state.getCarrier().getId(),
                null, actionType, actingPlayerId, targetPlayerId, aim, aim,
                state.getPhase(), state.getHomeGoals(), state.getAwayGoals(),
                state.getMatchTicks(), state.isHalfTime(), state.isMatchFinished(),
                state.getPassAttempts(), state.getPassesCompleted(),
                state.getShotsOnTarget(),
                state.getBall().getHeight()));
    }

    public MatchRecording buildRecording(String matchId) {
        return new MatchRecording(matchId, events, snapshots);
    }

    public List<MatchEvent> getEvents() { return List.copyOf(events); }
    public List<MatchSnapshot> getSnapshots() { return List.copyOf(snapshots); }
}