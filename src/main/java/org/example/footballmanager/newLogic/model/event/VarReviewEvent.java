package org.example.footballmanager.newLogic.model.event;

public record VarReviewEvent(
    int minute,
    int tick,
    long chainId,
    String teamSide,
    String decision,
    String reason,
    String description
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.VAR_REVIEW; }
}
