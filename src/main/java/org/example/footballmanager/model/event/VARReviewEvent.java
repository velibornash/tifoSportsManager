package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class VARReviewEvent extends MatchEvent {
    int number;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private GoalEvent reviewedGoalEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private PenaltyEvent reviewedPenaltyEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private OffsideEvent reviewedOffsideEvent;

    private String decision;
    private String overturnReason;

    @Override
    public void apply() {
        if ("Overturned".equals(decision)) {
            if (number == 1) {
                match.getGoals().remove(reviewedGoalEvent);
                match.setHomeGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());
            } else if (number == 2) {
                match.getPenalties().remove(reviewedPenaltyEvent);
            } else {
                match.getOffsides().remove(reviewedOffsideEvent);
                match.setHomeGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());
            }
        }
    }

    @Override
    public String getDescription() {
        String outcome = decision != null ? decision.toUpperCase() : "PENDING";
        String target = "incident";
        String team = "N/A";
        String reasonPart = (overturnReason != null && !overturnReason.isBlank()) ? " (" + overturnReason + ")" : "";

        if (reviewedGoalEvent != null) {
            target = "goal";
            if (reviewedGoalEvent.getTeam() != null) {
                team = reviewedGoalEvent.getTeam().getName();
            }
        } else if (reviewedPenaltyEvent != null) {
            target = "penalty";
            if (reviewedPenaltyEvent.getTeam() != null) {
                team = reviewedPenaltyEvent.getTeam().getName();
            }
        } else if (reviewedOffsideEvent != null && reviewedOffsideEvent.getPlayer() != null && reviewedOffsideEvent.getPlayer().getTeam() != null) {
            target = "offside";
            team = reviewedOffsideEvent.getPlayer().getTeam().getName();
        }

        return String.format("%d' VAR %s: %s - %s%s", minute, outcome, target, team, reasonPart);
    }
}
