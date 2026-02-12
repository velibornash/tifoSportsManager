package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import jakarta.persistence.Entity;
@Entity
@Getter
@Setter
public class ShotOnTargetEvent extends MatchEvent {
    @ManyToOne
    @JsonIgnore
    private Team team;
    @ManyToOne
    @JsonIgnore
    private Player shooter;

    @Override
    public void apply() {
        match.getAllMatchEvents().add(this);
        match.getShotsOnTarget().add(this);
    }

    @Override
    public String getDescription() {
        return minute + "' Šut u okvir - " + shooter.getName();
    }
}
