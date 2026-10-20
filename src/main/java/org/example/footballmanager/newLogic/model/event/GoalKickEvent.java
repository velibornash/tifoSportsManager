package org.example.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class GoalKickEvent {
    private Long id;
    private Integer minute;
    private Integer tick;

    private Team team;

    private Player goalkeeper;

    private Match match;

    public String getDescription() {
        String gkName = goalkeeper != null ? goalkeeper.getName() : "Goalkeeper";
        String teamName = team != null ? team.getName() : "Unknown team";
        return minute + "' Goal kick for " + teamName + " (" + gkName + ")";
    }
}
