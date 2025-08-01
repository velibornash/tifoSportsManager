package org.example.footballmanager.model.event;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.simulator.MatchContext;

import java.util.Random;

@Getter
@Setter
@Entity
@NoArgsConstructor
public class ChanceEvent extends MatchEvent {

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player creator;

    private static final String[] chanceTypes = {
            "dribling", "centaršut", "dupli pas", "solo prodor", "ubacivanje iz auta"
    };

    @Override
    public void apply(MatchContext context) {
        context.getMatch().getChances().add(this);
    }

    @Override
    public String getDescription() {
        String type = chanceTypes[new Random().nextInt(chanceTypes.length)];
        return String.format("%d' Šansa (%s) - %s", minute, type, creator != null ? creator.getName() : "Unknown");
    }

    public String getPlayerName() {
        return creator != null ? creator.getName() : "Unknown";
    }
}