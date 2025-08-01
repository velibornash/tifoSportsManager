package org.example.footballmanager.simulator;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Team;

@Getter
@Setter
public class MatchContext {
    private Match match;
    private int currentMinute;
    private double homeMomentum = 1.0;
    private double awayMomentum = 1.0;
    private double fatigueFactor = 1.0;
    private String ballPosition; // "left_wing", "center", "right_wing", "box"
    private Team possessionTeam;

    public MatchContext(Match match) {
        this.match = match;
    }

    public void goalScored(Team scoringTeam) {
        if (scoringTeam.equals(match.getHomeTeam())) {
            homeMomentum += 0.1;
            awayMomentum -= 0.1;
        } else {
            awayMomentum += 0.1;
            homeMomentum -= 0.1;
        }
        homeMomentum = Math.max(0.8, Math.min(1.2, homeMomentum));
        awayMomentum = Math.max(0.8, Math.min(1.2, awayMomentum));
    }
}
