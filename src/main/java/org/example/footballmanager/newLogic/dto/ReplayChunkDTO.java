package org.example.footballmanager.newLogic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayChunkDTO(
    long matchId,
    int chunkIndex,
    int startTick,
    int endTick,
    Map<String, List<Map<String, Object>>> players,
    List<Map<String, Object>> ball,
    List<Map<String, Object>> events
) {}
