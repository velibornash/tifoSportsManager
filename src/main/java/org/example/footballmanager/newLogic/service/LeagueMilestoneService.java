package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.dto.LeagueMilestonesDTO;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.event.GoalEvent;
import org.example.footballmanager.newLogic.repository.GoalEventRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import org.example.footballmanager.newLogic.repository.PlayerRepository;

import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LeagueMilestoneService {

    private final MatchRepository matchRepository;
    private final GoalEventRepository goalEventRepository;
    private final PlayerRepository playerRepository;
    private final HonourService honours;

    private final Map<Long, String> clubNames = new HashMap<>();
    private final Map<Long, Set<Long>> clubPlayerIds = new HashMap<>();

    public LeagueMilestonesDTO buildLeagueMilestones(Competition league, int seasonYear) {
        List<Match> playedMatches = matchRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(league.getId(), seasonYear)
                .stream()
                .filter(Match::isPlayed)
                .filter(match -> match.getHomeTeam() != null && match.getAwayTeam() != null)
                .toList();

        List<GoalEvent> seasonGoals = goalEventRepository
                .findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(league.getId(), seasonYear);

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

        // **These goals must be this club's goals.** It used to read every goal in the season, in every
        // competition, by every club in the world - the league call - so OFK Omladinac's Club Milestones
        // named the top scorer of the entire planet. The matches beside it were already filtered to the
        // club, which is why the biggest win and the attendance were right and only the two leaders were
        // not. It is the same "league page answered a club question" mistake as the club page showing the
        // league table.
        //
        // **Read once.** These two were the same call, twice: the season's whole event log walked and
        // parsed once to find goals and again to find assists, and the second walk threw the first one's
        // work away. `findByMatchSeasonYearAndScoredTrue` is the heaviest read in the service - it walks
        // every match of the season and parses each one's event log - so this was the whole cost of the
        // call paid twice for two answers that come out of the same list.
        //
        // One read, two derivations. The filters are unchanged and stay separate, because a goal with no
        // assist keys is not an assist and must not be invented from the absence.
        List<GoalEvent> seasonGoals = goalEventRepository.findByMatchSeasonYearAndScoredTrue(seasonYear);
        List<GoalEvent> scoringGoals = seasonGoals.stream()
                .filter(goal -> goal.scorerName() != null)
                .filter(goal -> playsForClub(goal.scorerId(), team))
                .toList();
        List<GoalEvent> assistGoals = seasonGoals.stream()
                .filter(goal -> goal.assistantName() != null && goal.assistantId() != null)
                .filter(goal -> playsForClub(goal.assistantId(), team))
                .toList();

        return LeagueMilestonesDTO.builder()
                .seasonYear(seasonYear)
                .topScorer(resolveLeader(scoringGoals, true))
                .topAssist(resolveLeader(assistGoals, false))
                .biggestWin(resolveBiggestWin(playedMatches, team))
                .biggestLoss(resolveBiggestLoss(playedMatches, team))
                .attendance(resolveAttendanceMilestone(playedMatches, team))
                // Honours are a club's whole history, not this season's: a title won in season 1 is still
                // on the board in season 6. Read from the stored rows rather than re-derived, because the
                // derivation rewrites a whole season and a page load must not trigger one.
                .trophies(honours.honoursOf(team).stream()
                        .map(h -> LeagueMilestonesDTO.TrophyMilestoneDTO.builder()
                                .competitionName(h.getCompetitionName())
                                .seasonYear(h.getSeasonYear())
                                .medal(h.getMedal().name())
                                .build())
                        .toList())
                .build();
    }

    private LeagueMilestonesDTO.MilestoneLeaderDTO resolveLeader(List<GoalEvent> seasonGoals, boolean scorerMode) {
        Map<String, LeaderAccumulator> counts = new HashMap<>();
        for (GoalEvent goal : seasonGoals) {
            String playerName = scorerMode ? goal.scorerName() : goal.assistantName();
            if (playerName == null || playerName.isBlank()) {
                continue;
            }
            long playerId = scorerMode ? goal.scorerId() : goal.assistantId();
            String key = playerId > 0 ? "id:" + playerId : "name:" + safe(playerName);
            LeaderAccumulator accumulator = counts.computeIfAbsent(key, ignored -> new LeaderAccumulator());
            accumulator.playerName = safe(playerName);
            accumulator.teamName = resolveTeamName(playerId);
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

    /**
     * Does this player belong to this club?
     *
     * <p>Used by the club milestones, where the answer decides whose goal counts. A player who has since
     * transferred is credited to the club they now play for, which is the same rule the league top scorers
     * use — and it is a rule, not an accident, so it is written once here rather than re-decided at each
     * call site.
     *
     * <p>The club's own players are read once per call, not once per goal: the milestone leader is a
     * handful of players and the world holds every player of every club in every country.
     */
    private boolean playsForClub(long playerId, Team team) {
        if (playerId <= 0 || team == null || team.getId() == null) {
            return false;
        }
        Set<Long> clubPlayerIds = clubPlayerIds(team.getId());
        return clubPlayerIds.contains(playerId);
    }

    /** Memoised per club, so a season of goals does not re-read the same squad for every one of them. */
    private Set<Long> clubPlayerIds(Long clubId) {
        return clubPlayerIds.computeIfAbsent(clubId, id -> {
            Set<Long> ids = new HashSet<>();
            for (Player player : playerRepository.findByTeamId(id)) {
                if (player.getId() != null) {
                    ids.add(player.getId());
                }
            }
            return ids;
        });
    }

    /**
     * The club a milestone leader plays for.
     *
     * <p>This used to return {@code goal.teamSide()}, which is the string {@code "HOME"} or {@code "AWAY"},
     * so the club milestones' top scorer was credited to a side of the pitch rather than to a club. The
     * team the player belongs to is the answer; the side they happened to be playing on is not.
     */
    private String resolveTeamName(long playerId) {
        return clubNameOf(playerId);
    }

    /**
     * The club name for one player, memoised.
     *
     * <p>Deliberately not {@code playerRepository.findAll()}: a milestone leader is a handful of players,
     * and the world holds every player of every club in every country. Loading them all to answer that
     * question is the same mistake as the one this method was written to fix.
     */
    private String clubNameOf(long playerId) {
        String cached = clubNames.get(playerId);
        if (cached != null) {
            return cached;
        }
        String name = playerRepository.findById(playerId)
                .map(Player::getTeam)
                .map(Team::getName)
                .orElse("Unknown club");
        clubNames.put(playerId, name);
        return name;
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
