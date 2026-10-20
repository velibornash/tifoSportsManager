package org.example.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class ShotOnTargetEvent {
    private Long id;
    private Integer minute;
    private Integer tick;
    private Double xG;

    private Team team;
    private Player shooter;
    private Match match;

    public String getDescription() {
        String name = shooter != null ? shooter.getName() : "Unknown";
        return minute + "' Shot on target - " + name;
    }
}
