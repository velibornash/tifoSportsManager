package org.example.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class ChanceEvent {
    private Long id;
    private Integer minute;
    private Integer tick;
    private Double xG;

    private Team team;
    private Player player;
    private Match match;

    public String getDescription() {
        String name = player != null ? player.getName() : "Unknown";
        return minute + "' Chance created - " + name;
    }
}
