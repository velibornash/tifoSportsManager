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
public class ThrowInEvent extends MatchEvent {

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player taker;

    @Override
    public void apply() {
    }

    @Override
    public String getDescription() {
        String takerName = taker != null ? taker.getName() : "Unknown";
        String teamName = team != null ? team.getName() : "Unknown team";
        return minute + "' Throw-in for " + teamName + " (" + takerName + ")";
    }
}
