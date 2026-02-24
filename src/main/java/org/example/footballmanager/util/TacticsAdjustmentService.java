package org.example.footballmanager.util;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.util.match.MatchContext;
import org.springframework.stereotype.Service;

@Service
public class TacticsAdjustmentService {

    public void adjustTactics(MatchContext context) {
        Match match = context.getMatch();
        int minute = context.getCurrentMinute();
        int goalDifference = match.getHomeGoals() - match.getAwayGoals();

        // Domaćin
        Tactics homeTactics = context.getHomeTactics();
        adjustTeamTactics(homeTactics, goalDifference, minute, true);

        // Gost
        Tactics awayTactics = context.getAwayTactics();
        adjustTeamTactics(awayTactics, -goalDifference, minute, false);
    }

    private void adjustTeamTactics(Tactics tactics, int goalDifference, int minute, boolean isHome) {
        if (goalDifference >= 2 && minute > 70) {
            tactics.setAggression(Math.max(1, tactics.getAggression() - 2));
            tactics.setPressing(Math.max(1, tactics.getPressing() - 2));
            tactics.setCounterAttack(Math.max(1, tactics.getCounterAttack() - 1));
            tactics.setBallControl(tactics.getBallControl() + 1);
        } else if (goalDifference <= -2 && minute > 60) {
            tactics.setAggression(tactics.getAggression() + 2);
            tactics.setPressing(tactics.getPressing() + 2);
            tactics.setCounterAttack(tactics.getCounterAttack() + 1);
            tactics.setBallControl(Math.max(1, tactics.getBallControl() - 1));
        } else if (isHome && minute < 30) {
            tactics.setAggression(tactics.getAggression() + 1);
            tactics.setPressing(tactics.getPressing() + 1);
        }
    }
}
