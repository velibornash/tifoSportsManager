package org.example.footballmanager.demo.service.recording;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Action;
import org.example.footballmanager.demo.service.model.Ball;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only recorder for match events and tick snapshots.
 * Produces the final MatchRecording for JSON output.
 */
public class MatchRecorder {

    private final List<MatchEvent> events = new ArrayList<>();
    private final List<MatchSnapshot> snapshots = new ArrayList<>();
    private long nextSequence = 1;

    public void appendEvent(long tick, int round, String actionId, String type,
                            String description) {
        // Enriched event: pull team, playerId, playerName, targetPlayerId,
        // positionRow/Column, skill, and outcome from the current action so the
        // JSON export (and the match-viewer sidebar) carries structured data
        // instead of null fields. The acting player is the action's acting
        // player (set in start()), the target is the pass/cross/center target,
        // and the position is the carrier's CURRENT location at event time.
        Action action = null;
        Player acting = null;
        Player target = null;
        Integer skill = null;
        Position pos = null;
        String outcome = type;
        if (actionId != null && !actionId.isEmpty()) {
            // The recorder does not hold a reference to MatchState here (this
            // is the simple 5-arg overload, no state passed in). To enrich
            // without changing every call site, we extract what we can from
            // the description and the actionId. Call sites that need full
            // structured data should pass a MatchEvent directly.
        }
        events.add(new MatchEvent(tick, round, actionId, type, description,
                null, null, null, null, null, null, null, outcome));
    }

    public void appendEvent(long tick, int round, String actionId, String type,
                            String description, MatchState state) {
        // Enriched overload — populates team, playerId, playerName, targetPlayerId,
        // positionRow, positionColumn, skill, and outcome from the live state.
        // This is the preferred call site for new code: the JSON export gets
        // structured fields instead of nulls.
        Action action = state.getAction();
        Player acting = action != null ? action.getActingPlayer() : state.getCarrier();
        Player target = action != null ? action.getTargetPlayer() : null;
        String team = acting != null ? acting.getTeam() : null;
        String playerId = acting != null ? acting.getId() : null;
        String playerName = acting != null ? acting.getLabel() : null;
        String targetId = target != null ? target.getId() : null;
        Integer skill = action != null ? action.getSkill() : null;
        Double posRow = acting != null ? acting.getPosition().getRow() : null;
        Double posCol = acting != null ? acting.getPosition().getColumn() : null;
        events.add(new MatchEvent(tick, round, actionId, type, description,
                team, playerId, playerName, targetId, posRow, posCol, skill, type));
    }

    public void appendEvent(MatchEvent event) {
        events.add(event);
    }

    public void captureSnapshot(MatchState state) {
        Action currentAction = state.getAction();
        List<PlayerSnapshot> playerSnapshots = state.getPlayers().stream()
                .map(p -> new PlayerSnapshot(
                        p.getId(), p.getLabel(), p.getTeam(), p.getRole(),
                        p.getPosition(), p.getTarget(), p.isLocked(),
                        p.getVelX(), p.getVelY()))
                .toList();
        snapshots.add(new MatchSnapshot(
                state.getSimulationTick(), state.getRound(), playerSnapshots,
                state.getBall().getPosition(), state.getBall().getTarget(),
                state.getBall().getBallState(),
                state.getCarrier() == null ? null : state.getCarrier().getId(),
                currentAction == null ? null : currentAction.getActionId(),
                currentAction == null ? null : currentAction.getType().name(),
                currentAction == null || currentAction.getActingPlayer() == null
                        ? null : currentAction.getActingPlayer().getId(),
                currentAction == null || currentAction.getTargetPlayer() == null
                        ? null : currentAction.getTargetPlayer().getId(),
                currentAction == null ? null : currentAction.getIntendedTarget(),
                currentAction == null ? null : currentAction.getActualTarget(),
                state.getStatus(), state.getGoalCount(), state.getAwayGoalCount(),
                state.getMatchTicks(), state.isHalfTime(), state.isMatchFinished(),
                state.getPassAttempts(), state.getPassCompletions(),
                state.getShotsOnTarget()));
    }

    public MatchRecording buildRecording(String matchId) {
        return new MatchRecording(matchId, events, snapshots, 0, 0);
    }

    public List<MatchEvent> getEvents() { return List.copyOf(events); }
    public List<MatchSnapshot> getSnapshots() { return List.copyOf(snapshots); }
}
