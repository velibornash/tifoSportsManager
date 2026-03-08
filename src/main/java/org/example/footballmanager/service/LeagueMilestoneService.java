package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.LeagueMilestonesDTO;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.repository.GoalEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LeagueMilestoneService {

    private final MatchRepository matchRepository;
    private final GoalEventRepository goalEventRepository;

    public LeagueMilestonesDTO buildLeagueMilestones(Competition league, int seasonYear) {
        List<Match> playedMatches = matchRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(league.getId(), seasonYear)
                .stream()
                .filter(Match::isPlayed)
                .filter(match -> match.getHomeTeam() != null && match.getAwayTeam() != null)
                .toList();

        List<GoalEvent> seasonGoals = goalEventRepository.findAll().stream()
                .filter(GoalEvent::isScored)
                .filter(goal -> goal.getMatch() != null
                        && goal.getMatch().getCompetition() != null
                        && Objects.equals(goal.getMatch().getCompetition().getId(), league.getId())
                        && Objects.equals(goal.getMatch().getSeasonYear(), seasonYear))
                .toList();

        return LeagueMilestonesDTO.builder()
                .seasonYear(seasonYear)
                .topScorer(resolveLeader(seasonGoals, true))
                .topAssist(resolveLeader(seasonGoals, false))
                .biggestWin(resolveBiggestWin(playedMatches))
                .biggestLoss(resolveBiggestLoss(playedMatches))
                .attendance(resolveAttendanceMilestone(playedMatches, null))
                .build();
    }

    public LeagueMilestonesDTO buildTeamMilestones(Team team, int seasonYear) {
        List<Match> playedMatches = matchRepository
                .findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(team.getId(), team.getId())
                .stream()
                .filter(match -> Objects.equals(match.getSeasonYear(), seasonYear))
                .filter(match -> match.getHomeTeam() != null && match.getAwayTeam() != null)
                .toList();

        List<GoalEvent> seasonGoals = goalEventRepository.findAll().stream()
                .filter(GoalEvent::isScored)
                .filter(goal -> goal.getMatch() != null
                        && Objects.equals(goal.getMatch().getSeasonYear(), seasonYear))
                .toList();

        List<GoalEvent> scoringGoals = seasonGoals.stream()
                .filter(goal -> sameTeam(goal.getScorer() != null ? goal.getScorer().getTeam() : null, team))
                .toList();
        List<GoalEvent> assistGoals = seasonGoals.stream()
                .filter(goal -> sameTeam(goal.getAssistant() != null ? goal.getAssistant().getTeam() : null, team))
                .toList();

        return LeagueMilestonesDTO.builder()
                .seasonYear(seasonYear)
                .topScorer(resolveLeader(scoringGoals, true))
                .topAssist(resolveLeader(assistGoals, false))
                .biggestWin(resolveBiggestWin(playedMatches, team))
                .biggestLoss(resolveBiggestLoss(playedMatches, team))
                .attendance(resolveAttendanceMilestone(playedMatches, team))
                .build();
    }

    private LeagueMilestonesDTO.MilestoneLeaderDTO resolveLeader(List<GoalEvent> seasonGoals, boolean scorerMode) {
        Map<String, LeaderAccumulator> counts = new HashMap<>();
        for (GoalEvent goal : seasonGoals) {
            Player player = scorerMode ? goal.getScorer() : goal.getAssistant();
            if (player == null) {
                continue;
            }
            String key = player.getId() != null ? "id:" + player.getId() : "name:" + safe(player.getName());
            LeaderAccumulator accumulator = counts.computeIfAbsent(key, ignored -> new LeaderAccumulator());
            accumulator.playerName = safe(player.getName());
            accumulator.teamName = resolveTeamName(goal, player);
            accumulator.value += 1;
        }

        return counts.values().stream()
                .sorted(Comparator
                        .comparingInt((LeaderAccumulator value) -> value.value).reversed()
                        .thenComparing(value -> safe(value.playerName)))
                .map(value -> LeagueMilestonesDTO.MilestoneLeaderDTO.builder()
                        .playerName(value.playerName)
                        .teamName(value.teamName)
                        .value(value.value)
                        .build())
                .findFirst()
                .orElse(null);
    }

    private LeagueMilestonesDTO.MatchMilestoneDTO resolveBiggestWin(List<Match> matches) {
        List<ResultCandidate> candidates = new ArrayList<>();
        for (Match match : matches) {
            if (match.getHomeGoals() > match.getAwayGoals()) {
                candidates.add(candidateFor(match, match.getHomeTeam(), match.getAwayTeam(), match.getHomeGoals(), match.getAwayGoals()));
            } else if (match.getAwayGoals() > match.getHomeGoals()) {
                candidates.add(candidateFor(match, match.getAwayTeam(), match.getHomeTeam(), match.getAwayGoals(), match.getHomeGoals()));
            }
        }
        return resolveResultCandidate(candidates);
    }

    private LeagueMilestonesDTO.MatchMilestoneDTO resolveBiggestWin(List<Match> matches, Team team) {
        List<ResultCandidate> candidates = new ArrayList<>();
        for (Match match : matches) {
            if (sameTeam(match.getHomeTeam(), team) && match.getHomeGoals() > match.getAwayGoals()) {
                candidates.add(candidateFor(match, match.getHomeTeam(), match.getAwayTeam(), match.getHomeGoals(), match.getAwayGoals()));
            } else if (sameTeam(match.getAwayTeam(), team) && match.getAwayGoals() > match.getHomeGoals()) {
                candidates.add(candidateFor(match, match.getAwayTeam(), match.getHomeTeam(), match.getAwayGoals(), match.getHomeGoals()));
            }
        }
        return resolveResultCandidate(candidates);
    }

    private LeagueMilestonesDTO.MatchMilestoneDTO resolveBiggestLoss(List<Match> matches) {
        List<ResultCandidate> candidates = new ArrayList<>();
        for (Match match : matches) {
            if (match.getHomeGoals() < match.getAwayGoals()) {
                candidates.add(candidateFor(match, match.getHomeTeam(), match.getAwayTeam(), match.getHomeGoals(), match.getAwayGoals()));
            } else if (match.getAwayGoals() < match.getHomeGoals()) {
                candidates.add(candidateFor(match, match.getAwayTeam(), match.getHomeTeam(), match.getAwayGoals(), match.getHomeGoals()));
            }
        }
        return resolveResultCandidate(candidates);
    }

    private LeagueMilestonesDTO.MatchMilestoneDTO resolveBiggestLoss(List<Match> matches, Team team) {
        List<ResultCandidate> candidates = new ArrayList<>();
        for (Match match : matches) {
            if (sameTeam(match.getHomeTeam(), team) && match.getHomeGoals() < match.getAwayGoals()) {
                candidates.add(candidateFor(match, match.getHomeTeam(), match.getAwayTeam(), match.getHomeGoals(), match.getAwayGoals()));
            } else if (sameTeam(match.getAwayTeam(), team) && match.getAwayGoals() < match.getHomeGoals()) {
                candidates.add(candidateFor(match, match.getAwayTeam(), match.getHomeTeam(), match.getAwayGoals(), match.getHomeGoals()));
            }
        }
        return resolveResultCandidate(candidates);
    }

    private LeagueMilestonesDTO.MatchMilestoneDTO resolveResultCandidate(List<ResultCandidate> candidates) {
        return candidates.stream()
                .max(Comparator
                        .comparingInt((ResultCandidate candidate) -> candidate.goalMargin)
                        .thenComparingInt(candidate -> candidate.teamGoals + candidate.opponentGoals)
                        .thenComparingInt(candidate -> candidate.roundNumber != null ? candidate.roundNumber : 0))
                .map(candidate -> LeagueMilestonesDTO.MatchMilestoneDTO.builder()
                        .matchId(candidate.matchId)
                        .teamName(candidate.teamName)
                        .opponentName(candidate.opponentName)
                        .teamGoals(candidate.teamGoals)
                        .opponentGoals(candidate.opponentGoals)
                        .goalMargin(candidate.goalMargin)
                        .summary(candidate.teamGoals + "-" + candidate.opponentGoals + " vs " + candidate.opponentName)
                        .context("" + candidate.teamName + (candidate.roundNumber != null ? " · Round " + candidate.roundNumber : ""))
                        .build())
                .orElse(null);
    }

    private LeagueMilestonesDTO.AttendanceMilestoneDTO resolveAttendanceMilestone(List<Match> matches, Team focalTeam) {
        List<Match> attendanceScope = focalTeam == null
                ? matches
                : matches.stream()
                .filter(match -> sameTeam(match.getHomeTeam(), focalTeam))
                .toList();
        if (attendanceScope.isEmpty()) {
            attendanceScope = matches;
        }

        List<Match> withAttendance = attendanceScope.stream()
                .filter(match -> match.getAttendance() != null && match.getAttendance() > 0)
                .toList();
        if (withAttendance.isEmpty()) {
            return LeagueMilestonesDTO.AttendanceMilestoneDTO.builder()
                    .insight(focalTeam != null
                            ? "Home crowd data will appear once played fixtures start filing gates."
                            : "Crowd data will appear once played fixtures start filing gates.")
                    .build();
        }

        int average = (int) Math.round(withAttendance.stream()
                .mapToInt(match -> match.getAttendance() != null ? match.getAttendance() : 0)
                .average()
                .orElse(0.0));

        Match highest = withAttendance.stream()
                .max(Comparator.comparingInt(match -> match.getAttendance() != null ? match.getAttendance() : 0))
                .orElse(null);
        Match lowest = withAttendance.stream()
                .min(Comparator.comparingInt(match -> match.getAttendance() != null ? match.getAttendance() : 0))
                .orElse(null);

        double overall = withAttendance.stream().mapToInt(match -> match.getAttendance()).average().orElse(0.0);
        double glamourAverage = withAttendance.stream()
                .filter(match -> resolveReputation(resolveAttendanceOpponent(match, focalTeam)) >= 60.0)
                .mapToInt(match -> match.getAttendance())
                .average()
                .orElse(overall);
        int maxRound = withAttendance.stream().mapToInt(match -> match.getRoundNumber() != null ? match.getRoundNumber() : 1).max().orElse(1);
        double lateSeasonAverage = withAttendance.stream()
                .filter(match -> (match.getRoundNumber() != null ? match.getRoundNumber() : 1) >= Math.max(1, (int) Math.ceil(maxRound * 0.65)))
                .mapToInt(match -> match.getAttendance())
                .average()
                .orElse(overall);

        return LeagueMilestonesDTO.AttendanceMilestoneDTO.builder()
                .averageAttendance(average)
                .highestAttendance(highest != null ? highest.getAttendance() : null)
                .highestMatchLabel(highest != null ? matchLabel(highest) : null)
                .lowestAttendance(lowest != null ? lowest.getAttendance() : null)
                .lowestMatchLabel(lowest != null ? matchLabel(lowest) : null)
                .insight(buildAttendanceInsight(overall, glamourAverage, lateSeasonAverage, average))
                .build();
    }

    private String buildAttendanceInsight(double overall, double glamourAverage, double lateSeasonAverage, int average) {
        if (overall <= 0.0) {
            return "Crowd data will appear once played fixtures start filing gates.";
        }
        if (glamourAverage >= overall * 1.07) {
            return "Bigger visiting clubs are lifting the gate whenever they come to town.";
        }
        if (lateSeasonAverage >= overall * 1.05) {
            return "The season run-in is pushing attendances upward as stakes rise.";
        }
        return "Crowds are holding steady around " + formatAttendance(average) + ".";
    }

    private ResultCandidate candidateFor(Match match, Team team, Team opponent, int teamGoals, int opponentGoals) {
        ResultCandidate candidate = new ResultCandidate();
        candidate.matchId = match.getId();
        candidate.teamName = safe(team != null ? team.getName() : null);
        candidate.opponentName = safe(opponent != null ? opponent.getName() : null);
        candidate.teamGoals = teamGoals;
        candidate.opponentGoals = opponentGoals;
        candidate.goalMargin = Math.abs(teamGoals - opponentGoals);
        candidate.roundNumber = match.getRoundNumber();
        return candidate;
    }

    private String resolveTeamName(GoalEvent goal, Player player) {
        if (goal.getTeam() != null && goal.getTeam().getName() != null && !goal.getTeam().getName().isBlank()) {
            return goal.getTeam().getName();
        }
        if (player.getTeam() != null && player.getTeam().getName() != null) {
            return player.getTeam().getName();
        }
        return "No Team";
    }

    private double resolveReputation(Team team) {
        return team != null && team.getReputation() != null ? team.getReputation() : 50.0;
    }

    private Team resolveAttendanceOpponent(Match match, Team focalTeam) {
        if (focalTeam == null) {
            return match.getAwayTeam();
        }
        if (sameTeam(match.getHomeTeam(), focalTeam)) {
            return match.getAwayTeam();
        }
        if (sameTeam(match.getAwayTeam(), focalTeam)) {
            return match.getHomeTeam();
        }
        return match.getAwayTeam();
    }

    private boolean sameTeam(Team first, Team second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getId() != null && second.getId() != null) {
            return Objects.equals(first.getId(), second.getId());
        }
        return Objects.equals(safe(first.getName()), safe(second.getName()));
    }

    private String matchLabel(Match match) {
        return safe(match.getHomeTeam().getName()) + " vs " + safe(match.getAwayTeam().getName());
    }

    private String formatAttendance(int value) {
        return String.format("%,d", value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class LeaderAccumulator {
        private String playerName;
        private String teamName;
        private int value;
    }

    private static class ResultCandidate {
        private Long matchId;
        private String teamName;
        private String opponentName;
        private int teamGoals;
        private int opponentGoals;
        private int goalMargin;
        private Integer roundNumber;
    }
}