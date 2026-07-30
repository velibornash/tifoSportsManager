package org.example.footballmanager.newLogic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayMetadataDTO(
    long matchId,
    int totalTicks,
    int totalMinutes,
    int ticksPerMinute,
    String homeTeamName,
    String awayTeamName,
    int homeGoals,
    int awayGoals,
    List<Map<String, Object>> eventSummaries,
    String replayState,
    long totalDurationMs,
    int chunkDurationMs,
    int chunkCount,
    String homeFormation,
    String awayFormation,
    List<Map<String, Object>> players,
    List<Map<String, Object>> goals,
    List<Map<String, Object>> events
) {}
