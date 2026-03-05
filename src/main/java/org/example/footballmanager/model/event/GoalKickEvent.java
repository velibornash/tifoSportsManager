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
public class GoalKickEvent extends MatchEvent {

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player goalkeeper;

    @Override
    public void apply() {
    }

    @Override
    public String getDescription() {
        String gkName = goalkeeper != null ? goalkeeper.getName() : "Goalkeeper";
        String teamName = team != null ? team.getName() : "Unknown team";
        return minute + "' Goal kick for " + teamName + " (" + gkName + ")";
    }
}
