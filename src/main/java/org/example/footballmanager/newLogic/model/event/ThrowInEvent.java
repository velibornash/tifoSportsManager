package org.example.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class ThrowInEvent {
    private Long id;
    private Integer minute;
    private Integer tick;

    private Team team;

    private Player taker;

    private Match match;

    public String getDescription() {
        String takerName = taker != null ? taker.getName() : "Unknown";
        String teamName = team != null ? team.getName() : "Unknown team";
        return minute + "' Throw-in for " + teamName + " (" + takerName + ")";
    }
}
