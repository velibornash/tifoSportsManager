package org.example.footballmanager.newLogic.dto;

import java.util.List;
import java.util.Map;

public record ReplayMetadataDTO(
    long matchId,
    int totalTicks,
    int totalMinutes,
    int ticksPerMinute,
    String homeTeamName,
    String awayTeamName,
    int homeGoals,
    int awayGoals,
    List<Map<String, Object>> eventSummaries
) {}
