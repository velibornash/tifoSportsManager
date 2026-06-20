package org.example.footballmanager.newLogic.model.event;

public record SetPieceEvent(
    int minute,
    int tick,
    String teamSide,
    Long takerId,
    String takerName,
    SetPieceType setPieceType,
    double x,
    double y
) implements MatchEvent {
    public enum SetPieceType { CORNER, THROW_IN, GOAL_KICK, FREE_KICK }

    @Override
    public MatchEventType type() {
        return switch (setPieceType) {
            case CORNER -> MatchEventType.CORNER;
            case THROW_IN -> MatchEventType.THROW_IN;
            case GOAL_KICK -> MatchEventType.GOAL_KICK;
            case FREE_KICK -> MatchEventType.FREE_KICK;
        };
    }
}
