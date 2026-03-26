package org.example.footballmanager.zox;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoxPlaybackMetadataDTO {
    private Long matchId;
    private Long homeTeamId;
    private Long awayTeamId;
    private String homeTeamName;
    private String awayTeamName;
    private String homeFormation;
    private String awayFormation;
    private int homeGoals;
    private int awayGoals;
    private String timeStatus;
    private int ticksPerMinute;
    private int tickDurationMs;
    private int chunkDurationMs;
    private int chunkCount;
    private int totalTicks;
    private long totalDurationMs;
    private boolean replayReady;
    private String replayState;
    private String replayMessage;
    private List<ZoxReplayPlayerDTO> playersData;
    private List<ZoxReplayGoalDTO> goalsData;
    private List<ZoxReplayEventDTO> eventData;
    private List<ZoxReplayEventDTO> keyMoments;

    @JsonProperty("chunk_count")
    public int getChunkCountSnakeCase() {
        return chunkCount;
    }

    @JsonProperty("chunk_duration_ms")
    public int getChunkDurationMsSnakeCase() {
        return chunkDurationMs;
    }

    @JsonProperty("total_duration_ms")
    public long getTotalDurationMsSnakeCase() {
        return totalDurationMs;
    }

    @JsonProperty("match_time_ms")
    public long getMatchTimeMs() {
        return totalDurationMs;
    }

    @JsonProperty("players")
    public List<ZoxReplayPlayerDTO> getPlayersAlias() {
        return playersData;
    }

    @JsonProperty("goals")
    public List<ZoxReplayGoalDTO> getGoalsAlias() {
        return goalsData;
    }

    @JsonProperty("events")
    public List<ZoxReplayEventDTO> getEventsAlias() {
        return eventData;
    }
}
