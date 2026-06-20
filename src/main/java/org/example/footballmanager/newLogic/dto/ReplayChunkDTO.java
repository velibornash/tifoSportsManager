package org.example.footballmanager.newLogic.dto;

import org.example.footballmanager.newLogic.model.TickSnapshot;

import java.util.List;

public record ReplayChunkDTO(
    long matchId,
    int chunkIndex,
    int startTick,
    int endTick,
    List<TickSnapshot> ticks
) {}
