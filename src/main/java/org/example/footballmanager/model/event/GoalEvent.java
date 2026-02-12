package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;

@Entity
@Getter
@Setter
public class GoalEvent extends MatchEvent {

    @ManyToOne
    @JsonIgnore   // ← Sprečava ciklus preko team → players
    private Team team;

    @ManyToOne
    @JsonIgnore   // ← Sprečava ciklus preko scorer → team → players
    private Player scorer;

    @ManyToOne
    @JsonIgnore   // ← Isto za assistant
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
        return String.format("%d' ⚽ %s", getMinute(), scorer != null ? scorer.getName() : "N/A");
    }
}