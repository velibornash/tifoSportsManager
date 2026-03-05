package org.example.footballmanager.util.match;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;

import java.util.List;

public class MatchRatingCalculator {

    public static int calculate(Player player, Team team, List<GoalEvent> allGoals) {
        Position pos = player.getPositionEnum();

        double skillScore = player.getSkills().getRatingScore(pos);
        double maxScore = getMaxScore(pos);
        double normalizedSkill = skillScore / maxScore * 70.0;

        // Direct attacking contribution
        long goals = allGoals.stream()
                .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                .count();
        long assists = allGoals.stream()
                .filter(g ->  g.getAssistant() != null && g.getAssistant().equals(player))
                .count();

        long teamGoals = allGoals.stream()
                .filter(g -> g.getScorer() != null && g.getScorer().getTeam().equals(player.getTeam()))
                .count();
        long concededGoals = allGoals.stream()
                .filter(g -> g.getScorer() != null && !g.getScorer().getTeam().equals(player.getTeam()))
                .count();

        double contribution = goals * 12 + assists * 6;
        if (goals >= 3) {
            contribution += 4;
        } else if (goals == 2) {
            contribution += 2;
        }

        // Form influence (small but meaningful on 1-100 scale)
        double formBonus = (player.getForm() - 5.0) * 1.2;

        // Defensive contribution
        boolean cleanSheet = concededGoals == 0;
        double defensiveBonus = 0;
        if (cleanSheet && pos == Position.GK) {
            defensiveBonus += 8;
        } else if (cleanSheet && pos == Position.DEF) {
            defensiveBonus += 5;
        }
        if (concededGoals >= 3 && pos == Position.GK) {
            defensiveBonus -= 6;
        } else if (concededGoals >= 3 && pos == Position.DEF) {
            defensiveBonus -= 4;
        }

        double teamResultModifier = 0;
        if (teamGoals > concededGoals) {
            teamResultModifier = 1;
        } else if (teamGoals < concededGoals) {
            teamResultModifier = -1;
        }

        double rating = normalizedSkill + contribution + formBonus + defensiveBonus + teamResultModifier;
        rating = Math.max(10, Math.min(100, rating));

        return (int) Math.round(rating);
    }



    private static double getMaxScore(Position pos) {
        return switch (pos) {
            case GK -> 17 * 2.0 + 17 + 17 + 17 * 0.5;              // 2*gk + pace + pass + 0.5*def
            case DEF -> 17 * 1.2 + 17 * 2.0 + 17 + 17 + 17 * 0.8;   // pace + 2*def + play + pass + 0.8*tech
            case MID -> 17 + 17 * 1.2 + 17 * 2.0 + 17 * 1.5 + 17 * 0.7; // pace + 1.2*tech + 2*play + 1.5*pass + 0.7*def
            case ATT -> 17 * 2.0 + 17 * 1.5 + 17 * 2.0 + 17 * 0.5;  // 2*pace + 1.5*tech + 2*str + 0.5*def
            case WNG -> 17 * 2.0 + 17 * 1.5 + 17 * 2.0 ;
        };
    }
}
