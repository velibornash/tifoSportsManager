package org.example.footballmanager.newLogic.model.event;

public record GoalEvent(
    int minute,
    int tick,
    long scorerId,
    String scorerName,
    Long assistantId,
    String assistantName,
    String teamSide,
    double xG,
    int homeScoreAfter,
    int awayScoreAfter
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.GOAL; }


}
