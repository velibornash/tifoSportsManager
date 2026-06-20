package org.example.footballmanager.newLogic.model.event;

public record SubstitutionEvent(
    int minute,
    int tick,
    long playerOutId,
    String playerOutName,
    long playerInId,
    String playerInName,
    String teamSide
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.SUBSTITUTION; }
}
