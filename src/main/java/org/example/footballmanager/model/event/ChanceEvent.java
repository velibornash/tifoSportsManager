package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;

import java.util.Random;

@Getter
@Setter
@Entity
public class ChanceEvent extends MatchEvent {
    @ManyToOne
    private Player player;

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