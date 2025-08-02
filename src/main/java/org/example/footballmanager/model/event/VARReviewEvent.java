package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.simulator.MatchContext;

@Getter
@Setter
@Entity
public class VARReviewEvent extends MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    int number;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private GoalEvent reviewedGoalEvent;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private PenaltyEvent reviewedPenaltyEvent;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private OffsideEvent reviewedOffsideEvent;

    private String decision; // "Overturned" ili "Confirmed"

    @Override
    public void apply(MatchContext context) {
        Match match = context.getMatch();
        if ("Overturned".equals(decision)) {
            if (number==1) {
                match.getGoalEvents().remove(reviewedGoalEvent); // Ukloni gol ako je poništen
                match.setHomeGoals((int) match.getGoalEvents().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoalEvents().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());
            } else if (number==2) {
                match.getPenalties().remove(reviewedPenaltyEvent); // Ukloni penal ako je poništen
            }
            else {
                match.getOffsides().remove(reviewedOffsideEvent); // Ukloni off ako je poništen
                match.setHomeGoals((int) match.getGoalEvents().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoalEvents().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());
            }
        }
        // Dodaj u listu VAR eventova (pretpostavljam da Match ima getVars())
        if (match.getVars() != null) {
            match.getVars().add(this);
        }
    }

    @Override
    public String getDescription() {
        return String.format("\uD83D\uDCF9 %d' VAR Review: %s - %s", minute, decision, reviewedGoalEvent != null ? reviewedGoalEvent.getDescription() : "No event");
    }
}