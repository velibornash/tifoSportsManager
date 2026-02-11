package org.example.footballmanager.model.event;

import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import jakarta.persistence.Entity;
@Entity
@Getter
@Setter
public class GoalEvent extends MatchEvent {
    @ManyToOne
    private Team team;
    @ManyToOne
    private Player scorer;
    @ManyToOne
    private Player assistant;
    private String scoreAfterGoal;
    private boolean scored;

    @Override
    public void apply() {
        match.getAllMatchEvents().add(this);
        match.getGoals().add(this);

        if (team.equals(match.getHomeTeam())) {
            match.setHomeGoals(match.getHomeGoals() + 1);
        } else {
            match.setAwayGoals(match.getAwayGoals() + 1);
        }
    }

    public boolean isScored() {
        scored = true;
        return scored;
    }


    @Override
    public String getDescription() {
        return String.format("%d' ⚽ %s", getMinute(), scorer.getName());
    }
}
