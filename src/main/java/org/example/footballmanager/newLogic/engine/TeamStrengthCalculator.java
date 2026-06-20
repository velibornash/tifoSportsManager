package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.Team;

public final class TeamStrengthCalculator {

    public record TeamStrength(
        double attack,
        double midfield,
        double defense,
        double overall
    ) {}

    public static TeamStrength calculate(Team team) {
        double att = team.attackRating();
        double mid = team.midfieldRating();
        double def = team.defenseRating();
        double overall = (att * 0.35 + mid * 0.30 + def * 0.35);
        return new TeamStrength(att, mid, def, overall);
    }

    public static double expectedPossession(Team home, Team away) {
        double hMid = home.midfieldRating();
        double aMid = away.midfieldRating();
        if (hMid + aMid == 0) return 50.0;
        return 50.0 + (hMid - aMid) * 2.5;
    }
}
