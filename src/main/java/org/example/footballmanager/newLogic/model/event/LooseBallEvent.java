package org.example.footballmanager.newLogic.model.event;

public record LooseBallEvent(
    int minute,
    int tick,
    long chainId,
    long player1Id,
    String player1Name,
    long player2Id,
    String player2Name,
    long winnerId,
    String winnerName,
    String duelType,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.LOOSE_BALL; }
}
