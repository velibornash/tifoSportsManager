package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class VARReviewEvent extends MatchEvent {
    int number;


    @ManyToOne(cascade = CascadeType.ALL)
    @JsonIgnore
    private GoalEvent reviewedGoalEvent;

    @ManyToOne(cascade = CascadeType.ALL)
    @JsonIgnore
    private PenaltyEvent reviewedPenaltyEvent;

    @ManyToOne(cascade = CascadeType.ALL)
    @JsonIgnore
    private OffsideEvent reviewedOffsideEvent;

    private String decision; // "Overturned" ili "Confirmed"
    @Override
    public void apply() {
        match.getVarReviews().add(this);
        if ("Overturned".equals(decision)) {
            if (number==1) {
                match.getGoals().remove(reviewedGoalEvent); // Ukloni gol ako je poništen
                match.setHomeGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());
            } else if (number==2) {
                match.getPenalties().remove(reviewedPenaltyEvent); // Ukloni penal ako je poništen
            }
            else {
                match.getOffsides().remove(reviewedOffsideEvent); // Ukloni off ako je poništen
                match.setHomeGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());
            }
        }
    }
    @Override
    public String getDescription() {
        return String.format("\uD83D\uDCF9 %d' VAR Review: %s - %s", minute, decision, reviewedGoalEvent != null ? reviewedGoalEvent.getDescription() : "No event");
    }
}