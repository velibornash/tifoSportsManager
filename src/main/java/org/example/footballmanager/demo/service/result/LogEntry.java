package org.example.footballmanager.demo.service.result;

/**
 * Immutable record of one significant simulation event, formatted
 * for human-readable debugging output.
 */
public class LogEntry {

    public enum EntryType {
        DECISION,
        ACTION_EXECUTION,
        ACTION_OUTCOME,
        DUEL,
        FOUL,
        CARD,
        CORNER,
        THROW_IN,
        GOAL_KICK,
        GOAL,
        RESTART,
        POSSESSION,
        INFO,
        CHASE
    }

    private final String wallClock;     // real-time timestamp (HH:mm:ss.SSS)
    private final EntryType type;
    private final String matchClock;    // match minute:second (e.g. "45:23")
    private final String channel;       // logical channel / subsystem
    private final String description;   // human-readable description

    // Optional context fields
    private final String team;
    private final String playerId;
    private final String playerName;
    private final String targetPlayerId;
    private final String targetPlayerName;
    private final Object context;       // Action, DuelResolver.DuelResult, List<DecisionOption>, etc.

    private LogEntry(String wallClock, EntryType type, String matchClock, String channel,
                     String description, String team, String playerId, String playerName,
                     String targetPlayerId, String targetPlayerName, Object context) {
        this.wallClock = wallClock;
        this.type = type;
        this.matchClock = matchClock;
        this.channel = channel;
        this.description = description;
        this.team = team;
        this.playerId = playerId;
        this.playerName = playerName;
        this.targetPlayerId = targetPlayerId;
        this.targetPlayerName = targetPlayerName;
        this.context = context;
    }

    public static Builder builder(String wallClock, EntryType type, String matchClock, String channel, String description) {
        return new Builder(wallClock, type, matchClock, channel, description);
    }

    public static final class Builder {
        private final String wallClock;
        private final EntryType type;
        private final String matchClock;
        private final String channel;
        private final String description;
        private String team;
        private String playerId;
        private String playerName;
        private String targetPlayerId;
        private String targetPlayerName;
        private Object context;

        private Builder(String wallClock, EntryType type, String matchClock, String channel, String description) {
            this.wallClock = wallClock;
            this.type = type;
            this.matchClock = matchClock;
            this.channel = channel;
            this.description = description;
        }

        public Builder team(String team) { this.team = team; return this; }
        public Builder player(String id, String name) { this.playerId = id; this.playerName = name; return this; }
        public Builder targetPlayer(String id, String name) { this.targetPlayerId = id; this.targetPlayerName = name; return this; }
        public Builder context(Object context) { this.context = context; return this; }

        public LogEntry build() {
            return new LogEntry(wallClock, type, matchClock, channel, description, team,
                    playerId, playerName, targetPlayerId, targetPlayerName, context);
        }
    }

    // --- Getters ---

    public String getWallClock() { return wallClock; }
    public EntryType getType() { return type; }
    public String getMatchClock() { return matchClock; }
    public String getChannel() { return channel; }
    public String getDescription() { return description; }
    public String getTeam() { return team; }
    public String getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public String getTargetPlayerName() { return targetPlayerName; }
    public Object getContext() { return context; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(matchClock).append("]");
        if (wallClock != null) sb.append(" (").append(wallClock).append(")");
        if (channel != null && !channel.isEmpty()) sb.append(" <").append(channel).append(">");
        if (team != null) sb.append(" {").append(team).append("}");
        if (playerName != null) sb.append(" ").append(playerName);
        sb.append(" ").append(description);
        if (targetPlayerName != null) sb.append(" → ").append(targetPlayerName);
        return sb.toString();
    }
}
