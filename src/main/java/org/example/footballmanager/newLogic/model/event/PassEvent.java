package org.example.footballmanager.newLogic.model.event;

public record PassEvent(
    int minute,
    int tick,
    long passerId,
    String passerName,
    Long receiverId,
    String receiverName,
    String teamSide,
    boolean completed,
    boolean intercepted,
    Long interceptorId
) implements MatchEvent {
    public static PassEvent completed(int minute, int tick, long passerId, String passerName,
                                       long receiverId, String receiverName, String teamSide) {
        return new PassEvent(minute, tick, passerId, passerName, receiverId, receiverName, teamSide, true, false, null);
    }

    public static PassEvent intercepted(int minute, int tick, long passerId, String passerName,
                                         long receiverId, String receiverName, String teamSide,
                                         long interceptorId) {
        return new PassEvent(minute, tick, passerId, passerName, receiverId, receiverName, teamSide, false, true, interceptorId);
    }

    @Override
    public MatchEventType type() { return intercepted ? MatchEventType.INTERCEPTION : MatchEventType.PASS; }
}
