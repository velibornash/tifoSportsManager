package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;

@Entity
@Getter
@Setter
public class GoalEvent extends MatchEvent {

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player scorer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player assistant;

    private String scoreAfterGoal;
    private boolean scored;

    @Override
    public void apply() {
        scored = true;
    }

    @Override
    public String getDescription() {
        return String.format("%d' ⚽ Goal - %s", getMinute(), scorer != null ? scorer.getName() : "N/A");
    }
}
