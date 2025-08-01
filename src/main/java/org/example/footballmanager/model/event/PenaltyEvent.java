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
public class PenaltyEvent extends MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private boolean scored;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player taker;

    @Override
    public void apply(MatchContext context) {
        if (scored) {
            GoalEvent goal = new GoalEvent();
            goal.setMatch(match);
            goal.setTeam(team);
            goal.setScorer(taker);
            goal.setMinute(minute);
            goal.setAssistant(null);

            // Izračunaj rezultat odmah
            Match match = context.getMatch();
            long homeGoals = match.getGoals().stream()
                    .filter(g -> g.getTeam().equals(match.getHomeTeam()))
                    .count() + (team.equals(match.getHomeTeam()) ? 1 : 0);

            long awayGoals = match.getGoals().stream()
                    .filter(g -> g.getTeam().equals(match.getAwayTeam()))
                    .count() + (team.equals(match.getAwayTeam()) ? 1 : 0);

            goal.setScoreAfterGoal(String.format("%d:%d", homeGoals, awayGoals));

            match.getGoals().add(goal);
            match.getAllMatchEvents().add(goal);
        }
    }

    @Override
    public String getDescription() {
        return minute + "' Penal - " + taker.getName() + (scored ? " ✅" : " ❌");
    }
}