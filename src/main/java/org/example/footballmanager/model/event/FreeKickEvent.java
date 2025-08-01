package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.simulator.MatchContext;

@Getter
@Setter
@Entity
public class FreeKickEvent extends MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private boolean direct; // true = šut direktno, false = centaršut
    private boolean dangerous;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player taker;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player player;

    @Override
    public void apply(MatchContext context) {
        context.setBallPosition("box");
    }

    @Override
    public String getDescription() {
        return minute + "' Slobodan udarac - " + taker.getName() + (direct ? " (direktan)" : " (indirektan)");
    }
}