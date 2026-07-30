package org.example.footballmanager.newLogic.model.event;

public record PassIncompleteEvent(
    int minute,
    int tick,
    long chainId,
    long passerId,
    String passerName,
    String teamSide,
    String reason,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.PASS_INCOMPLETE; }
}
