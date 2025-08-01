package org.example.footballmanager.util;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class MatchAnalyticsService {

    public Map<String, Object> generateStats(Match match) {
        Map<String, Object> stats = new HashMap<>();

        // Broj golova
        stats.put("homeGoals", match.getHomeGoals());
        stats.put("awayGoals", match.getAwayGoals());

        // Šutevi
        long homeShotsOnTarget = match.getShotsOnTarget().stream()
                .filter(s -> s.getTeam().equals(match.getHomeTeam())).count();
        long awayShotsOnTarget = match.getShotsOnTarget().stream()
                .filter(s -> s.getTeam().equals(match.getAwayTeam())).count();
        long homeShotsOffTarget = match.getShotsOffTarget().stream()
                .filter(s -> s.getTeam().equals(match.getHomeTeam())).count();
        long awayShotsOffTarget = match.getShotsOffTarget().stream()
                .filter(s -> s.getTeam().equals(match.getAwayTeam())).count();
        stats.put("homeShotsOnTarget", homeShotsOnTarget);
        stats.put("awayShotsOnTarget", awayShotsOnTarget);
        stats.put("homeShotsOffTarget", homeShotsOffTarget);
        stats.put("awayShotsOffTarget", awayShotsOffTarget);

        // Kartoni
        long homeYellowCards = match.getYellowCards().stream()
                .filter(c -> c.getPlayer().getTeam().equals(match.getHomeTeam())).count();
        long awayYellowCards = match.getYellowCards().stream()
                .filter(c -> c.getPlayer().getTeam().equals(match.getAwayTeam())).count();
        long homeRedCards = match.getRedCards().stream()
                .filter(c -> c.getPlayer().getTeam().equals(match.getHomeTeam())).count();
        long awayRedCards = match.getRedCards().stream()
                .filter(c -> c.getPlayer().getTeam().equals(match.getAwayTeam())).count();
        stats.put("homeYellowCards", homeYellowCards);
        stats.put("awayYellowCards", awayYellowCards);
        stats.put("homeRedCards", homeRedCards);
        stats.put("awayRedCards", awayRedCards);

        // Posed (procenat na osnovu događaja)
        long homeEvents = countTeamEvents(match, match.getHomeTeam());
        long awayEvents = countTeamEvents(match, match.getAwayTeam());
        double totalEvents = homeEvents + awayEvents;
        stats.put("homePossession", totalEvents > 0 ? (homeEvents / totalEvents) * 100 : 50);
        stats.put("awayPossession", totalEvents > 0 ? (awayEvents / totalEvents) * 100 : 50);

        // Penali i slobodni udarci
        stats.put("homePenalties", match.getPenalties().stream()
                .filter(p -> p.getTeam().equals(match.getHomeTeam())).count());
        stats.put("awayPenalties", match.getPenalties().stream()
                .filter(p -> p.getTeam().equals(match.getAwayTeam())).count());
        stats.put("homeFreeKicks", match.getFreeKicks().stream()
                .filter(f -> f.getTeam().equals(match.getHomeTeam())).count());
        stats.put("awayFreeKicks", match.getFreeKicks().stream()
                .filter(f -> f.getTeam().equals(match.getAwayTeam())).count());

        return stats;
    }

    private long countTeamEvents(Match match, Team team) {
        return match.getAllMatchEvents().stream()
                .filter(e -> {
                    if (e instanceof GoalEvent g) return g.getTeam().equals(team);
                    if (e instanceof ShotOnTargetEvent s) return s.getTeam().equals(team);
                    if (e instanceof ShotOffTargetEvent s) return s.getTeam().equals(team);
                    if (e instanceof FreeKickEvent f) return f.getTeam().equals(team);
                    if (e instanceof PenaltyEvent p) return p.getTeam().equals(team);
                    if (e instanceof ChanceEvent c) return c.getTeam().equals(team);
                    return false;
                }).count();
    }
}