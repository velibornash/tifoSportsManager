package org.example.footballmanager.newLogic.model.event;

public record PassInterceptedEvent(
    int minute,
    int tick,
    long chainId,
    long passerId,
    String passerName,
    long interceptorId,
    String interceptorName,
    String interceptorTeamSide,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.PASS_INTERCEPTED; }
}
