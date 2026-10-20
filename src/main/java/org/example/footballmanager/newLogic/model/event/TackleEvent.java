package org.example.footballmanager.newLogic.model.event;

public record TackleEvent(
    int minute,
    int tick,
    long chainId,
    long defenderId,
    String defenderName,
    String defenderTeamSide,
    long attackerId,
    String attackerName,
    boolean success,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.TACKLE; }
}
