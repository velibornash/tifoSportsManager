package org.example.footballmanager.newLogic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveTickDTO(
    long matchId,
    int tick,
    int minute,
    int homeGoals,
    int awayGoals,
    String possessionTeam,
    List<Map<String, Object>> players,
    Map<String, Object> ball,
    String eventType,
    Map<String, Object> eventData,
    boolean matchFinished,
    boolean offsideActive,
    double offsideLineX,
    String offsideTeam
) {}