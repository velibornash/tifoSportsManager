package org.example.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class RedCardEvent {
    private Long id;
    private Integer minute;
    private Integer tick;

    private Player player;
    private Team team;
    private Match match;

    public String getDescription() {
        String name = player != null ? player.getName() : "Unknown";
        return minute + "' Red card - " + name;
    }
}
