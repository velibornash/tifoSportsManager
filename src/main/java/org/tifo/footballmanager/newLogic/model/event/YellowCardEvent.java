package org.tifo.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class YellowCardEvent {
    private Long id;
    private Integer minute;
    private Integer tick;

    private Player player;
    private Team team;
    private Match match;

    public String getDescription() {
        String name = player != null ? player.getName() : "Unknown";
        return minute + "' Yellow card - " + name;
    }
}
