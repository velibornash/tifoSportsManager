package org.example.footballmanager.newLogic.model.event;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.Match;

@Getter
@Setter
public class MatchEndedEvent {
    private Long id;
    private Integer minute;
    private Integer tick;

    private Match match;

    public String getDescription() {
        if (match != null) {
            return "Match Ended: " + match.getHomeTeam().getName() + " " +
                    match.getHomeGoals() + " - " + match.getAwayGoals() + " " +
                    match.getAwayTeam().getName();
        }
        return "Match Ended";
    }
}
