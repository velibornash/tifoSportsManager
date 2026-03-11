package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MatchReportService {

    private final MatchRepository matchRepository;
    private final MatchDetailService matchDetailService;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;

    public Map<String, Object> buildMatchReport(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        List<MatchEventFlatDTO> events;
        try {
            events = matchDetailService.getMatchEventsFlat(matchId);
        } catch (RuntimeException ignored) {
            events = List.of();
        }

        String homeTeam = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
        String awayTeam = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";
        int homeGoals = match.getHomeGoals();
        int awayGoals = match.getAwayGoals();

        int homeShotsOn = count(events, "ShotOnTargetEvent", homeTeam);
        int awayShotsOn = count(events, "ShotOnTargetEvent", awayTeam);
        int homeShotsOff = count(events, "ShotOffTargetEvent", homeTeam);
        int awayShotsOff = count(events, "ShotOffTargetEvent", awayTeam);
        int homeCorners = count(events, "CornerEvent", homeTeam);
        int awayCorners = count(events, "CornerEvent", awayTeam);
        int homeYellows = count(events, "YellowCardEvent", homeTeam);
        int awayYellows = count(events, "YellowCardEvent", awayTeam);
        int homeReds = count(events, "RedCardEvent", homeTeam);
        int awayReds = count(events, "RedCardEvent", awayTeam);
        int homePens = count(events, "PenaltyEvent", homeTeam);
        int awayPens = count(events, "PenaltyEvent", awayTeam);
        int homeGoalsFromEvents = countGoals(events, homeTeam);
        int awayGoalsFromEvents = countGoals(events, awayTeam);

        int adjHomeShotsOn = Math.max(homeShotsOn, homeGoalsFromEvents);
        int adjAwayShotsOn = Math.max(awayShotsOn, awayGoalsFromEvents);
        int homeShots = Math.max(adjHomeShotsOn + homeShotsOff, homeGoals);
        int awayShots = Math.max(adjAwayShotsOn + awayShotsOff, awayGoals);
        int homePoss = possessionShare(events, homeTeam, awayTeam, homeShotsOn, awayShotsOn, homeShotsOff, awayShotsOff, homeCorners, awayCorners, homePens, awayPens);
        int awayPoss = 100 - homePoss;

        String headline = buildHeadline(homeTeam, awayTeam, homeGoals, awayGoals);
        String report = buildReport(homeTeam, awayTeam, homeGoals, awayGoals, homePoss, awayPoss,
                homeShots, awayShots, adjHomeShotsOn, adjAwayShotsOn,
                homeCorners, awayCorners, homeYellows, awayYellows, homeReds, awayReds,
                events);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchId", matchId);
        payload.put("headline", headline);
        payload.put("manOfTheMatch", resolveManOfTheMatch(matchId));
        payload.put("report", report);
        return payload;
    }

    private Map<String, Object> resolveManOfTheMatch(Long matchId) {
        return matchPlayerStatsRepository.findByMatchId(matchId).stream()
                .filter(stats -> stats.getPlayer() != null)
                .max(Comparator
                        .comparingInt(MatchPlayerStats::getRating)
                        .thenComparingInt(MatchPlayerStats::getGoals)
                        .thenComparingInt(MatchPlayerStats::getAssists)
                        .thenComparingInt(MatchPlayerStats::getSaves)
                        .thenComparingInt(MatchPlayerStats::getInterceptions)
                        .thenComparingInt(MatchPlayerStats::getMinutesPlayed)
                        .thenComparing(stats -> stats.isCleanSheet() ? 1 : 0))
                .map(this::toManOfTheMatchPayload)
                .orElse(null);
    }

    private Map<String, Object> toManOfTheMatchPayload(MatchPlayerStats stats) {
        Player player = stats.getPlayer();
        double rating10 = Math.round((stats.getRating() / 10.0) * 10.0) / 10.0;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", player.getId());
        payload.put("playerName", safe(player.getName()));
        payload.put("teamId", player.getTeam() != null ? player.getTeam().getId() : null);
        payload.put("teamName", player.getTeam() != null ? safe(player.getTeam().getName()) : "Unknown");
        payload.put("rating", stats.getRating());
        payload.put("rating10", rating10);
        payload.put("goals", stats.getGoals());
        payload.put("assists", stats.getAssists());
        payload.put("minutesPlayed", stats.getMinutesPlayed());
        payload.put("saves", stats.getSaves());
        payload.put("interceptions", stats.getInterceptions());
        payload.put("cleanSheet", stats.isCleanSheet());
        return payload;
    }

    private String buildHeadline(String homeTeam, String awayTeam, int homeGoals, int awayGoals) {
        if (homeGoals == awayGoals) {
            return homeTeam + " and " + awayTeam + " shared the points in a " + homeGoals + "-" + awayGoals + " draw.";
        }
        String winner = homeGoals > awayGoals ? homeTeam : awayTeam;
        int margin = Math.abs(homeGoals - awayGoals);
        String tone = margin >= 3 ? "with authority" : margin == 2 ? "with a comfortable margin" : "by the narrowest of margins";
        return winner + " won " + tone + ": " + homeTeam + " " + homeGoals + "-" + awayGoals + " " + awayTeam + ".";
    }

    private String buildReport(String homeTeam, String awayTeam, int homeGoals, int awayGoals,
                               int homePoss, int awayPoss, int homeShots, int awayShots,
                               int homeShotsOn, int awayShotsOn, int homeCorners, int awayCorners,
                               int homeYellows, int awayYellows, int homeReds, int awayReds,
                               List<MatchEventFlatDTO> events) {
        StringBuilder sb = new StringBuilder();
        sb.append(homeTeam).append(' ').append(homeGoals).append("-").append(awayGoals).append(' ').append(awayTeam).append("\n\n");
        sb.append(homeTeam).append(" controlled an estimated ").append(homePoss).append("% of the game and finished with ")
                .append(homeShots).append(" shots (").append(homeShotsOn).append(" on target), while ")
                .append(awayTeam).append(" answered with ").append(awayShots).append(" shots (").append(awayShotsOn).append(" on target) and roughly ")
                .append(awayPoss).append("% of the control.");

        List<String> goals = events.stream()
                .filter(e -> "GoalEvent".equals(e.getEventType()) && !Boolean.FALSE.equals(e.getGoalScored()))
                .sorted(Comparator.comparingInt(e -> e.getMatchMinute() != null ? e.getMatchMinute() : 999))
                .map(e -> e.getMatchMinute() + "' " + safe(e.getScorer())
                        + (e.getAssistant() != null ? " (assist " + e.getAssistant() + ")" : "")
                        + (e.getScoreAfterGoal() != null ? " for " + e.getScoreAfterGoal() : ""))
                .toList();

        sb.append("\n\n");
        if (goals.isEmpty()) {
            sb.append("The match never found a decisive finishing streak and neither side managed to build a proper scoring run.");
        } else {
            sb.append("Goals arrived through ").append(String.join(", ", goals)).append('.');
        }

        sb.append("\n\n");
        sb.append("Set-pieces and discipline also shaped the flow: corners ")
                .append(homeTeam).append(' ').append(homeCorners).append("-").append(awayCorners).append(' ').append(awayTeam)
                .append(", yellow cards ").append(homeYellows).append("-").append(awayYellows)
                .append(", red cards ").append(homeReds).append("-").append(awayReds).append('.');

        List<String> keyMoments = events.stream()
                .sorted(Comparator.comparingInt(e -> e.getMatchMinute() != null ? e.getMatchMinute() : 999))
                .filter(event -> !isRedundantScoredPenalty(event, events))
                .map(this::toMomentLine)
                .filter(Objects::nonNull)
                .distinct()
                .limit(7)
                .toList();

        if (!keyMoments.isEmpty()) {
            sb.append("\n\nKey moments:\n");
            sb.append(keyMoments.stream().map(line -> "• " + line).collect(Collectors.joining("\n")));
        }
        return sb.toString().trim();
    }

    private int count(List<MatchEventFlatDTO> events, String eventType, String teamName) {
        return (int) events.stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> Objects.equals(resolveTeam(e), teamName))
                .count();
    }

    private int countGoals(List<MatchEventFlatDTO> events, String teamName) {
        return (int) events.stream()
                .filter(e -> "GoalEvent".equals(e.getEventType()) && !Boolean.FALSE.equals(e.getGoalScored()))
                .filter(e -> Objects.equals(e.getScoreTeam(), teamName))
                .count();
    }

    private int possessionShare(List<MatchEventFlatDTO> events, String homeTeam, String awayTeam,
                                int homeShotsOn, int awayShotsOn, int homeShotsOff, int awayShotsOff,
                                int homeCorners, int awayCorners, int homePens, int awayPens) {
        double homeWeight = (count(events, "ChanceEvent", homeTeam) * 3.0) + (homeShotsOn * 2.0) + (homeShotsOff * 1.4)
                + (homeCorners * 1.2) + (count(events, "FreeKickEvent", homeTeam) * 0.9) + (homePens * 1.3) + (countGoals(events, homeTeam) * 1.1);
        double awayWeight = (count(events, "ChanceEvent", awayTeam) * 3.0) + (awayShotsOn * 2.0) + (awayShotsOff * 1.4)
                + (awayCorners * 1.2) + (count(events, "FreeKickEvent", awayTeam) * 0.9) + (awayPens * 1.3) + (countGoals(events, awayTeam) * 1.1);
        double baseline = 18.0;
        double total = (homeWeight + baseline) + (awayWeight + baseline);
        int homePoss = total > 0 ? (int) Math.round(((homeWeight + baseline) / total) * 100) : 50;
        return Math.max(32, Math.min(68, homePoss));
    }

    private String toMomentLine(MatchEventFlatDTO event) {
        Integer minute = event.getMatchMinute();
        if (minute == null) {
            return null;
        }
        return switch (safe(event.getEventType())) {
            case "GoalEvent" -> minute + "' Goal: " + safe(event.getScorer()) + (event.getScoreAfterGoal() != null ? " (" + event.getScoreAfterGoal() + ")" : "");
            case "PenaltyEvent" -> minute + "' Penalty for " + safe(resolveTeam(event)) + " - " + safe(event.getPenaltyTaker()) + (Boolean.TRUE.equals(event.getPenaltyScored()) ? " scored" : " missed/saved");
            case "RedCardEvent" -> minute + "' Red card: " + safe(event.getRedCardPlayer()) + " (" + safe(resolveTeam(event)) + ")";
            case "YellowCardEvent" -> minute + "' Yellow card: " + safe(event.getYellowCardPlayer()) + " (" + safe(resolveTeam(event)) + ")";
            case "InjuryEvent" -> minute + "' Injury concern: " + safe(event.getInjuryPlayer()) + " (" + safe(resolveTeam(event)) + ")";
            case "SubstitutionEvent" -> minute + "' Substitution for " + safe(resolveTeam(event)) + ": " + safe(event.getPlayerOutName()) + " off, " + safe(event.getPlayerInName()) + " on";
            default -> null;
        };
    }

    private boolean isRedundantScoredPenalty(MatchEventFlatDTO event, List<MatchEventFlatDTO> events) {
        if (!"PenaltyEvent".equals(event.getEventType()) || !Boolean.TRUE.equals(event.getPenaltyScored())) {
            return false;
        }
        Integer minute = event.getMatchMinute();
        String penaltyTeam = resolveTeam(event);
        String penaltyTaker = safe(event.getPenaltyTaker());
        return events.stream().anyMatch(candidate ->
                "GoalEvent".equals(candidate.getEventType())
                        && !Boolean.FALSE.equals(candidate.getGoalScored())
                        && Objects.equals(candidate.getMatchMinute(), minute)
                        && Objects.equals(resolveTeam(candidate), penaltyTeam)
                        && (Objects.equals(safe(candidate.getScorer()), penaltyTaker)
                        || Objects.equals(penaltyTaker, "Unknown"))
        );
    }

    private String resolveTeam(MatchEventFlatDTO event) {
        if (event.getEventTeam() != null) return event.getEventTeam();
        if (event.getScoreTeam() != null) return event.getScoreTeam();
        if (event.getShotOnTargetTeam() != null) return event.getShotOnTargetTeam();
        if (event.getShotOffTargetTeam() != null) return event.getShotOffTargetTeam();
        if (event.getCornerTeam() != null) return event.getCornerTeam();
        if (event.getFreeKickTeam() != null) return event.getFreeKickTeam();
        if (event.getPenaltyTeam() != null) return event.getPenaltyTeam();
        if (event.getYellowCardTeam() != null) return event.getYellowCardTeam();
        if (event.getRedCardTeam() != null) return event.getRedCardTeam();
        if (event.getInjuryTeam() != null) return event.getInjuryTeam();
        if (event.getSubstitutionTeam() != null) return event.getSubstitutionTeam();
        return event.getPossessionTeam();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }
}