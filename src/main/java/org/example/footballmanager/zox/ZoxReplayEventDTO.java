package org.example.footballmanager.zox;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoxReplayEventDTO {
    private Long eventId;
    private long timestampMs;
    private int tick;
    private int minute;
    private String clockLabel;
    private String type;
    private String description;
    private String displayCategory;
    private String importance;
    private boolean keyEvent;
    private Double xG;
    private String teamName;
    private String teamSide;
    private String homeTeamName;
    private String awayTeamName;
    private Integer homeGoals;
    private Integer awayGoals;
    private Long playerId;
    private String playerName;
    private String scorerName;
    private String assistantName;
    private String takerName;
    private String goalkeeperName;
    private Long secondaryPlayerId;
    private String secondaryPlayerName;
    private String targetPlayerName;
    private String playerOutName;
    private String playerInName;
    private String outcome;
    private Boolean scored;
    private boolean dangerous;
    private String decision;
    private String reviewTarget;
    private String overturnReason;
    private String scoreAfterGoal;
    private String scoreAfterEvent;

    @JsonProperty("timestamp")
    public long getTimestampAlias() {
        return timestampMs;
    }

    @JsonProperty("category")
    public String getCategoryAlias() {
        return displayCategory;
    }
}