package org.example.footballmanager.util.match;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class MatchAnalyticsService {

    public Map<String, Object> generateStats(Match match) {
        Map<String, Object> stats = new HashMap<>();

        stats.put("homeGoals", match.getHomeGoals());
        stats.put("awayGoals", match.getAwayGoals());

        stats.put("homeShotsOnTarget", countTeamEvents(match.getShotsOnTarget(), match.getHomeTeam()));
        stats.put("awayShotsOnTarget", countTeamEvents(match.getShotsOnTarget(), match.getAwayTeam()));
        stats.put("homeShotsOffTarget", countTeamEvents(match.getShotsOffTarget(), match.getHomeTeam()));
        stats.put("awayShotsOffTarget", countTeamEvents(match.getShotsOffTarget(), match.getAwayTeam()));

        stats.put("homeYellowCards", countTeamEvents(match.getYellowCards(), match.getHomeTeam()));
        stats.put("awayYellowCards", countTeamEvents(match.getYellowCards(), match.getAwayTeam()));
        stats.put("homeRedCards", countTeamEvents(match.getRedCards(), match.getHomeTeam()));
        stats.put("awayRedCards", countTeamEvents(match.getRedCards(), match.getAwayTeam()));

        stats.put("homePenalties", countTeamEvents(match.getPenalties(), match.getHomeTeam()));
        stats.put("awayPenalties", countTeamEvents(match.getPenalties(), match.getAwayTeam()));
        stats.put("homeFreeKicks", countTeamEvents(match.getFreeKicks(), match.getHomeTeam()));
        stats.put("awayFreeKicks", countTeamEvents(match.getFreeKicks(), match.getAwayTeam()));

        long homeEvents = countTeamEvents(match.getAllMatchEvents(), match.getHomeTeam());
        long awayEvents = countTeamEvents(match.getAllMatchEvents(), match.getAwayTeam());
        double total = homeEvents + awayEvents;

        stats.put("homePossession", total > 0 ? (homeEvents / total) * 100 : 50);
        stats.put("awayPossession", total > 0 ? (awayEvents / total) * 100 : 50);

        return stats;
    }

    private long countTeamEvents(Set<? extends MatchEvent> events, Team team) {
        return events.stream()
                .filter(e -> {
                    try {
                        Team eventTeam = resolveEventTeam(e);
                        return eventTeam != null && eventTeam.equals(team);
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .count();
    }

    private Team resolveEventTeam(MatchEvent event) {
        return switch (event) {
            case GoalEvent goalEvent -> goalEvent.getTeam();
            case ShotOnTargetEvent shotOnTargetEvent -> shotOnTargetEvent.getTeam();
            case ShotOffTargetEvent shotOffTargetEvent -> shotOffTargetEvent.getTeam();
            case YellowCardEvent yellowCardEvent -> yellowCardEvent.getTeam();
            case RedCardEvent redCardEvent -> redCardEvent.getTeam();
            case PenaltyEvent penaltyEvent -> penaltyEvent.getTeam();
            case FreeKickEvent freeKickEvent -> freeKickEvent.getTeam();
            case CornerEvent cornerEvent -> cornerEvent.getTeam();
            case ThrowInEvent throwInEvent -> throwInEvent.getTeam();
            case GoalKickEvent goalKickEvent -> goalKickEvent.getTeam();
            case ChanceEvent chanceEvent -> chanceEvent.getTeam();
            case DuelEvent duelEvent -> duelEvent.getTeam();
            case DribbleEvent dribbleEvent -> dribbleEvent.getTeam();
            case InterceptionEvent interceptionEvent -> interceptionEvent.getTeam();
            case PassEvent passEvent -> passEvent.getTeam();
            case SubstitutionEvent substitutionEvent -> substitutionEvent.getTeam();
            default -> null;
        };
    }
}
