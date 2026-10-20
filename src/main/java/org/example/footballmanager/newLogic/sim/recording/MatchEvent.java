package org.example.footballmanager.newLogic.sim.recording;

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
    private final String assistantId;
    private final String assistantName;
    private final Integer homeScoreAfter;
    private final Integer awayScoreAfter;
    private final String cardType;
    private final Boolean penaltyFoul;
    private final String takerId;
    private final String takerName;
    private final String varType;
    private final String varDecision;

    public MatchEvent(long tick, String type, String description,
                      String team, String playerId, String playerName,
                      String targetPlayerId, Double positionRow, Double positionColumn,
                      Integer skill, String outcome) {
        this(tick, type, description, team, playerId, playerName, targetPlayerId,
                positionRow, positionColumn, skill, outcome,
                null, null, null, null, null, null, null, null, null, null);
    }

    public MatchEvent(long tick, String type, String description,
                      String team, String playerId, String playerName,
                      String targetPlayerId, Double positionRow, Double positionColumn,
                      Integer skill, String outcome,
                      String assistantId, String assistantName,
                      Integer homeScoreAfter, Integer awayScoreAfter,
                      String cardType, Boolean penaltyFoul,
                      String takerId, String takerName,
                      String varType, String varDecision) {
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
        this.assistantId = assistantId;
        this.assistantName = assistantName;
        this.homeScoreAfter = homeScoreAfter;
        this.awayScoreAfter = awayScoreAfter;
        this.cardType = cardType;
        this.penaltyFoul = penaltyFoul;
        this.takerId = takerId;
        this.takerName = takerName;
        this.varType = varType;
        this.varDecision = varDecision;
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
    public String getAssistantId() { return assistantId; }
    public String getAssistantName() { return assistantName; }
    public Integer getHomeScoreAfter() { return homeScoreAfter; }
    public Integer getAwayScoreAfter() { return awayScoreAfter; }
    public String getCardType() { return cardType; }
    public Boolean getPenaltyFoul() { return penaltyFoul; }
    public String getTakerId() { return takerId; }
    public String getTakerName() { return takerName; }
    public String getVarType() { return varType; }
    public String getVarDecision() { return varDecision; }
}