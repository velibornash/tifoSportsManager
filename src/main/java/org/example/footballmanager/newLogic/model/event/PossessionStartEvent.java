package org.example.footballmanager.newLogic.model.event;

public record PossessionStartEvent(
    int minute,
    int tick,
    long chainId,
    String teamSide,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.POSSESSION_START; }
}
