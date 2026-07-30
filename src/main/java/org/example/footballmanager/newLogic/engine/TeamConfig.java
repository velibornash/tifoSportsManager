package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.Team;

import java.util.List;

public record TeamConfig(
    String formation,
    List<Long> startingXI,
    List<Long> bench,
    String teamStyle,
    int pressingIntensity,
    int defensiveLine,
    int width,
    int tempo,
    String buildUpPreference
) {
    public static TeamConfig fromTeam(Team team) {
        return new TeamConfig(
            team.getFormation() != null ? team.getFormation() : "4-3-3",
            team.startingXI().stream().map(p -> p.id()).toList(),
            team.substitutes().stream().map(p -> p.id()).toList(),
            "BALANCED",
            5,
            5,
            5,
            5,
            "MIXED"
        );
    }
}
