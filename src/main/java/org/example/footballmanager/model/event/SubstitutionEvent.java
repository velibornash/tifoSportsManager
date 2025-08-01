package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.simulator.MatchContext;

@Entity
@Getter
@Setter
public class SubstitutionEvent extends MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player playerOut;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player playerIn;

    @Override
    public void apply(MatchContext context) {
        context.getMatch().getSubstitutions().add(this);
        context.getMatch().getAllMatchEvents().add(this);      // OBAVEZNO za izveštaj

    }

    @Override
    public String getDescription() {
        return minute + "' IZLAZI " + playerOut.getName() + ", ULAZI " + playerIn.getName();
    }
}