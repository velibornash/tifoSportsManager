package org.example.footballmanager.dto;

public record LeagueTableDTO(
        Long teamId,
        String name,
        Integer points,
        Integer goalsScored,
        Integer goalsConceded,
        Integer goalDifference,
        Integer wins,
        Integer draws,
        Integer losses,
        Integer position,
        Boolean humanControlled
) {}