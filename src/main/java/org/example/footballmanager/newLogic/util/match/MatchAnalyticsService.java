package org.example.footballmanager.newLogic.util.match;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.repository.MatchEventRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MatchAnalyticsService {

    private final MatchEventRepository matchEventRepository;

    public Map<String, Object> generateStats(Match match) {
        Map<String, Object> stats = new HashMap<>();

        stats.put("homeGoals", match.getHomeGoals());
        stats.put("awayGoals", match.getAwayGoals());

        List<MatchEvent> events = matchEventRepository.findByMatch(match);
        Long homeId = match.getHomeTeam() != null ? match.getHomeTeam().getId() : null;
        Long awayId = match.getAwayTeam() != null ? match.getAwayTeam().getId() : null;

        long homeShotsOnTarget = events.stream().filter(e -> e instanceof ShotEvent se && se.onTarget() && homeId != null && homeId.equals(resolveTeamId(se, match))).count();
        long awayShotsOnTarget = events.stream().filter(e -> e instanceof ShotEvent se && se.onTarget() && awayId != null && awayId.equals(resolveTeamId(se, match))).count();
        long homeShotsOffTarget = events.stream().filter(e -> e instanceof ShotEvent se && !se.onTarget() && homeId != null && homeId.equals(resolveTeamId(se, match))).count();
        long awayShotsOffTarget = events.stream().filter(e -> e instanceof ShotEvent se && !se.onTarget() && awayId != null && awayId.equals(resolveTeamId(se, match))).count();

        stats.put("homeShotsOnTarget", homeShotsOnTarget);
        stats.put("awayShotsOnTarget", awayShotsOnTarget);
        stats.put("homeShotsOffTarget", homeShotsOffTarget);
        stats.put("awayShotsOffTarget", awayShotsOffTarget);

        long homeYellowCards = events.stream().filter(e -> e instanceof CardEvent ce && ce.cardType() == CardEvent.CardType.YELLOW && homeId != null && homeId.equals(resolveTeamId(ce, match))).count();
        long awayYellowCards = events.stream().filter(e -> e instanceof CardEvent ce && ce.cardType() == CardEvent.CardType.YELLOW && awayId != null && awayId.equals(resolveTeamId(ce, match))).count();
        long homeRedCards = events.stream().filter(e -> e instanceof CardEvent ce && ce.cardType() == CardEvent.CardType.RED && homeId != null && homeId.equals(resolveTeamId(ce, match))).count();
        long awayRedCards = events.stream().filter(e -> e instanceof CardEvent ce && ce.cardType() == CardEvent.CardType.RED && awayId != null && awayId.equals(resolveTeamId(ce, match))).count();

        stats.put("homeYellowCards", homeYellowCards);
        stats.put("awayYellowCards", awayYellowCards);
        stats.put("homeRedCards", homeRedCards);
        stats.put("awayRedCards", awayRedCards);

        long homePossessions = events.stream().filter(e -> e instanceof PossessionStartEvent pe && "HOME".equals(pe.teamSide())).count();
        long awayPossessions = events.stream().filter(e -> e instanceof PossessionStartEvent pe && "AWAY".equals(pe.teamSide())).count();
        double totalPossessions = homePossessions + awayPossessions;

        stats.put("homePossession", totalPossessions > 0 ? (homePossessions / totalPossessions) * 100 : 50);
        stats.put("awayPossession", totalPossessions > 0 ? (awayPossessions / totalPossessions) * 100 : 50);

        return stats;
    }

    private Long resolveTeamId(MatchEvent event, Match match) {
        if (event == null || match == null) return null;
        String side = resolveTeamSide(event);
        if (side == null) return null;
        return "HOME".equals(side) ?
                (match.getHomeTeam() != null ? match.getHomeTeam().getId() : null) :
                (match.getAwayTeam() != null ? match.getAwayTeam().getId() : null);
    }

    private String resolveTeamSide(MatchEvent event) {
        return switch (event) {
            case GoalEvent e -> e.teamSide();
            case ShotEvent e -> e.teamSide();
            case CardEvent e -> e.teamSide();
            case PenaltyEvent e -> e.teamSide();
            case SetPieceEvent e -> e.teamSide();
            case InjuryEvent e -> e.teamSide();
            case SubstitutionEvent e -> e.teamSide();
            case OffsideEvent e -> e.teamSide();
            case PossessionStartEvent e -> e.teamSide();
            case PossessionEndEvent e -> e.teamSide();
            case PassInterceptedEvent e -> e.interceptorTeamSide();
            case CrossEvent e -> e.teamSide();
            case TackleEvent e -> e.defenderTeamSide();
            case DuelEvent e -> e.teamSide();
            case DribbleEvent e -> e.teamSide();
            case DribbleLostEvent e -> e.teamSide();
            default -> null;
        };
    }
}
