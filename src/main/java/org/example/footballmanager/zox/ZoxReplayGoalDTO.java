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
public class ZoxReplayGoalDTO {
    private long timestampMs;
    private int tick;
    private int minute;
    private String teamSide;
    private String teamName;
    private Long playerId;
    private String playerName;
    private String scoreAfterGoal;
    private int homeScore;
    private int awayScore;

    @JsonProperty("time")
    public long getTimeAlias() {
        return timestampMs;
    }

    @JsonProperty("player_id")
    public Long getPlayerIdAlias() {
        return playerId;
    }

    @JsonProperty("player_name")
    public String getPlayerNameAlias() {
        return playerName;
    }
}