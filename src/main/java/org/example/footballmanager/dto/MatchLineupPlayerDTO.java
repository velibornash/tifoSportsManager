package org.example.footballmanager.dto;

public record MatchLineupPlayerDTO(
        Long playerId,
        String playerName,
        String position,
        String teamName,
        double grade,
        int goals,
        int assists,
        int minutesPlayed
) {}
