package org.example.footballmanager.newLogic.model;

import java.util.List;

public record TickSnapshot(
    int tick,
    int minute,
    List<PlayerSnapshot> players,
    BallState ball,
    Long carrierId,
    Long pendingReceiverId,
    boolean ballInTransit,
    String activeEventType
) {
}
