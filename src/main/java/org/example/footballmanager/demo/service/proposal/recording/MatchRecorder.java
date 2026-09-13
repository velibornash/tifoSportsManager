package org.example.footballmanager.demo.service.proposal.recording;

import org.example.footballmanager.demo.service.proposal.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only recorder for match events and tick snapshots.
 * Produces the final MatchRecording for JSON output.
 */
public class MatchRecorder {

    private final List<MatchEvent> events = new ArrayList<>();
    private final List<MatchSnapshot> snapshots = new ArrayList<>();

    public void appendEvent(long tick, String type, String description) {
        events.add(new MatchEvent(tick, type, description, null, null, null, null, null, null, null, type));
    }

    public void appendEvent(long tick, String type, String description, MatchState state) {
        Player acting = state.getCarrier();
        Player target = state.getPendingReceiver();
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

    public void captureSnapshot(MatchState state) {
        List<PlayerSnapshot> playerSnapshots = state.getPlayers().stream()
                .map(p -> new PlayerSnapshot(
                        p.getId(), p.getLabel(), p.getTeam(), p.getRole(),
                        p.getPosition(), p.getTarget(), p.isLocked(),
                        p.getVelX(), p.getVelY()))
                .toList();
        snapshots.add(new MatchSnapshot(
                state.getMatchTicks(), 0, playerSnapshots,
                state.getBall().getPosition(), null,
                state.getBall().getBallState(state.getCarrier()),
                state.getCarrier() == null ? null : state.getCarrier().getId(),
                null, null, null,
                state.getCarrier() == null ? null : state.getCarrier().getId(),
                null, null,
                state.getPhase(), state.getHomeGoals(), state.getAwayGoals(),
                state.getMatchTicks(), false, false,
                state.getPassAttempts(), state.getPassesCompleted(),
                state.getShotsOnTarget()));
    }

    public MatchRecording buildRecording(String matchId) {
        return new MatchRecording(matchId, events, snapshots);
    }

    public List<MatchEvent> getEvents() { return List.copyOf(events); }
    public List<MatchSnapshot> getSnapshots() { return List.copyOf(snapshots); }
}