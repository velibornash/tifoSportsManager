package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;

import java.util.Random;

@Getter
@Setter
@Entity
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
    }

    private static final String[] chanceTypes = {
            "dribling", "centaršut", "dupli pas", "solo prodor", "ubacivanje iz auta"
    };
    @Override
    public String getDescription() {
        String type = chanceTypes[new Random().nextInt(chanceTypes.length)];
        return String.format("%d' Šansa (%s) - %s", minute, type, player != null ? player.getName() : "Unknown");
    }

}