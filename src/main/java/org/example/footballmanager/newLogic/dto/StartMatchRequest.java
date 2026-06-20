package org.example.footballmanager.newLogic.dto;

public record StartMatchRequest(
    String homeTeamName,
    String awayTeamName,
    Long homeTeamId,
    Long awayTeamId,
    String formation
) {
    public StartMatchRequest {
        formation = formation != null ? formation : "4-3-3";
    }

    public StartMatchRequest(String homeTeamName, String awayTeamName) {
        this(homeTeamName, awayTeamName, null, null, "4-3-3");
    }
}
