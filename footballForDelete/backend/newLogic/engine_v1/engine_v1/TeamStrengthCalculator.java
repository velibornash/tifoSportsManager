package org.example.footballmanager.newLogic.engine_v1;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.Formation;
import org.example.footballmanager.newLogic.model.tactics.Tactics;

import java.util.List;

public final class TeamStrengthCalculator {

    private TeamStrengthCalculator() {}

    public static double calculateTeamStrength(Team team) {
        if (team == null || team.startingXI() == null || team.startingXI().isEmpty()) {
            return 50.0;
        }

        List<Player> starters = team.startingXI();
        double totalRating = 0.0;

        for (Player p : starters) {
            totalRating += calculatePlayerRating(p);
        }

        return totalRating / starters.size();
    }

    public static double calculateTeamStrength(List<Player> players, Formation formation,
                                                Tactics tactics, boolean isHome) {
        if (players == null || players.isEmpty()) {
            return 50.0;
        }

        double totalRating = 0.0;
        for (Player p : players) {
            totalRating += calculatePlayerRating(p);
        }

        return totalRating / players.size();
    }

    private static double calculatePlayerRating(Player p) {
        if (p.getSkills() == null) return 10.0;

        return switch (p.getPosition()) {
            case GK -> p.getSkills().getGoalkeeper() * 2.0 + p.getSkills().getPace() * 0.5;
            case DEF -> p.getSkills().getDefender() * 1.5 + p.getSkills().getPace() * 1.0 + p.getSkills().getPassing() * 0.5;
            case MID -> p.getSkills().getPlaymaker() * 1.5 + p.getSkills().getPassing() * 1.0 + p.getSkills().getTechnique() * 0.8;
            case ATT -> p.getSkills().getStriker() * 2.0 + p.getSkills().getPace() * 1.0 + p.getSkills().getTechnique() * 0.5;
            case WNG -> p.getSkills().getPace() * 1.5 + p.getSkills().getPassing() * 1.0 + p.getSkills().getTechnique() * 0.8;
        };
    }
}
