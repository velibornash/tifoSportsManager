package org.example.footballmanager.controller;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.MatchEventDTO;
import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.InjuryEvent;
import org.example.footballmanager.model.event.MatchEndedEvent;
import org.example.footballmanager.model.event.PenaltyEvent;
import org.example.footballmanager.model.event.ShotOnTargetEvent;
import org.example.footballmanager.model.event.SubstitutionEvent;
import org.example.footballmanager.model.event.VARReviewEvent;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.MatchDetailService;
import org.example.footballmanager.service.MatchReportService;
import org.example.footballmanager.service.ScheduleInsightService;
import org.example.footballmanager.old.oldService.MatchService;
import org.example.footballmanager.util.players.PlayerFactory;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchRepository matchRepository;
    private final MatchDetailService matchDetailService;
    private final MatchEventRepository matchEventRepository;
    private final MatchEventMapper matchEventMapper;
    private final MatchReportService matchReportService;
    private final ScheduleInsightService scheduleInsightService;
    @Autowired
    public MatchController(
            MatchRepository matchRepository,
            MatchDetailService matchDetailService,
            MatchEventRepository matchEventRepository,
            MatchEventMapper matchEventMapper,
            MatchReportService matchReportService,
            ScheduleInsightService scheduleInsightService
    ) {
        this.matchRepository = matchRepository;
        this.matchDetailService = matchDetailService;
        this.matchEventRepository = matchEventRepository;
        this.matchEventMapper = matchEventMapper;
        this.matchReportService = matchReportService;
        this.scheduleInsightService = scheduleInsightService;
    }


    @GetMapping("/{matchId}")
    public ResponseEntity<MatchDTO> getMatch(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(MatchDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{matchId}/lineups")
    public ResponseEntity<Map<String, Object>> getMatchLineups(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("homeTeam", match.getHomeTeam() != null ? match.getHomeTeam().getName() : null);
                    payload.put("awayTeam", match.getAwayTeam() != null ? match.getAwayTeam().getName() : null);
                    payload.put("homeLineup", match.getHomeLineup() != null
                            ? match.getHomeLineup().getOrderedStartingPlayers().stream().map(this::toLineupPlayer).toList()
                            : List.of());
                    payload.put("awayLineup", match.getAwayLineup() != null
                            ? match.getAwayLineup().getOrderedStartingPlayers().stream().map(this::toLineupPlayer).toList()
                            : List.of());
                    return ResponseEntity.ok(payload);
                })
                .orElse(ResponseEntity.notFound().build());
    }
    @GetMapping("/{matchId}/detail")
    public ResponseEntity<List<MatchEventFlatDTO>> getMatchDetail(@PathVariable Long matchId) {
        try {
            List<MatchEventFlatDTO> events = matchDetailService.getMatchEventsFlat(matchId);
            return ResponseEntity.ok(events);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{matchId}/key-events")
    public ResponseEntity<List<MatchEventDTO>> getKeyEvents(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> matchEventRepository.findByMatch(match).stream()
                        .filter(e -> e instanceof GoalEvent
                                || e instanceof PenaltyEvent
                                || e instanceof VARReviewEvent
                                || e instanceof ShotOnTargetEvent
                                || e instanceof InjuryEvent
                                || e instanceof SubstitutionEvent
                                || e instanceof MatchEndedEvent)
                        .sorted((a, b) -> {
                            int byMinute = Integer.compare(a.getMinute(), b.getMinute());
                            if (byMinute != 0) return byMinute;
                            if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                                int byCreated = a.getCreatedAt().compareTo(b.getCreatedAt());
                                if (byCreated != 0) return byCreated;
                            }
                            Long left = a.getId() != null ? a.getId() : Long.MAX_VALUE;
                            Long right = b.getId() != null ? b.getId() : Long.MAX_VALUE;
                            return Long.compare(left, right);
                        })
                        .map(matchEventMapper::toDto)
                        .toList())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{matchId}/preview")
    public ResponseEntity<Map<String, Object>> getMatchPreview(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    Team homeTeam = match.getHomeTeam();
                    Team awayTeam = match.getAwayTeam();
                    if (homeTeam == null || awayTeam == null || homeTeam.getId() == null || awayTeam.getId() == null) {
                        return ResponseEntity.ok(Map.of(
                                "matchId", matchId,
                                "prediction", Map.of(),
                                "h2h", emptyHeadToHeadSummary(),
                                "meetings", List.of()
                        ));
                    }

                    ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(homeTeam, awayTeam);
                    List<Match> headToHeadMatches = matchRepository.findByHomeTeamIdInAndAwayTeamIdIn(
                                    List.of(homeTeam.getId(), awayTeam.getId()),
                                    List.of(homeTeam.getId(), awayTeam.getId())
                            ).stream()
                            .filter(Match::isPlayed)
                            .filter(existing -> existing.getHomeTeam() != null && existing.getAwayTeam() != null)
                            .filter(existing -> !Objects.equals(existing.getId(), match.getId()))
                            .sorted(Comparator.comparing(Match::getMatchDate, Comparator.nullsLast(Comparator.reverseOrder())))
                            .toList();

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("matchId", match.getId());
                    payload.put("matchDate", match.getMatchDate() != null ? match.getMatchDate().toString() : null);
                    payload.put("played", match.isPlayed());
                    payload.put("homeTeamId", homeTeam.getId());
                    payload.put("awayTeamId", awayTeam.getId());
                    payload.put("homeTeam", homeTeam.getName());
                    payload.put("awayTeam", awayTeam.getName());
                    payload.put("homeTeamStrength", insights.homeTeamStrength());
                    payload.put("awayTeamStrength", insights.awayTeamStrength());
                    payload.put("homeTeamForm", insights.homeTeamForm());
                    payload.put("awayTeamForm", insights.awayTeamForm());
                    payload.put("prediction", toPredictionMap(insights.prediction()));
                    payload.put("h2hPerspectiveTeam", homeTeam.getName());
                    payload.put("h2h", summarizeHeadToHead(homeTeam.getId(), headToHeadMatches));
                    payload.put("meetings", headToHeadMatches.stream()
                            .limit(5)
                            .map(existing -> toMeetingPayload(homeTeam.getId(), existing))
                            .toList());
                    return ResponseEntity.ok(payload);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{matchId}/report")
    public ResponseEntity<Map<String, Object>> getMatchReport(@PathVariable Long matchId) {
        try {
            return ResponseEntity.ok(matchReportService.buildMatchReport(matchId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private Map<String, Object> toLineupPlayer(Player player) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", player.getId());
        dto.put("name", player.getName());
        dto.put("position", player.getPosition() != null ? player.getPosition().name() : null);
        dto.put("squadNumber", player.getSquadNumber());
        return dto;
    }

    private Map<String, Object> toPredictionMap(ScheduleInsightService.Prediction prediction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("homeWinProbability", prediction.homeWinProbability());
        payload.put("drawProbability", prediction.drawProbability());
        payload.put("awayWinProbability", prediction.awayWinProbability());
        payload.put("expectedHomeGoals", prediction.expectedHomeGoals());
        payload.put("expectedAwayGoals", prediction.expectedAwayGoals());
        payload.put("mostLikelyResult", prediction.mostLikelyResult());
        payload.put("confidence", prediction.confidence());
        payload.put("analysis", prediction.analysis());
        return payload;
    }

    private Map<String, Object> summarizeHeadToHead(Long teamId, List<Match> matches) {
        if (matches == null || matches.isEmpty()) {
            return emptyHeadToHeadSummary();
        }

        int wins = 0;
        int draws = 0;
        int losses = 0;
        int goalsFor = 0;
        int goalsAgainst = 0;

        for (Match existing : matches) {
            boolean isHome = Objects.equals(existing.getHomeTeam().getId(), teamId);
            int teamGoals = isHome ? existing.getHomeGoals() : existing.getAwayGoals();
            int opponentGoals = isHome ? existing.getAwayGoals() : existing.getHomeGoals();
            goalsFor += teamGoals;
            goalsAgainst += opponentGoals;
            if (teamGoals > opponentGoals) {
                wins++;
            } else if (teamGoals == opponentGoals) {
                draws++;
            } else {
                losses++;
            }
        }

        Match lastMeeting = matches.getFirst();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("played", matches.size());
        summary.put("wins", wins);
        summary.put("draws", draws);
        summary.put("losses", losses);
        summary.put("goalsFor", goalsFor);
        summary.put("goalsAgainst", goalsAgainst);
        summary.put("summary", String.format("H2H %d-%d-%d · Goals %d:%d", wins, draws, losses, goalsFor, goalsAgainst));
        summary.put("lastMeetingSummary", buildLastMeetingSummary(teamId, lastMeeting));
        summary.put("lastMeetingDate", lastMeeting != null ? formatDateTime(lastMeeting.getMatchDate()) : "N/A");
        return summary;
    }

    private Map<String, Object> emptyHeadToHeadSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("played", 0);
        summary.put("wins", 0);
        summary.put("draws", 0);
        summary.put("losses", 0);
        summary.put("goalsFor", 0);
        summary.put("goalsAgainst", 0);
        summary.put("summary", "No head-to-head history yet.");
        summary.put("lastMeetingSummary", "First recorded meeting.");
        summary.put("lastMeetingDate", "N/A");
        return summary;
    }

    private Map<String, Object> toMeetingPayload(Long perspectiveTeamId, Match match) {
        boolean isHome = match.getHomeTeam() != null && Objects.equals(match.getHomeTeam().getId(), perspectiveTeamId);
        int teamGoals = isHome ? match.getHomeGoals() : match.getAwayGoals();
        int opponentGoals = isHome ? match.getAwayGoals() : match.getHomeGoals();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchId", match.getId());
        payload.put("matchDate", match.getMatchDate() != null ? match.getMatchDate().toString() : null);
        payload.put("homeTeam", match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home");
        payload.put("awayTeam", match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away");
        payload.put("homeGoals", match.getHomeGoals());
        payload.put("awayGoals", match.getAwayGoals());
        payload.put("summary", String.format("%d:%d %s", teamGoals, opponentGoals, isHome ? "at home" : "away"));
        return payload;
    }

    private String buildLastMeetingSummary(Long teamId, Match match) {
        if (match == null || match.getHomeTeam() == null || match.getAwayTeam() == null) {
            return "First recorded meeting.";
        }
        boolean isHome = Objects.equals(match.getHomeTeam().getId(), teamId);
        int teamGoals = isHome ? match.getHomeGoals() : match.getAwayGoals();
        int opponentGoals = isHome ? match.getAwayGoals() : match.getHomeGoals();
        String venue = isHome ? "at home" : "away";
        String opponentName = isHome ? match.getAwayTeam().getName() : match.getHomeTeam().getName();
        return String.format("Last meeting: %d:%d vs %s (%s)", teamGoals, opponentGoals, opponentName, venue);
    }

    private String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.toString().substring(0, 16).replace("T", " ") : "N/A";
    }
}
