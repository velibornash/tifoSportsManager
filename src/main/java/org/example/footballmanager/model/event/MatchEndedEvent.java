package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.simulator.MatchContext;

@Getter
@Setter
@Entity
public class MatchEndedEvent extends MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public void apply(MatchContext context) {
        // Nema dodatne logike za kraj utakmice, samo označava kraj
        context.getMatch().setPlayed(true); // Potvrđuje da je utakmica završena
    }

    @Override
    public String getDescription() {
        Match match = getMatch();
        return String.format("\uD83C\uDFC1 %d' Match Ended: %s %d - %d %s",
                minute,
                match.getHomeTeam().getName(),
                match.getHomeGoals(),
                match.getAwayGoals(),
                match.getAwayTeam().getName());
    }
}