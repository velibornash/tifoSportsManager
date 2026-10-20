package org.example.footballmanager.newLogic.model.event;

public record CardEvent(
    int minute,
    int tick,
    long playerId,
    String playerName,
    String teamSide,
    CardEvent.CardType cardType
) implements MatchEvent {
    public enum CardType { YELLOW, RED }

    @Override
    public MatchEventType type() {
        return cardType == CardType.RED ? MatchEventType.RED_CARD : MatchEventType.YELLOW_CARD;
    }
}
