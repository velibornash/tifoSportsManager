package org.example.footballmanager.newLogic.model.event;

public record DuelEvent(
    int minute,
    int tick,
    long player1Id,
    String player1Name,
    long player2Id,
    String player2Name,
    String teamSide,
    boolean attackerWon,
    String duelType
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.DUEL; }
}
