package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.simulator.MatchContext;

@Entity
@Getter
@Setter
public class GoalEvent extends MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player scorer;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player assistant;

    private String scoreAfterGoal;

    @Override
    public void apply(MatchContext context) {
        context.goalScored(team);
        context.getMatch().getGoals().add(this); // prvo dodaj

        // tek sada izračunaj rezultat
        long homeGoals = context.getMatch().getGoals().stream()
                .filter(g -> g.getTeam().equals(context.getMatch().getHomeTeam()))
                .count();
        long awayGoals = context.getMatch().getGoals().stream()
                .filter(g -> g.getTeam().equals(context.getMatch().getAwayTeam()))
                .count();

        this.scoreAfterGoal = String.format("%d:%d", homeGoals, awayGoals);
    }
    public String getPlayerName() {
        return scorer != null ? scorer.getName() : null;
    }
    @Override
    public String getDescription() {
        String assistPart = assistant != null ? " (asist. " + assistant.getName() + ")" : "";
        return String.format("⚽ %d' %s%s --> %s", minute, scorer.getName(), assistPart, scoreAfterGoal);
    }
}