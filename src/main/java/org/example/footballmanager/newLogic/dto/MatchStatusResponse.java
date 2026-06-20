package org.example.footballmanager.newLogic.dto;

public record MatchStatusResponse(
    long matchId,
    String status,
    String homeTeamName,
    String awayTeamName,
    int homeGoals,
    int awayGoals,
    boolean finished
) {}
