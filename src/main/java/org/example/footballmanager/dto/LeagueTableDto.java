package org.example.footballmanager.dto;

public record LeagueTableDto(
        String name,
        Integer points,
        Integer goalDifference
) {}