package org.example.footballmanager.newLogic.model.event;

public record PossessionEndEvent(
    int minute,
    int tick,
    long chainId,
    String teamSide,
    int passCount,
    String reason,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.POSSESSION_END; }
}
