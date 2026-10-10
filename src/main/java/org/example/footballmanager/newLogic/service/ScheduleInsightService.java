package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
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

    /**
     * A snapshot for every team asked for, built from **three bulk reads**.
     *
     * <p><b>This method is the whole of the ranking rebuild's cost, and it used to be three queries per
     * club.</b> Both ranking services call it over the entire world — 14,731 clubs — and it ran a squad
     * read, a template-lineup read and a results read for each one. That is roughly 44,000 queries, and
     * the results were worse than slow: every entity they returned stayed in the persistence context, so
     * the transaction could not begin its flush until it had dirty-checked the entire accumulated graph.
     *
     * <p>Observed on the owner's database rather than reasoned about. After a full round the ranking
     * rebuild had written **nothing**, logged neither its success nor its failure, and left its thread
     * RUNNABLE at 100% CPU twenty minutes later, inside
     * {@code DefaultFlushEntityEventListener.performDirtyCheck}. It was not going to finish.
     *
     * <p>The three reads happen once, before any snapshot is built. Nothing is loaded lazily afterwards,
     * so the flush has only what it wrote.
     *
     * <p><b>Behaviour is unchanged.</b> The same players, the same template lineup and the same five most
     * recent results go into each snapshot; only the number of round trips differs. The match query
     * filters on {@code played = true} and the per-team filter does the same, so the two agree.
     */
    public Map<Long, TeamSnapshot> buildTeamSnapshots(Collection<Team> teams) {
        if (teams == null || teams.isEmpty()) {
            return Map.of();
        }

        // Deduplicate first. Two callers pass a collection built from fixtures, and the same club can
        // appear on both sides of a season.
        Map<Long, Team> byId = new LinkedHashMap<>();
        for (Team team : teams) {
            if (team != null && team.getId() != null) {
                byId.putIfAbsent(team.getId(), team);
            }
        }
        if (byId.isEmpty()) {
            return Map.of();
        }
        Collection<Long> teamIds = byId.keySet();

        Map<Long, List<Player>> squadsByTeam = new LinkedHashMap<>();
        for (Player player : playerRepository.findByTeamIdIn(teamIds)) {
            if (player == null || player.getTeam() == null || player.getTeam().getId() == null) {
                continue;
            }
            squadsByTeam.computeIfAbsent(player.getTeam().getId(), key -> new ArrayList<>()).add(player);
        }

        // The query orders by id descending, so the first lineup seen for a team is the newest template.
        Map<Long, Lineup> templateByTeam = new LinkedHashMap<>();
        for (Lineup lineup : lineupRepository.findTemplatesForTeams(teamIds)) {
            if (lineup != null && lineup.getTeam() != null && lineup.getTeam().getId() != null) {
                templateByTeam.putIfAbsent(lineup.getTeam().getId(), lineup);
            }
        }

        Map<Long, List<Match>> matchesByTeam = new LinkedHashMap<>();
        for (Match match : matchRepository.findPlayedInvolvingAnyOf(teamIds)) {
            if (match == null || match.getHomeTeam() == null || match.getAwayTeam() == null) {
                continue;
            }
            matchesByTeam.computeIfAbsent(match.getHomeTeam().getId(), key -> new ArrayList<>()).add(match);
            Long awayId = match.getAwayTeam().getId();
            if (awayId != null && !awayId.equals(match.getHomeTeam().getId())) {
                matchesByTeam.computeIfAbsent(awayId, key -> new ArrayList<>()).add(match);
            }
        }

        Map<Long, TeamSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<Long, Team> entry : byId.entrySet()) {
            Long teamId = entry.getKey();
            snapshots.put(teamId, buildTeamSnapshot(entry.getValue(),
                    squadsByTeam.getOrDefault(teamId, List.of()),
                    templateByTeam.get(teamId),
                    matchesByTeam.getOrDefault(teamId, List.of())));
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

    /**
     * One team's snapshot from data already in hand.
     *
     * <p>Every read has been done by {@link #buildTeamSnapshots(Collection)}. A per-team read here would
     * put this method back to being an N+1 for the two-team preview path, which is small but is the same
     * defect in miniature.
     */
    private TeamSnapshot buildTeamSnapshot(Team team, List<Player> rawSquad, Lineup template,
                                          List<Match> allMatches) {
        List<Player> squad = rawSquad.stream().filter(Objects::nonNull).toList();
        List<Player> corePlayers = selectCorePlayers(template, squad);

        double baseStrength = corePlayers.stream()
                .mapToInt(player -> Math.max(1, player.getRating()))
                .average()
                .orElseGet(() -> squad.stream().mapToInt(player -> Math.max(1, player.getRating())).average().orElse(60.0));
        double availabilityPenalty = Math.max(0, 11 - corePlayers.size()) * 1.4;
        int strength = clampInt((int) Math.round(baseStrength - availabilityPenalty), 38, 92);

        List<Match> recentMatches = allMatches.stream()
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

    private List<Player> selectCorePlayers(Lineup template, List<Player> squad) {
        List<Player> availablePlayers = squad.stream()
                .filter(player -> !player.isInjured())
                .sorted(Comparator.comparingInt(Player::getRating).reversed())
                .toList();

        List<Player> selected = new ArrayList<>();
        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>();
        if (template != null) {
            template.getOrderedStartingPlayers().forEach(player -> addIfEligible(selected, selectedIds, player));
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
        // No prefetched snapshot: build one, through the same bulk path, so this fallback cannot become
        // the N+1 the prefetch was added to remove.
        return snapshot != null
                ? snapshot
                : buildTeamSnapshots(List.of(team)).getOrDefault(team.getId(),
                        new TeamSnapshot(60, 6.0, 0));
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