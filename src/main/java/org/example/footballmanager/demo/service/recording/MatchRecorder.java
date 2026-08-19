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
        events.add(new MatchEvent(tick, round, actionId, type, description));
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
