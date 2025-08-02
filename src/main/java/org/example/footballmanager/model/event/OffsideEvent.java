package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.simulator.MatchContext;

@Getter
@Setter
@Entity
public class OffsideEvent extends MatchEvent{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player offender;

    @Override
    public void apply(MatchContext context) {
        context.getMatch().getOffsides().add(this);
    }

    @Override
    public String getDescription() {
        return String.format("\uD83D\uDEA9 %d' Offside: %s", minute, offender.getName());
    }
}
