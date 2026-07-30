package org.example.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class VARReviewEvent {
    private Long id;
    private Integer minute;
    private Integer tick;
    private String decision;
    private String reason;

    private Team team;
    private Player player;
    private Match match;

    public String getDescription() {
        return minute + "' VAR Review - " + (decision != null ? decision : "pending");
    }
}
