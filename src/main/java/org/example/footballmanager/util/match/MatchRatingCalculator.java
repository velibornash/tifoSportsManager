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

        // Golovi i asistencije koriste allGoals iz baze
        long goals = allGoals.stream()
                .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                .count();
        long assists = allGoals.stream()
                .filter(g ->  g.getAssistant() != null && g.getAssistant().equals(player))
                .count();

        double contribution = goals * 10 + assists * 6;

        // Clean sheet bonus
        boolean isOwnTeamCleanSheet = allGoals.stream()
                .noneMatch(g -> g.getScorer() != null && !g.getScorer().getTeam().equals(player.getTeam()));

        double cleanSheetBonus = 0;
        if (isOwnTeamCleanSheet && (pos == Position.GK || pos == Position.DEF)) {
            cleanSheetBonus = 20;
        }

        return (int) Math.round(normalizedSkill + contribution + cleanSheetBonus);
    }



    private static double getMaxScore(Position pos) {
        return switch (pos) {
            case GK -> 17 * 2.0 + 17 + 17 + 17 * 0.5;              // 2*gk + pace + pass + 0.5*def
            case DEF -> 17 * 1.2 + 17 * 2.0 + 17 + 17 + 17 * 0.8;   // pace + 2*def + play + pass + 0.8*tech
            case MID -> 17 + 17 * 1.2 + 17 * 2.0 + 17 * 1.5 + 17 * 0.7; // pace + 1.2*tech + 2*play + 1.5*pass + 0.7*def
            case ATT -> 17 * 2.0 + 17 * 1.5 + 17 * 2.0 + 17 * 0.5;  // 2*pace + 1.5*tech + 2*str + 0.5*def
        };
    }
}