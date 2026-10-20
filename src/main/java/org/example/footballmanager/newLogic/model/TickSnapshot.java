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
    public static TickSnapshot capture(int tick, int minute, List<PlayerSnapshot> players,
                                        BallState ball, Long carrierId, Long pendingReceiverId,
                                        boolean ballInTransit, String activeEventType) {
        List<PlayerSnapshot> copies = players.stream().map(PlayerSnapshot::copy).toList();
        return new TickSnapshot(tick, minute, copies, ball, carrierId, pendingReceiverId,
            ballInTransit, activeEventType);
    }
}
