package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScheduleInsightService {

    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;

    public Map<Long, TeamSnapshot> buildTeamSnapshots(Collection<Team> teams) {
        if (teams == null || teams.isEmpty()) {
            return Map.of();
        }

        Map<Long, TeamSnapshot> snapshots = new LinkedHashMap<>();
        for (Team team : teams) {
            if (team == null || team.getId() == null || snapshots.containsKey(team.getId())) {
                continue;
            }
            snapshots.put(team.getId(), buildTeamSnapshot(team));
        }
        return snapshots;
    }

    public FixtureInsights buildFixtureInsights(Team homeTeam, Team awayTeam) {
        return buildFixtureInsights(homeTeam, awayTeam, buildTeamSnapshots(List.of(homeTeam, awayTeam)));
    }

    public FixtureInsights buildFixtureInsights(Team homeTeam, Team awayTeam, Map<Long, TeamSnapshot> snapshotByTeamId) {
        TeamSnapshot home = resolveSnapshot(homeTeam, snapshotByTeamId);
        TeamSnapshot away = resolveSnapshot(awayTeam, snapshotByTeamId);
        Prediction prediction = buildPrediction(home, away);
        return new FixtureInsights(home.strength(), away.strength(), home.form(), away.form(), prediction);
    }

    private TeamSnapshot buildTeamSnapshot(Team team) {
        List<Player> squad = Optional.ofNullable(playerRepository.findByTeamId(team.getId())).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .toList();
        List<Player> corePlayers = selectCorePlayers(team.getId(), squad);

        double baseStrength = corePlayers.stream()
                .mapToInt(player -> Math.max(1, player.getRating()))
                .average()
                .orElseGet(() -> squad.stream().mapToInt(player -> Math.max(1, player.getRating())).average().orElse(60.0));
        double availabilityPenalty = Math.max(0, 11 - corePlayers.size()) * 1.4;
        int strength = clampInt((int) Math.round(baseStrength - availabilityPenalty), 38, 92);

        List<Match> recentMatches = Optional.ofNullable(matchRepository.findByHomeTeamIdOrAwayTeamId(team.getId(), team.getId()))
                .orElse(List.of())
                .stream()
                .filter(Match::isPlayed)
                .filter(match -> match.getHomeTeam() != null && match.getAwayTeam() != null)
                .sorted((left, right) -> {
                    if (left.getMatchDate() == null && right.getMatchDate() == null) return 0;
                    if (left.getMatchDate() == null) return 1;
                    if (right.getMatchDate() == null) return -1;
                    return right.getMatchDate().compareTo(left.getMatchDate());
                })
                .limit(5)
                .toList();

        double squadForm = squad.stream()
                .filter(player -> !player.isInjured())
                .mapToDouble(player -> clamp(player.getForm(), 1.0, 10.0))
                .average()
                .orElse(6.0);
        double recentForm = calculateRecentForm(team.getId(), recentMatches, squadForm);

        return new TeamSnapshot(strength, round1(recentForm), recentMatches.size());
    }

    private List<Player> selectCorePlayers(Long teamId, List<Player> squad) {
        List<Player> availablePlayers = squad.stream()
                .filter(player -> !player.isInjured())
                .sorted(Comparator.comparingInt(Player::getRating).reversed())
                .toList();

        List<Player> selected = new ArrayList<>();
        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>();
        if (teamId != null) {
            Optional<Lineup> lineup = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId);
            lineup.ifPresent(value -> value.getOrderedStartingPlayers().forEach(player -> addIfEligible(selected, selectedIds, player)));
        }

        for (Player player : availablePlayers) {
            if (selected.size() >= 11) break;
            addIfEligible(selected, selectedIds, player);
        }

        if (selected.isEmpty()) {
            return squad.stream()
                    .sorted(Comparator.comparingInt(Player::getRating).reversed())
                    .limit(11)
                    .toList();
        }
        return selected.size() > 11 ? selected.subList(0, 11) : selected;
    }

    private void addIfEligible(List<Player> selected, LinkedHashSet<Long> selectedIds, Player player) {
        if (player == null || player.isInjured() || selected.size() >= 11) {
            return;
        }
        Long playerId = player.getId();
        if (playerId == null) {
            selected.add(player);
            return;
        }
        if (selectedIds.add(playerId)) {
            selected.add(player);
        }
    }

    private double calculateRecentForm(Long teamId, List<Match> recentMatches, double squadForm) {
        if (recentMatches.isEmpty()) {
            return clamp(0.55 * squadForm + 0.45 * 6.0, 1.0, 10.0);
        }

        double pointsPerGame = recentMatches.stream()
                .mapToDouble(match -> pointsFor(teamId, match))
                .average()
                .orElse(1.0);
        double goalDiffPerGame = recentMatches.stream()
                .mapToDouble(match -> goalDiffFor(teamId, match))
                .average()
                .orElse(0.0);

        double resultForm = 4.7 + pointsPerGame * 1.35 + goalDiffPerGame * 0.32;
        return clamp(resultForm * 0.68 + squadForm * 0.32, 1.0, 10.0);
    }

    private Prediction buildPrediction(TeamSnapshot home, TeamSnapshot away) {
        double strengthEdge = (home.strength() - away.strength()) / 7.5;
        double formEdge = (home.form() - away.form()) * 0.58;
        double rawEdge = strengthEdge + formEdge + 0.7;

        double drawProbability = 0.18 + Math.max(0.0, 1.0 - Math.min(1.0, Math.abs(rawEdge) / 3.4)) * 0.16;
        double decisiveShare = 1.0 - drawProbability;
        double homeShare = 1.0 / (1.0 + Math.exp(-rawEdge / 1.55));
        double homeWinProbability = decisiveShare * homeShare;
        double awayWinProbability = decisiveShare - homeWinProbability;

        int homeWinPercent = clampPercent((int) Math.round(homeWinProbability * 100));
        int drawPercent = clampPercent((int) Math.round(drawProbability * 100));
        int awayWinPercent = 100 - homeWinPercent - drawPercent;
        if (awayWinPercent < 0) {
            awayWinPercent = 0;
            if (homeWinPercent >= drawPercent) {
                homeWinPercent = 100 - drawPercent;
            } else {
                drawPercent = 100 - homeWinPercent;
            }
        }

        double probabilitySwing = (homeWinPercent - awayWinPercent) / 100.0;
        double expectedHomeGoals = round2(clamp(
                0.45 + home.strength() / 76.0 + home.form() / 17.5 + 0.12 + probabilitySwing * 0.55,
                0.45,
                3.15
        ));
        double expectedAwayGoals = round2(clamp(
                0.28 + away.strength() / 80.0 + away.form() / 18.5 - probabilitySwing * 0.42,
                0.30,
                2.85
        ));

        String mostLikelyResult = homeWinPercent >= drawPercent && homeWinPercent >= awayWinPercent
                ? "HOME_WIN"
                : drawPercent >= awayWinPercent ? "DRAW" : "AWAY_WIN";
        double[] alignedExpectedGoals = alignExpectedGoals(expectedHomeGoals, expectedAwayGoals, mostLikelyResult, rawEdge, probabilitySwing);
        expectedHomeGoals = alignedExpectedGoals[0];
        expectedAwayGoals = alignedExpectedGoals[1];
        int confidence = clampInt((int) Math.round(51 + Math.abs(rawEdge) * 8 + Math.min(home.recentMatchCount(), away.recentMatchCount()) * 2), 48, 87);

        String lean = switch (mostLikelyResult) {
            case "HOME_WIN" -> "Home edge";
            case "AWAY_WIN" -> "Away edge";
            default -> "Balanced matchup";
        };
        String analysis = String.format(
                "%s · OVR %d:%d · form %.1f:%.1f",
                lean,
                home.strength(),
                away.strength(),
                home.form(),
                away.form()
        );

        return new Prediction(
                homeWinPercent,
                drawPercent,
                awayWinPercent,
                expectedHomeGoals,
                expectedAwayGoals,
                mostLikelyResult,
                confidence,
                analysis
        );
    }

    private double[] alignExpectedGoals(double homeExpectedGoals,
                                        double awayExpectedGoals,
                                        String mostLikelyResult,
                                        double rawEdge,
                                        double probabilitySwing) {
        double totalGoals = Math.max(0.90, homeExpectedGoals + awayExpectedGoals);
        double minGap = Math.max(0.08, Math.min(0.72, Math.abs(probabilitySwing) * 0.95 + Math.abs(rawEdge) * 0.16));

        if ("HOME_WIN".equals(mostLikelyResult) && homeExpectedGoals <= awayExpectedGoals) {
            return rebalanceExpectedGoals(totalGoals, minGap, true);
        }
        if ("AWAY_WIN".equals(mostLikelyResult) && awayExpectedGoals <= homeExpectedGoals) {
            return rebalanceExpectedGoals(totalGoals, minGap, false);
        }
        if ("DRAW".equals(mostLikelyResult) && Math.abs(homeExpectedGoals - awayExpectedGoals) > 0.18) {
            double shared = round2(totalGoals / 2.0);
            return new double[]{shared, shared};
        }
        return new double[]{homeExpectedGoals, awayExpectedGoals};
    }

    private double[] rebalanceExpectedGoals(double totalGoals, double desiredGap, boolean homeLeans) {
        double dominantGoals = round2(clamp((totalGoals + desiredGap) / 2.0, 0.45, 3.15));
        double supportGoals = round2(clamp(totalGoals - dominantGoals, 0.30, 2.85));

        if (homeLeans && dominantGoals <= supportGoals) {
            dominantGoals = round2(clamp(supportGoals + 0.08, 0.45, 3.15));
        } else if (!homeLeans && dominantGoals <= supportGoals) {
            dominantGoals = round2(clamp(supportGoals + 0.08, 0.45, 3.15));
        }

        return homeLeans
                ? new double[]{dominantGoals, supportGoals}
                : new double[]{supportGoals, dominantGoals};
    }

    private TeamSnapshot resolveSnapshot(Team team, Map<Long, TeamSnapshot> snapshotByTeamId) {
        if (team == null || team.getId() == null) {
            return new TeamSnapshot(60, 6.0, 0);
        }
        TeamSnapshot snapshot = snapshotByTeamId == null ? null : snapshotByTeamId.get(team.getId());
        return snapshot != null ? snapshot : buildTeamSnapshot(team);
    }

    private int pointsFor(Long teamId, Match match) {
        int goalDiff = goalDiffFor(teamId, match);
        if (goalDiff > 0) return 3;
        if (goalDiff == 0) return 1;
        return 0;
    }

    private int goalDiffFor(Long teamId, Match match) {
        boolean isHome = Objects.equals(match.getHomeTeam().getId(), teamId);
        int teamGoals = isHome ? match.getHomeGoals() : match.getAwayGoals();
        int opponentGoals = isHome ? match.getAwayGoals() : match.getHomeGoals();
        return teamGoals - opponentGoals;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampPercent(int value) {
        return clampInt(value, 0, 100);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record TeamSnapshot(int strength, double form, int recentMatchCount) {}

    public record Prediction(
            int homeWinProbability,
            int drawProbability,
            int awayWinProbability,
            double expectedHomeGoals,
            double expectedAwayGoals,
            String mostLikelyResult,
            int confidence,
            String analysis
    ) {}

    public record FixtureInsights(
            int homeTeamStrength,
            int awayTeamStrength,
            double homeTeamForm,
            double awayTeamForm,
            Prediction prediction
    ) {}
}