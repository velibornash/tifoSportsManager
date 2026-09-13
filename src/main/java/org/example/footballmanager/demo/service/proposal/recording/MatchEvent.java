package org.example.footballmanager.demo.service.proposal.recording;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Single match event with structured fields for the viewer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatchEvent {
    private final long tick;
    private final String type;
    private final String description;
    private final String team;
    private final String playerId;
    private final String playerName;
    private final String targetPlayerId;
    private final Double positionRow;
    private final Double positionColumn;
    private final Integer skill;
    private final String outcome;

    public MatchEvent(long tick, String type, String description,
                      String team, String playerId, String playerName,
                      String targetPlayerId, Double positionRow, Double positionColumn,
                      Integer skill, String outcome) {
        this.tick = tick;
        this.type = type;
        this.description = description;
        this.team = team;
        this.playerId = playerId;
        this.playerName = playerName;
        this.targetPlayerId = targetPlayerId;
        this.positionRow = positionRow;
        this.positionColumn = positionColumn;
        this.skill = skill;
        this.outcome = outcome;
    }

    public long getTick() { return tick; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public String getTeam() { return team; }
    public String getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public Double getPositionRow() { return positionRow; }
    public Double getPositionColumn() { return positionColumn; }
    public Integer getSkill() { return skill; }
    public String getOutcome() { return outcome; }
}