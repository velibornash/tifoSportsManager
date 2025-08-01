package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.simulator.MatchContext;

@Getter
@Setter
@Entity
public class RedCardEvent extends MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player player;

    @Override
    public void apply(MatchContext context) {
        context.getMatch().getRedCards().add(this);
    }

    @Override
    public String getDescription() {
        return minute + "' 🔴 Crveni karton: " + player.getName();
    }
}