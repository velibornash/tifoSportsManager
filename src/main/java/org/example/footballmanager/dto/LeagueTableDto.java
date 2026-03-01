package org.example.footballmanager.dto;

public record LeagueTableDto(
        String name,
        Integer points,
        Integer goalsScored,
        Integer goalsConceded,
        Integer goalDifference,
        Integer wins,
        Integer draws,
        Integer losses,
        Integer position
) {}