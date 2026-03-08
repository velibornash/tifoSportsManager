package org.example.footballmanager.zox;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoxPlaybackChunkDTO {
    private Long matchId;
    private int chunkIndex;
    private long startTimeMs;
    private long endTimeMs;
    private boolean lastChunk;
    private List<ZoxReplayFrameDTO> frames;
    private Map<Long, List<ZoxReplayPositionPointDTO>> playerPositions;
    private List<ZoxReplayBallPointDTO> ballData;
    private List<ZoxReplayEventDTO> eventData;

    @JsonProperty("start_time_ms")
    public long getStartTimeMsSnakeCase() {
        return startTimeMs;
    }

    @JsonProperty("end_time_ms")
    public long getEndTimeMsSnakeCase() {
        return endTimeMs;
    }

    @JsonProperty("last_chunk")
    public boolean isLastChunkAlias() {
        return lastChunk;
    }

    @JsonProperty("players")
    public Map<Long, List<ZoxReplayPositionPointDTO>> getPlayersAlias() {
        return playerPositions;
    }

    @JsonProperty("ball")
    public List<ZoxReplayBallPointDTO> getBallAlias() {
        return ballData;
    }

    @JsonProperty("events")
    public List<ZoxReplayEventDTO> getEventsAlias() {
        return eventData;
    }
}