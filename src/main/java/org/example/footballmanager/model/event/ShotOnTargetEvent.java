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
public class ShotOnTargetEvent extends MatchEvent {
    @ManyToOne
    private Team team;
    @ManyToOne
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
