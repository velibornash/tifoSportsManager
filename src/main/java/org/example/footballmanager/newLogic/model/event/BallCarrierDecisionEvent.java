package org.example.footballmanager.newLogic.model.event;

public record BallCarrierDecisionEvent(
    int minute,
    int tick,
    long chainId,
    long carrierId,
    String carrierName,
    String teamSide,
    String action,        // BallAction name (SHORT_PASS, LONG_PASS, ...)
    String reason,        // human-readable explanation (pressure, openness, ...)
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.BALL_CARRIER_DECISION; }
}
