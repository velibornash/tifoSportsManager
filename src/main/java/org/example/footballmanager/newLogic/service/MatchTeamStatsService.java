package org.example.footballmanager.newLogic.service;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.MatchTeamStats;
import org.example.footballmanager.newLogic.model.event.CrossHeaderEvent;
import org.example.footballmanager.newLogic.model.event.GoalEvent;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.model.event.OffsideEvent;
import org.example.footballmanager.newLogic.model.event.PenaltyEvent;
import org.example.footballmanager.newLogic.model.event.ShotBlockedEvent;
import org.example.footballmanager.newLogic.model.event.ShotMissedEvent;
import org.example.footballmanager.newLogic.model.event.ShotSavedEvent;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class MatchTeamStatsService {

    public MatchTeamStats compute(Match match, MatchResult result, List<MatchPlayerStats> playerStats) {
        double homeXg = 0.0;
        double awayXg = 0.0;
        int homeOffsides = 0;
        int awayOffsides = 0;

        for (MatchEvent event : result.events()) {
            boolean home = "HOME".equals(eventTeamSide(event));
            boolean away = "AWAY".equals(eventTeamSide(event));
            if (!home && !away) continue;

            if (event instanceof GoalEvent goal) {
                if ("HOME".equals(goal.teamSide())) homeXg += goal.xG();
                else awayXg += goal.xG();
            } else if (event instanceof ShotSavedEvent saved) {
                if ("HOME".equals(saved.teamSide())) homeXg += saved.xG();
                else awayXg += saved.xG();
            } else if (event instanceof ShotMissedEvent missed) {
                if ("HOME".equals(missed.teamSide())) homeXg += missed.xG();
                else awayXg += missed.xG();
            } else if (event instanceof ShotBlockedEvent blocked) {
                if ("HOME".equals(blocked.teamSide())) homeXg += blocked.xG();
                else awayXg += blocked.xG();
            } else if (event instanceof CrossHeaderEvent header) {
                if ("HOME".equals(header.teamSide())) homeXg += header.xG();
                else awayXg += header.xG();
            } else if (event instanceof PenaltyEvent penalty) {
                // scored penalties already carry xG via their GoalEvent
                if (penalty.scored()) continue;
                if ("HOME".equals(penalty.teamSide())) homeXg += penalty.xG();
                else awayXg += penalty.xG();
            } else if (event instanceof OffsideEvent offside) {
                if ("HOME".equals(offside.teamSide())) homeOffsides++;
                else awayOffsides++;
            }
        }

        double[] ratings = averageRatings(match, playerStats);

        return new MatchTeamStats(
            result.homeGoals(), result.awayGoals(),
            result.homePossession(), result.awayPossession(),
            homeXg, awayXg,
            result.homeShots(), result.awayShots(),
            result.homeShotsOnTarget(), result.awayShotsOnTarget(),
            result.homePassesAttempted(), result.homePassesCompleted(),
            result.awayPassesAttempted(), result.awayPassesCompleted(),
            result.homeCorners(), result.awayCorners(),
            homeOffsides, awayOffsides,
            result.homeYellowCards(), result.awayYellowCards(),
            result.homeRedCards(), result.awayRedCards(),
            result.homeFouls(), result.awayFouls(),
            ratings[0], ratings[1]
        );
    }

    private String eventTeamSide(MatchEvent event) {
        if (event instanceof GoalEvent g) return g.teamSide();
        if (event instanceof ShotSavedEvent s) return s.teamSide();
        if (event instanceof ShotMissedEvent m) return m.teamSide();
        if (event instanceof ShotBlockedEvent b) return b.teamSide();
        if (event instanceof CrossHeaderEvent h) return h.teamSide();
        if (event instanceof PenaltyEvent p) return p.teamSide();
        if (event instanceof OffsideEvent o) return o.teamSide();
        return null;
    }

    private double[] averageRatings(Match match, List<MatchPlayerStats> playerStats) {
        if (playerStats == null || playerStats.isEmpty()) return new double[]{0.0, 0.0};
        String homeName = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
        String awayName = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";
        double homeSum = 0.0, awaySum = 0.0;
        int homeCount = 0, awayCount = 0;
        for (MatchPlayerStats s : playerStats) {
            if (s.getPlayer() == null || s.getPlayer().getTeam() == null) continue;
            String teamName = s.getPlayer().getTeam().getName();
            if (homeName.equals(teamName)) {
                homeSum += s.getRating();
                homeCount++;
            } else if (awayName.equals(teamName)) {
                awaySum += s.getRating();
                awayCount++;
            }
        }
        double homeAvg = homeCount > 0 ? Math.round(10.0 * homeSum / homeCount) / 10.0 : 0.0;
        double awayAvg = awayCount > 0 ? Math.round(10.0 * awaySum / awayCount) / 10.0 : 0.0;
        return new double[]{homeAvg, awayAvg};
    }
}