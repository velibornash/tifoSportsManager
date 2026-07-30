package org.example.footballmanager.newLogic.util.match;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.event.GoalEvent;

import java.util.List;

public class MatchRatingCalculator {

    public static int calculate(Player player, Team team, List<GoalEvent> allGoals) {
        long goals = allGoals.stream()
                .filter(g -> g.scorerId() == player.getId())
                .count();
        long assists = allGoals.stream()
                .filter(g -> g.assistantId() != null && g.assistantId() == player.getId())
                .count();

        return calculate(
                player,
                (int) goals,
                (int) assists,
                0,
                0,
                false,
                0,
                0,
                0,
                0,
                90
        );
    }

    public static int calculate(Player player,
                                int goals,
                                int assists,
                                int interceptions,
                                int saves,
                                boolean cleanSheet,
                                int yellowCards,
                                int redCards,
                                int teamGoals,
                                int concededGoals,
                                int minutesPlayed) {
        Position pos = player.getPositionEnum() != null ? player.getPositionEnum() : Position.MID;

        double skillScore = player.getSkills().getRatingScore(pos);
        double maxScore = getMaxScore(pos);
        double normalizedSkill = Math.max(0.0, Math.min(1.0, skillScore / maxScore));
        double base = 54.0 + normalizedSkill * 14.0 + (player.getForm() - 6.0) * 1.2;
        double participationModifier = participationModifier(minutesPlayed);

        double attackingContribution = goals * 11.0 + assists * 6.5;
        if (goals >= 3) {
            attackingContribution += 3.5;
        } else if (goals == 2) {
            attackingContribution += 1.5;
        }

        double defensiveContribution = switch (pos) {
            case GK -> saves * 3.2 + (cleanSheet ? 8.0 : 0.0) - concededGoals * 2.2;
            case DEF -> interceptions * 1.7 + (cleanSheet ? 6.5 : 0.0) - concededGoals * 1.4;
            case MID -> interceptions * 1.1 + (cleanSheet ? 1.5 : 0.0) - Math.max(0, concededGoals - 2) * 0.4;
            case ATT, WNG -> interceptions * 0.35;
        };

        double teamResultModifier = 0;
        if (teamGoals > concededGoals) {
            teamResultModifier = 2.0;
        } else if (teamGoals == concededGoals) {
            teamResultModifier = 0.4;
        } else {
            teamResultModifier = -1.6;
        }

        double disciplinePenalty = yellowCards * 3.5 + redCards * 12.0;
        double rating = base + participationModifier + attackingContribution + defensiveContribution + teamResultModifier - disciplinePenalty;
        rating = Math.max(10, Math.min(100, rating));

        return (int) Math.round(rating);
    }

    private static double participationModifier(int minutesPlayed) {
        if (minutesPlayed >= 75) return 0.8;
        if (minutesPlayed >= 60) return 0.4;
        if (minutesPlayed >= 45) return 0.0;
        if (minutesPlayed >= 25) return -0.8;
        if (minutesPlayed >= 10) return -1.6;
        if (minutesPlayed > 0) return -2.2;
        return -4.0;
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
