package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import jakarta.persistence.Entity;
@Entity
@Getter
@Setter
public class ShotOffTargetEvent extends MatchEvent {
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player shooter;

    @Override
    public void apply() {

    }

    @Override
    public String getDescription() {
        return minute + "' Promašaj - " + shooter.getName();
    }
}
