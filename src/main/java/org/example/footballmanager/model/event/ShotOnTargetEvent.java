package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
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
public class ShotOnTargetEvent extends MatchEvent {
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player shooter;

    @Column(name = "xg")
    private double xG;

    @Override
    public void apply() {
    }

    @Override
    public String getDescription() {
        return minute + "' 🎯 Shot on target - " + shooter.getName();
    }
}
