package org.example.footballmanager.dto;

import lombok.Data;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.event.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class MatchDetailDTO {

    private Long id;
    private String homeTeamName;
    private String awayTeamName;
    private int homeGoals;
    private int awayGoals;
    private double possessionHome;
    private double possessionAway;
    private LocalDateTime matchDate;
    private boolean played;
    private boolean started;
    private String homeFormation;
    private String awayFormation;

    private List<GoalEvent> goals;
    private List<ChanceEvent> chances;
    private List<YellowCardEvent> yellowCards;
    private List<RedCardEvent> redCards;
    private List<PenaltyEvent> penalties;
    private List<FreeKickEvent> freeKicks;
    private List<OffsideEvent> offsides;
    private List<InjuryEvent> injuries;
    private List<SubstitutionEvent> substitutions;
    private List<VARReviewEvent> varReviews;
    private List<CornerEvent> corners;
    private List<ShotOnTargetEvent> shotsOnTarget;
    private List<ShotOffTargetEvent> shotsOffTarget;
    private List<MatchStartEvent> matchStartEvents;
    private List<MatchEndedEvent> matchEndEvents;
    private List<AllEventDTO> allEvents;

    public static MatchDetailDTO fromMatch(Match match) {
        MatchDetailDTO dto = new MatchDetailDTO();
        dto.setId(match.getId());
        dto.setHomeTeamName(match.getHomeTeam().getName());
        dto.setAwayTeamName(match.getAwayTeam().getName());
        dto.setHomeGoals(match.getHomeGoals());
        dto.setAwayGoals(match.getAwayGoals());
        dto.setPossessionHome(match.getPossessionHome());
        dto.setPossessionAway(match.getPossessionAway());
        dto.setMatchDate(match.getMatchDate());
        dto.setPlayed(match.isPlayed());
        dto.setStarted(match.isStarted());
        dto.setHomeFormation(match.getHomeFormation());
        dto.setAwayFormation(match.getAwayFormation());

        // NE mapiramo ovde evente → loadujemo ih u servisu
        return dto;
    }
}