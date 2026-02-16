package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;

import java.util.Random;

@Entity
@Getter
@Setter
public class ChanceEvent extends MatchEvent {
    @ManyToOne
    @JsonIgnore
    private Player player;

    @ManyToOne
    @JsonIgnore
    private Team team;

    private boolean dangerous;

    @Override
    public void apply() {
        match.getChances().add(this);
        match.getAllMatchEvents().add(this);
    }

    @Override
        public String getDescription() {
            return minute + "' Posed - " + team.getName();
        }
}