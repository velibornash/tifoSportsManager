package org.example.footballmanager.zox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.CornerEvent;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.InjuryEvent;
import org.example.footballmanager.model.event.InterceptionEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.OffsideEvent;
import org.example.footballmanager.model.event.PassEvent;
import org.example.footballmanager.model.event.RedCardEvent;
import org.example.footballmanager.model.event.ShotOffTargetEvent;
import org.example.footballmanager.model.event.ShotOnTargetEvent;
import org.example.footballmanager.model.event.SubstitutionEvent;
import org.example.footballmanager.model.event.VARReviewEvent;
import org.example.footballmanager.model.event.YellowCardEvent;
import org.example.footballmanager.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamTacticsProfileRepository;
import org.example.footballmanager.service.ScheduleInsightService;
import org.example.footballmanager.util.match.MatchAnalyticsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ZOX Match Analytics Service
 * Generates detailed match previews, player ratings, and statistics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoxMatchAnalyticsService {

    private final MatchRepository matchRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final MatchAnalyticsService matchAnalyticsService;
    private final MatchEventRepository matchEventRepository;
    private final ScheduleInsightService scheduleInsightService;
    private final TeamTacticsProfileRepository teamTacticsProfileRepository;

    public ZoxMatchPreviewDTO generateMatchPreview(Long matchId) {
        Match match = loadMatchDetailed(matchId);
        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();

        ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(homeTeam, awayTeam);
        ZoxMatchPredictionDTO prediction = calculateMatchPrediction(match);
        Lineup homeLineup = resolveLineup(match, homeTeam);
        Lineup awayLineup = resolveLineup(match, awayTeam);
        TeamPreviewMetrics homeMetrics = buildTeamPreviewMetrics(match, homeTeam, homeLineup);
        TeamPreviewMetrics awayMetrics = buildTeamPreviewMetrics(match, awayTeam, awayLineup);

        return ZoxMatchPreviewDTO.builder()
                .matchId(matchId)
                .homeTeamId(homeTeam != null ? homeTeam.getId() : null)
                .awayTeamId(awayTeam != null ? awayTeam.getId() : null)
                .homeTeamName(homeTeam != null ? homeTeam.getName() : "Home")
                .awayTeamName(awayTeam != null ? awayTeam.getName() : "Away")
                .homeFormation(homeMetrics.formation())
                .awayFormation(awayMetrics.formation())
                .homePlayStyle(homeMetrics.style())
                .awayPlayStyle(awayMetrics.style())
                .homeWinProbability(prediction.getHomeWinProbability())
                .drawProbability(prediction.getDrawProbability())
                .awayWinProbability(prediction.getAwayWinProbability())
                .expectedResult(prediction.getMostLikelyResult())
                .expectedHomeGoals(prediction.getExpectedHomeGoals())
                .expectedAwayGoals(prediction.getExpectedAwayGoals())
                .homeTeamRating(insights.homeTeamStrength())
                .awayTeamRating(insights.awayTeamStrength())
                .homeRecentForm(homeMetrics.recentForm())
                .awayRecentForm(awayMetrics.recentForm())
                .homeRecentFormPoints(homeMetrics.formPoints())
                .awayRecentFormPoints(awayMetrics.formPoints())
                .homeFormationFitness(homeMetrics.formationFitness())
                .awayFormationFitness(awayMetrics.formationFitness())
                .homeBenchQuality(homeMetrics.benchQuality())
                .awayBenchQuality(awayMetrics.benchQuality())
                .homePositionMismatches(homeMetrics.positionMismatches())
                .awayPositionMismatches(awayMetrics.positionMismatches())
                .homeAvailabilityScore(homeMetrics.availabilityScore())
                .awayAvailabilityScore(awayMetrics.availabilityScore())
                .homeLineup(getPlayerRatingsForTeam(matchId, homeTeam))
                .awayLineup(getPlayerRatingsForTeam(matchId, awayTeam))
                .homeSubstitutes(getBenchRatings(homeLineup, match))
                .awaySubstitutes(getBenchRatings(awayLineup, match))
                .keyMatchups(buildKeyMatchups(homeMetrics, awayMetrics))
                .homeAbsentees(homeMetrics.absentees())
                .awayAbsentees(awayMetrics.absentees())
                .predictionReasons(buildPredictionReasons(insights, prediction, homeMetrics, awayMetrics))
                .homeInsights(buildPreviewInsights(homeMetrics, true))
                .awayInsights(buildPreviewInsights(awayMetrics, false))
                .analysisText(buildAnalysisText(prediction, homeMetrics, awayMetrics))
                .build();
    }

    public Map<String, Object> generatePlayerRatings(Long matchId) {
        Match match = loadMatchWithTeams(matchId);
        return Map.of(
                "homeTeam", match.getHomeTeam().getName(),
                "awayTeam", match.getAwayTeam().getName(),
                "homePlayers", getPlayerRatingsForTeam(matchId, match.getHomeTeam()),
                "awayPlayers", getPlayerRatingsForTeam(matchId, match.getAwayTeam())
        );
    }

    public ZoxMatchStatsDTO generateMatchStats(Long matchId) {
        Match match = loadMatchDetailed(matchId);
        return buildDetailedStats(match);
    }

    public ZoxPostMatchReportDTO generatePostMatchReport(Long matchId) {
        Match match = loadMatchDetailed(matchId);
        ZoxMatchStatsDTO stats = buildDetailedStats(match);
        List<MatchPlayerStats> playerStats = matchPlayerStatsRepository.findByMatchId(matchId);
        ZoxTopPerformerDTO motm = selectManOfTheMatch(playerStats).orElse(null);
        List<ZoxTopPerformerDTO> homeTop = topPerformersForTeam(playerStats, match.getHomeTeam(), 3);
        List<ZoxTopPerformerDTO> awayTop = topPerformersForTeam(playerStats, match.getAwayTeam(), 3);
        List<ZoxTimelineEventDTO> timeline = buildTimeline(match);

        return ZoxPostMatchReportDTO.builder()
                .matchId(matchId)
                .headline(buildReportHeadline(match))
                .summary(buildReportSummary(match, stats))
                .turningPoint(resolveTurningPoint(timeline))
                .tacticalVerdict(buildTacticalVerdict(stats))
                .playerOfTheMatch(motm)
                .homeTopPerformers(homeTop)
                .awayTopPerformers(awayTop)
                .timeline(timeline)
                .stats(stats)
                .build();
    }

    public ZoxMatchPredictionDTO generateMatchPrediction(Long matchId) {
        return calculateMatchPrediction(loadMatchWithTeams(matchId));
    }

    public List<ZoxPlayerRatingDTO> getPlayerRatingsForTeam(Long matchId, Team team) {
        if (team == null || team.getId() == null) {
            return List.of();
        }

        Lineup lineup = resolveLineup(loadMatchDetailed(matchId), team);
        List<Player> starters = lineup != null && lineup.getStartingPlayers() != null
                ? lineup.getStartingPlayers()
                : List.of();

        return starters.stream()
                .filter(Objects::nonNull)
                .map(player -> calculatePlayerRating(player, lineup != null ? lineup.getMatch() : null))
                .toList();
    }

    public ZoxMatchPredictionDTO calculateMatchPrediction(Match match) {
        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();
        ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(home, away);

        int homeWin = insights.prediction().homeWinProbability();
        int draw = insights.prediction().drawProbability();
        int awayWin = insights.prediction().awayWinProbability();

        return ZoxMatchPredictionDTO.builder()
                .homeWinProbability(homeWin / 100.0)
                .drawProbability(draw / 100.0)
                .awayWinProbability(awayWin / 100.0)
                .expectedHomeGoals(insights.prediction().expectedHomeGoals())
                .expectedAwayGoals(insights.prediction().expectedAwayGoals())
                .mostLikelyResult(insights.prediction().mostLikelyResult())
                .analysis(insights.prediction().analysis())
                .confidence(insights.prediction().confidence())
                .build();
    }

    public Integer calculateTeamRating(Team team) {
        if (team == null || team.getId() == null) {
            return 50;
        }

        List<Player> players = playerRepository.findByTeamId(team.getId());
        if (players.isEmpty()) {
            return 50;
        }

        double avgRating = players.stream()
                .mapToDouble(this::getPlayerBaseRating)
                .average()
                .orElse(50.0);
        return (int) Math.round(avgRating);
    }

    public ZoxFormationDTO generateFormation(Long matchId, Team team) {
        Match match = loadMatch(matchId);
        Lineup lineup = resolveLineup(match, team);
        if (lineup == null) {
            return ZoxFormationDTO.builder()
                    .formation("4-3-3")
                    .positions(new ZoxFormationDTO.ZoxPlayerPositionDTO[0])
                    .build();
        }

        List<Player> startingPlayers = lineup.getStartingPlayers() != null ? lineup.getStartingPlayers() : List.of();
        ZoxFormationDTO.ZoxPlayerPositionDTO[] positions = new ZoxFormationDTO.ZoxPlayerPositionDTO[startingPlayers.size()];
        for (int i = 0; i < startingPlayers.size(); i++) {
            positions[i] = calculatePlayerPosition(startingPlayers.get(i), i);
        }

        return ZoxFormationDTO.builder()
                .formation(normalizeFormation(lineup.getFormation()))
                .positions(positions)
                .build();
    }

    public ZoxEventStreamDTO generateEventStream(Long matchId) {
        Match match = loadMatchDetailed(matchId);
        List<ZoxEventStreamDTO.ZoxMatchEventDTO> events = buildTimeline(match).stream()
                .map(event -> ZoxEventStreamDTO.ZoxMatchEventDTO.builder()
                        .minute(event.getMinute() != null ? event.getMinute() : 0)
                        .type(event.getType())
                        .teamName(event.getTeamName())
                        .playerName(event.getTitle())
                        .description(event.getDetail())
                        .eventIcon(event.getIcon())
                        .build())
                .toList();

        return ZoxEventStreamDTO.builder()
                .matchId(matchId)
                .minute(match.isFinished() ? 90 : 45)
                .timeStatus(match.isFinished() ? "FT" : "Live")
                .homeGoals(match.getHomeGoals())
                .awayGoals(match.getAwayGoals())
                .events(events)
                .build();
    }

    private ZoxPlayerRatingDTO calculatePlayerRating(Player player, Match match) {
        ZoxPlayerRatingDTO rating = ZoxPlayerRatingDTO.builder()
                .playerId(player.getId())
                .name(player.getName())
                .position(player.getPosition() != null ? player.getPosition().toString() : "Unknown")
                .squadNumber(player.getSquadNumber())
                .build();

        MatchPlayerStats stats = match != null ? matchPlayerStatsRepository.findByMatchAndPlayer(match, player) : null;
        if (stats != null) {
            rating.setPasses(0);
            rating.setSuccessfulPasses(0);
            rating.setTackles(stats.getInterceptions());
            rating.setShotsOnTarget(stats.getGoals());
            rating.setYellowCards(stats.getYellowCards());
            rating.setRedCards(stats.getRedCards());
            rating.setOverallRating(calculateOverallRating(player, stats));
            rating.setAttackRating(calculateAttackRating(player, stats));
            rating.setDefenseRating(calculateDefenseRating(player, stats));
            rating.setExpectedGoals((double) stats.getGoals());
            rating.setExpectedAssists((double) stats.getAssists());
        } else {
            rating.setOverallRating(getPlayerBaseRating(player));
            rating.setAttackRating(getPlayerAttributeRating(player, "attack"));
            rating.setDefenseRating(getPlayerAttributeRating(player, "defense"));
            rating.setExpectedGoals(0.0);
            rating.setExpectedAssists(0.0);
        }

        rating.setStatus(player.isInjured() ? "unavailable" : "active");
        return rating;
    }

    private Double calculateOverallRating(Player player, MatchPlayerStats stats) {
        double baseRating = getPlayerBaseRating(player);
        double statsModifier = 0.0;
        if (stats.getGoals() > 0) statsModifier += stats.getGoals() * 1.2;
        if (stats.getAssists() > 0) statsModifier += stats.getAssists();
        if (stats.getRating() > 0) statsModifier += (stats.getRating() / 10.0 - 6.0) * 0.75;
        return clamp(baseRating + statsModifier, 1.0, 10.0);
    }

    private Double calculateAttackRating(Player player, MatchPlayerStats stats) {
        return clamp(getPlayerAttributeRating(player, "attack") + stats.getGoals() * 1.0 + stats.getAssists() * 0.7, 1.0, 10.0);
    }

    private Double calculateDefenseRating(Player player, MatchPlayerStats stats) {
        double value = getPlayerAttributeRating(player, "defense") + stats.getInterceptions() * 0.12 + (stats.isCleanSheet() ? 0.5 : 0.0);
        value -= stats.getYellowCards() * 0.25 + stats.getRedCards() * 1.0;
        return clamp(value, 1.0, 10.0);
    }

    private Double getPlayerBaseRating(Player player) {
        if (player.getSkills() == null) {
            return clamp(player.getRating() / 10.0, 1.0, 10.0);
        }
        Skills skills = player.getSkills();
        double avg = (skills.getStamina()
                + skills.getGoalkeeper()
                + skills.getDefender()
                + skills.getPace()
                + skills.getTechnique()
                + skills.getPlaymaker()
                + skills.getPassing()
                + skills.getStriker()) / 8.0 / 2.0;
        return clamp(avg, 1.0, 10.0);
    }

    private Double getPlayerAttributeRating(Player player, String attribute) {
        if (player.getSkills() == null) {
            return clamp(player.getRating() / 10.0, 1.0, 10.0);
        }

        Skills skills = player.getSkills();
        return switch (attribute) {
            case "attack" -> clamp((skills.getStriker() + skills.getPace() + skills.getTechnique()) / 6.0, 1.0, 10.0);
            case "defense" -> clamp((skills.getDefender() + skills.getStamina()) / 4.0, 1.0, 10.0);
            case "passing" -> clamp((skills.getPassing() + skills.getPlaymaker()) / 4.0, 1.0, 10.0);
            default -> clamp(player.getRating() / 10.0, 1.0, 10.0);
        };
    }

    private Lineup resolveLineup(Match match, Team team) {
        if (match == null || team == null || team.getId() == null) {
            return null;
        }
        if (match.getHomeTeam() != null && Objects.equals(match.getHomeTeam().getId(), team.getId())) {
            return match.getHomeLineup() != null ? match.getHomeLineup() : findMatchLineup(match.getId(), team.getId());
        }
        if (match.getAwayTeam() != null && Objects.equals(match.getAwayTeam().getId(), team.getId())) {
            return match.getAwayLineup() != null ? match.getAwayLineup() : findMatchLineup(match.getId(), team.getId());
        }
        return findMatchLineup(match.getId(), team.getId());
    }

    private Lineup findMatchLineup(Long matchId, Long teamId) {
        return lineupRepository.findByMatchId(matchId).stream()
                .filter(lineup -> lineup.getTeam() != null && Objects.equals(lineup.getTeam().getId(), teamId))
                .findFirst()
                .orElse(null);
    }

    private TeamPreviewMetrics buildTeamPreviewMetrics(Match match, Team team, Lineup lineup) {
        String formation = normalizeFormation(lineup != null ? lineup.getFormation() : resolveStoredFormation(team));
        String style = resolveStyle(team);
        List<Player> starters = lineup != null && lineup.getStartingPlayers() != null ? lineup.getStartingPlayers() : List.of();
        List<Player> bench = lineup != null && lineup.getSubstitutes() != null ? lineup.getSubstitutes() : List.of();
        List<Player> squad = team != null && team.getId() != null ? playerRepository.findByTeamId(team.getId()) : List.of();
        List<Match> recentMatches = team != null && team.getId() != null
                ? matchRepository.findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(team.getId(), team.getId()).stream().limit(5).toList()
                : List.of();
        List<String> absentees = squad.stream()
                .filter(Player::isInjured)
                .sorted(Comparator.comparingInt(Player::getRating).reversed())
                .map(player -> player.getName() + " (" + safePosition(player.getPosition()) + ")")
                .limit(4)
                .toList();

        return new TeamPreviewMetrics(
                formation,
                style,
                buildFormString(team, recentMatches),
                recentFormPoints(team, recentMatches),
                calculateFormationFitness(formation, starters),
                calculateBenchQuality(bench),
                countPositionMismatches(formation, starters),
                calculateAvailabilityScore(squad),
                absentees,
                squad.stream().filter(player -> !player.isInjured()).count()
        );
    }

    private List<ZoxPlayerRatingDTO> getBenchRatings(Lineup lineup, Match match) {
        if (lineup == null || lineup.getSubstitutes() == null) {
            return List.of();
        }
        return lineup.getSubstitutes().stream()
                .filter(Objects::nonNull)
                .map(player -> calculatePlayerRating(player, match))
                .toList();
    }

    private Map<String, String> buildKeyMatchups(TeamPreviewMetrics homeMetrics, TeamPreviewMetrics awayMetrics) {
        Map<String, String> matchups = new LinkedHashMap<>();
        matchups.put("Shape", homeMetrics.formation() + " vs " + awayMetrics.formation());
        matchups.put("Tempo", homeMetrics.style() + " vs " + awayMetrics.style());
        matchups.put("Bench", String.format(Locale.US, "%.1f vs %.1f", homeMetrics.benchQuality(), awayMetrics.benchQuality()));
        return matchups;
    }

    private List<String> buildPredictionReasons(ScheduleInsightService.FixtureInsights insights,
                                                ZoxMatchPredictionDTO prediction,
                                                TeamPreviewMetrics homeMetrics,
                                                TeamPreviewMetrics awayMetrics) {
        List<String> reasons = new ArrayList<>();
        if (insights.homeTeamStrength() > insights.awayTeamStrength()) {
            reasons.add("Home side brings the stronger core rating edge.");
        } else if (insights.awayTeamStrength() > insights.homeTeamStrength()) {
            reasons.add("Away side has the better raw squad strength on paper.");
        }

        if (homeMetrics.formationFitness() - awayMetrics.formationFitness() > 0.08) {
            reasons.add("Home XI fits its current shape more naturally.");
        } else if (awayMetrics.formationFitness() - homeMetrics.formationFitness() > 0.08) {
            reasons.add("Away XI has the cleaner positional fit.");
        }

        if (homeMetrics.positionMismatches() != awayMetrics.positionMismatches()) {
            reasons.add((homeMetrics.positionMismatches() < awayMetrics.positionMismatches() ? "Home" : "Away")
                    + " team has fewer positional compromises.");
        }

        if (Math.abs(homeMetrics.benchQuality() - awayMetrics.benchQuality()) > 0.45) {
            reasons.add((homeMetrics.benchQuality() > awayMetrics.benchQuality() ? "Home" : "Away")
                    + " bench depth looks stronger for late-match adjustments.");
        }

        if (Math.abs(homeMetrics.availabilityScore() - awayMetrics.availabilityScore()) > 6.0) {
            reasons.add((homeMetrics.availabilityScore() > awayMetrics.availabilityScore() ? "Home" : "Away")
                    + " side arrives with fewer availability concerns.");
        }

        reasons.add("Projected xG sits at "
                + String.format(Locale.US, "%.2f - %.2f", prediction.getExpectedHomeGoals(), prediction.getExpectedAwayGoals()) + ".");
        return reasons.stream().distinct().limit(5).toList();
    }

    private List<ZoxInsightItemDTO> buildPreviewInsights(TeamPreviewMetrics metrics, boolean home) {
        return List.of(
                ZoxInsightItemDTO.builder().label("Style").value(metrics.style()).tone("neutral").build(),
                ZoxInsightItemDTO.builder().label("Form fit").value(percent(metrics.formationFitness())).tone(metrics.formationFitness() >= 0.72 ? "good" : "warn").build(),
                ZoxInsightItemDTO.builder().label("Bench").value(String.format(Locale.US, "%.1f", metrics.benchQuality())).tone(metrics.benchQuality() >= 6.6 ? "good" : "neutral").build(),
                ZoxInsightItemDTO.builder().label("Absences").value(metrics.absentees().isEmpty() ? "Full squad" : metrics.absentees().size() + " missing").tone(metrics.absentees().isEmpty() ? "good" : "warn").build(),
                ZoxInsightItemDTO.builder().label(home ? "Home readiness" : "Away readiness").value(percent(metrics.availabilityScore() / 100.0)).tone(metrics.availabilityScore() >= 85 ? "good" : "neutral").build()
        );
    }

    private String buildAnalysisText(ZoxMatchPredictionDTO prediction, TeamPreviewMetrics homeMetrics, TeamPreviewMetrics awayMetrics) {
        return String.format(
                Locale.US,
                "%s. Shape fit %s vs %s, bench %.1f vs %.1f, readiness %.0f%% vs %.0f%%.",
                safeMostLikely(prediction.getMostLikelyResult()),
                percent(homeMetrics.formationFitness()),
                percent(awayMetrics.formationFitness()),
                homeMetrics.benchQuality(),
                awayMetrics.benchQuality(),
                homeMetrics.availabilityScore(),
                awayMetrics.availabilityScore()
        );
    }

    private ZoxMatchStatsDTO buildDetailedStats(Match match) {
        Map<String, Object> baseStats = matchAnalyticsService.generateStats(match);
        List<MatchEvent> events = matchEventRepository.findByMatch(match);

        int homeShotsOn = number(baseStats.get("homeShotsOnTarget"));
        int awayShotsOn = number(baseStats.get("awayShotsOnTarget"));
        int homeShotsOff = number(baseStats.get("homeShotsOffTarget"));
        int awayShotsOff = number(baseStats.get("awayShotsOffTarget"));
        int homeGoals = match.getHomeGoals();
        int awayGoals = match.getAwayGoals();

        int homeTotalShots = homeShotsOn + homeShotsOff + homeGoals;
        int awayTotalShots = awayShotsOn + awayShotsOff + awayGoals;
        double homePasses = countTeamEvents(events, match.getHomeTeam(), PassEvent.class);
        double awayPasses = countTeamEvents(events, match.getAwayTeam(), PassEvent.class);
        int homeInterceptions = (int) countTeamEvents(events, match.getHomeTeam(), InterceptionEvent.class);
        int awayInterceptions = (int) countTeamEvents(events, match.getAwayTeam(), InterceptionEvent.class);
        int homeCorners = (int) countTeamEvents(events, match.getHomeTeam(), CornerEvent.class);
        int awayCorners = (int) countTeamEvents(events, match.getAwayTeam(), CornerEvent.class);
        int homeOffsides = (int) countTeamEvents(events, match.getHomeTeam(), OffsideEvent.class);
        int awayOffsides = (int) countTeamEvents(events, match.getAwayTeam(), OffsideEvent.class);
        double homeXg = sumXg(match, match.getHomeTeam());
        double awayXg = sumXg(match, match.getAwayTeam());

        int homePassCompleted = (int) Math.round(homePasses * 0.82);
        int awayPassCompleted = (int) Math.round(awayPasses * 0.82);
        double homePassAccuracy = homePasses > 0 ? (homePassCompleted / homePasses) * 100.0 : 0.0;
        double awayPassAccuracy = awayPasses > 0 ? (awayPassCompleted / awayPasses) * 100.0 : 0.0;

        double homePoss = match.getPossessionHome() > 0 ? match.getPossessionHome() : decimal(baseStats.get("homePossession"), 50.0);
        double awayPoss = match.getPossessionAway() > 0 ? match.getPossessionAway() : decimal(baseStats.get("awayPossession"), 50.0);
        if (homePoss <= 0 && awayPoss <= 0) {
            homePoss = 50.0;
            awayPoss = 50.0;
        }

        return ZoxMatchStatsDTO.builder()
                .matchId(match.getId())
                .homeGoals(homeGoals)
                .awayGoals(awayGoals)
                .result(resolveResult(homeGoals, awayGoals))
                .homeShotsOnTarget(homeShotsOn + homeGoals)
                .awayShotsOnTarget(awayShotsOn + awayGoals)
                .homeShotsOffTarget(homeShotsOff)
                .awayShotsOffTarget(awayShotsOff)
                .homeExpectedGoals(round2(homeXg))
                .awayExpectedGoals(round2(awayXg))
                .homePossession(round1(homePoss))
                .awayPossession(round1(awayPoss))
                .homePassesCompleted(homePassCompleted)
                .homeTotalPasses((int) Math.round(homePasses))
                .awayPassesCompleted(awayPassCompleted)
                .awayTotalPasses((int) Math.round(awayPasses))
                .homePassAccuracy(round1(homePassAccuracy))
                .awayPassAccuracy(round1(awayPassAccuracy))
                .homeTackles(sumStat(match, match.getHomeTeam(), MatchPlayerStats::getInterceptions))
                .awayTackles(sumStat(match, match.getAwayTeam(), MatchPlayerStats::getInterceptions))
                .homeInterceptions(homeInterceptions)
                .awayInterceptions(awayInterceptions)
                .homeClearances(Math.max(0, homeInterceptions + (awayTotalShots / 2)))
                .awayClearances(Math.max(0, awayInterceptions + (homeTotalShots / 2)))
                .homeYellowCards(number(baseStats.get("homeYellowCards")))
                .awayYellowCards(number(baseStats.get("awayYellowCards")))
                .homeRedCards(number(baseStats.get("homeRedCards")))
                .awayRedCards(number(baseStats.get("awayRedCards")))
                .homeFouls(number(baseStats.get("homeYellowCards")) + homeOffsides + number(baseStats.get("homeFreeKicks")))
                .awayFouls(number(baseStats.get("awayYellowCards")) + awayOffsides + number(baseStats.get("awayFreeKicks")))
                .homeOffsides(homeOffsides)
                .awayOffsides(awayOffsides)
                .homeCorners(homeCorners)
                .awayCorners(awayCorners)
                .homeFreeKicks(number(baseStats.get("homeFreeKicks")))
                .awayFreeKicks(number(baseStats.get("awayFreeKicks")))
                .homeDominance(round1(homePoss * 0.55 + homeXg * 18.0))
                .awayDominance(round1(awayPoss * 0.55 + awayXg * 18.0))
                .build();
    }

    private Optional<ZoxTopPerformerDTO> selectManOfTheMatch(List<MatchPlayerStats> playerStats) {
        return playerStats.stream()
                .filter(stats -> stats.getPlayer() != null)
                .max(Comparator.comparingInt(MatchPlayerStats::getRating)
                        .thenComparingInt(MatchPlayerStats::getGoals)
                        .thenComparingInt(MatchPlayerStats::getAssists)
                        .thenComparingInt(MatchPlayerStats::getSaves)
                        .thenComparingInt(MatchPlayerStats::getInterceptions))
                .map(this::toTopPerformer);
    }

    private List<ZoxTopPerformerDTO> topPerformersForTeam(List<MatchPlayerStats> stats, Team team, int limit) {
        if (team == null || team.getId() == null) {
            return List.of();
        }
        return stats.stream()
                .filter(item -> item.getPlayer() != null && item.getPlayer().getTeam() != null)
                .filter(item -> Objects.equals(item.getPlayer().getTeam().getId(), team.getId()))
                .sorted(Comparator.comparingInt(MatchPlayerStats::getRating).reversed()
                        .thenComparingInt(MatchPlayerStats::getGoals).reversed()
                        .thenComparingInt(MatchPlayerStats::getAssists).reversed())
                .limit(limit)
                .map(this::toTopPerformer)
                .toList();
    }

    private ZoxTopPerformerDTO toTopPerformer(MatchPlayerStats stats) {
        Player player = stats.getPlayer();
        Team team = player != null ? player.getTeam() : null;
        double rating10 = round1(stats.getRating() / 10.0);
        List<String> facts = new ArrayList<>();
        if (stats.getGoals() > 0) facts.add(stats.getGoals() + " goal");
        if (stats.getAssists() > 0) facts.add(stats.getAssists() + " assist");
        if (stats.getSaves() > 0) facts.add(stats.getSaves() + " saves");
        if (stats.getInterceptions() > 0) facts.add(stats.getInterceptions() + " interceptions");
        if (stats.isCleanSheet()) facts.add("clean sheet");
        if (facts.isEmpty()) facts.add(stats.getMinutesPlayed() + " minutes");

        return ZoxTopPerformerDTO.builder()
                .playerId(player != null ? player.getId() : null)
                .playerName(player != null ? player.getName() : "Unknown")
                .teamId(team != null ? team.getId() : null)
                .teamName(team != null ? team.getName() : "Unknown")
                .position(safePosition(player != null ? player.getPosition() : null))
                .rating10(rating10)
                .goals(stats.getGoals())
                .assists(stats.getAssists())
                .minutesPlayed(stats.getMinutesPlayed())
                .saves(stats.getSaves())
                .interceptions(stats.getInterceptions())
                .cleanSheet(stats.isCleanSheet())
                .summary(String.join(" · ", facts))
                .build();
    }

    private List<ZoxTimelineEventDTO> buildTimeline(Match match) {
        return matchEventRepository.findByMatch(match).stream()
                .sorted(Comparator.comparingInt(MatchEvent::getMinute).thenComparing(MatchEvent::getId, Comparator.nullsLast(Long::compareTo)))
                .map(this::toTimelineEvent)
                .filter(Objects::nonNull)
                .limit(14)
                .toList();
    }

    private ZoxTimelineEventDTO toTimelineEvent(MatchEvent event) {
        if (event instanceof GoalEvent goalEvent) {
            return ZoxTimelineEventDTO.builder()
                    .minute(goalEvent.getMinute())
                    .type("GOAL")
                    .icon("⚽")
                    .teamName(goalEvent.getTeam() != null ? goalEvent.getTeam().getName() : "Unknown")
                    .title(safe(goalEvent.getScorer() != null ? goalEvent.getScorer().getName() : "Goal"))
                    .detail(goalEvent.getScoreAfterGoal() != null ? goalEvent.getScoreAfterGoal() : "Goal scored")
                    .build();
        }
        if (event instanceof SubstitutionEvent substitutionEvent) {
            return ZoxTimelineEventDTO.builder()
                    .minute(substitutionEvent.getMinute())
                    .type("SUB")
                    .icon("⇄")
                    .teamName(substitutionEvent.getTeam() != null ? substitutionEvent.getTeam().getName() : "Unknown")
                    .title(safe(substitutionEvent.getPlayerOut() != null ? substitutionEvent.getPlayerOut().getName() : "Substitution"))
                    .detail("Off · " + safe(substitutionEvent.getPlayerIn() != null ? substitutionEvent.getPlayerIn().getName() : "Unknown") + " on")
                    .build();
        }
        if (event instanceof YellowCardEvent yellowCardEvent) {
            return ZoxTimelineEventDTO.builder()
                    .minute(yellowCardEvent.getMinute())
                    .type("YC")
                    .icon("🟨")
                    .teamName(yellowCardEvent.getTeam() != null ? yellowCardEvent.getTeam().getName() : "Unknown")
                    .title(safe(yellowCardEvent.getPlayer() != null ? yellowCardEvent.getPlayer().getName() : "Yellow card"))
                    .detail("Booked")
                    .build();
        }
        if (event instanceof RedCardEvent redCardEvent) {
            return ZoxTimelineEventDTO.builder()
                    .minute(redCardEvent.getMinute())
                    .type("RC")
                    .icon("🟥")
                    .teamName(redCardEvent.getTeam() != null ? redCardEvent.getTeam().getName() : "Unknown")
                    .title(safe(redCardEvent.getPlayer() != null ? redCardEvent.getPlayer().getName() : "Red card"))
                    .detail("Sent off")
                    .build();
        }
        if (event instanceof InjuryEvent injuryEvent) {
            return ZoxTimelineEventDTO.builder()
                    .minute(injuryEvent.getMinute())
                    .type("INJ")
                    .icon("✚")
                    .teamName(injuryEvent.getPlayer() != null && injuryEvent.getPlayer().getTeam() != null ? injuryEvent.getPlayer().getTeam().getName() : "Unknown")
                    .title(safe(injuryEvent.getPlayer() != null ? injuryEvent.getPlayer().getName() : "Injury"))
                    .detail("Forced treatment")
                    .build();
        }
        if (event instanceof VARReviewEvent varReviewEvent) {
            return ZoxTimelineEventDTO.builder()
                    .minute(varReviewEvent.getMinute())
                    .type("VAR")
                    .icon("📺")
                    .teamName(resolveVarTeam(varReviewEvent))
                    .title("VAR review")
                    .detail(safe(varReviewEvent.getDecision()) + (varReviewEvent.getOverturnReason() != null ? " · " + safe(varReviewEvent.getOverturnReason()) : ""))
                    .build();
        }
        return null;
    }

    private String buildReportHeadline(Match match) {
        String home = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
        String away = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";
        if (match.getHomeGoals() == match.getAwayGoals()) {
            return home + " and " + away + " finished level at " + match.getHomeGoals() + "-" + match.getAwayGoals() + ".";
        }
        String winner = match.getHomeGoals() > match.getAwayGoals() ? home : away;
        return winner + " came out on top in a " + home + " " + match.getHomeGoals() + "-" + match.getAwayGoals() + " " + away + " result.";
    }

    private String buildReportSummary(Match match, ZoxMatchStatsDTO stats) {
        return String.format(
                Locale.US,
                "%s had %.0f%% possession and %.2f xG, while %s produced %.0f%% possession and %.2f xG. Shots finished %d-%d and corners %d-%d.",
                match.getHomeTeam().getName(),
                stats.getHomePossession(),
                stats.getHomeExpectedGoals(),
                match.getAwayTeam().getName(),
                stats.getAwayPossession(),
                stats.getAwayExpectedGoals(),
                stats.getHomeShotsOnTarget() + stats.getHomeShotsOffTarget(),
                stats.getAwayShotsOnTarget() + stats.getAwayShotsOffTarget(),
                stats.getHomeCorners(),
                stats.getAwayCorners()
        );
    }

    private String resolveTurningPoint(List<ZoxTimelineEventDTO> timeline) {
        return timeline.stream()
                .filter(event -> List.of("GOAL", "RC", "VAR").contains(event.getType()))
                .findFirst()
                .map(event -> event.getMinute() + "' " + event.getTitle() + " shifted the tone of the match.")
                .orElse("No single flashpoint stood above the general flow of the match.");
    }

    private String buildTacticalVerdict(ZoxMatchStatsDTO stats) {
        if (stats.getHomeExpectedGoals() - stats.getAwayExpectedGoals() > 0.6) {
            return "Home side created the cleaner chances and carried the stronger territorial threat.";
        }
        if (stats.getAwayExpectedGoals() - stats.getHomeExpectedGoals() > 0.6) {
            return "Away side built the better chance profile despite the game state swings.";
        }
        if (Math.abs(stats.getHomePossession() - stats.getAwayPossession()) > 14.0) {
            return "The ball share was clearly one-sided, but the chance quality stayed more balanced than the possession split.";
        }
        return "The match stayed tactically balanced, with execution in key moments making the difference.";
    }

    private String buildFormString(Team team, List<Match> recentMatches) {
        if (team == null || team.getId() == null || recentMatches.isEmpty()) {
            return "N/A";
        }
        return recentMatches.stream()
                .map(match -> {
                    int goalDiff = goalDiff(team.getId(), match);
                    return goalDiff > 0 ? "W" : goalDiff < 0 ? "L" : "D";
                })
                .collect(Collectors.joining());
    }

    private int recentFormPoints(Team team, List<Match> recentMatches) {
        if (team == null || team.getId() == null) {
            return 0;
        }
        return recentMatches.stream()
                .mapToInt(match -> {
                    int goalDiff = goalDiff(team.getId(), match);
                    return goalDiff > 0 ? 3 : goalDiff == 0 ? 1 : 0;
                })
                .sum();
    }

    private int goalDiff(Long teamId, Match match) {
        if (match.getHomeTeam() != null && Objects.equals(match.getHomeTeam().getId(), teamId)) {
            return match.getHomeGoals() - match.getAwayGoals();
        }
        return match.getAwayGoals() - match.getHomeGoals();
    }

    private String resolveStoredFormation(Team team) {
        if (team == null || team.getId() == null) {
            return "4-3-3";
        }
        return teamTacticsProfileRepository.findByTeamId(team.getId())
                .map(TeamTacticsProfile::getFormation)
                .filter(value -> value != null && !value.isBlank())
                .orElse("4-3-3");
    }

    private String resolveStyle(Team team) {
        if (team == null || team.getId() == null) {
            return "BALANCED";
        }
        return teamTacticsProfileRepository.findByTeamId(team.getId())
                .map(TeamTacticsProfile::getStyle)
                .filter(value -> value != null && !value.isBlank())
                .orElse("BALANCED");
    }

    private String normalizeFormation(String formation) {
        return formation == null || formation.isBlank() ? "4-3-3" : formation;
    }

    private double calculateFormationFitness(String formation, List<Player> starters) {
        if (starters == null || starters.isEmpty()) {
            return 0.5;
        }
        int mismatches = countPositionMismatches(formation, starters);
        double avg = starters.stream().mapToDouble(this::getPlayerBaseRating).average().orElse(6.0);
        return clamp((avg / 10.0) - mismatches * 0.04, 0.45, 0.95);
    }

    private int countPositionMismatches(String formation, List<Player> starters) {
        if (starters == null || starters.isEmpty()) {
            return 0;
        }
        int[] counts = parseFormation(formation);
        int requiredDefs = counts[0];
        int requiredMids = counts[1];
        int requiredAtts = counts[2];

        long gk = starters.stream().filter(player -> player.getPosition() == Position.GK).count();
        long def = starters.stream().filter(player -> player.getPosition() == Position.DEF).count();
        long mid = starters.stream().filter(player -> player.getPosition() == Position.MID || player.getPosition() == Position.WNG).count();
        long att = starters.stream().filter(player -> player.getPosition() == Position.ATT || player.getPosition() == Position.WNG).count();

        int mismatches = 0;
        mismatches += Math.max(0, 1 - (int) gk);
        mismatches += Math.max(0, requiredDefs - (int) def);
        mismatches += Math.max(0, requiredMids - (int) mid);
        mismatches += Math.max(0, requiredAtts - (int) att);
        return mismatches;
    }

    private int[] parseFormation(String formation) {
        String[] parts = normalizeFormation(formation).split("-");
        int def = parts.length > 0 ? parseInt(parts[0], 4) : 4;
        int mid = parts.length > 1 ? parseInt(parts[1], 3) : 3;
        int att = parts.length > 2 ? parseInt(parts[2], 3) : 3;
        return new int[]{def, mid, att};
    }

    private double calculateBenchQuality(List<Player> bench) {
        if (bench == null || bench.isEmpty()) {
            return 5.5;
        }
        return round1(bench.stream().mapToDouble(this::getPlayerBaseRating).average().orElse(5.5));
    }

    private double calculateAvailabilityScore(List<Player> squad) {
        if (squad == null || squad.isEmpty()) {
            return 75.0;
        }
        long unavailable = squad.stream().filter(Player::isInjured).count();
        return clamp(100.0 - unavailable * 7.0, 60.0, 100.0);
    }

    private long countTeamEvents(Collection<MatchEvent> events, Team team, Class<? extends MatchEvent> type) {
        return events.stream()
                .filter(type::isInstance)
                .filter(event -> team != null && team.equals(resolveEventTeam(event)))
                .count();
    }

    private Team resolveEventTeam(MatchEvent event) {
        if (event instanceof GoalEvent goalEvent) return goalEvent.getTeam();
        if (event instanceof ShotOnTargetEvent shotOnTargetEvent) return shotOnTargetEvent.getTeam();
        if (event instanceof ShotOffTargetEvent shotOffTargetEvent) return shotOffTargetEvent.getTeam();
        if (event instanceof YellowCardEvent yellowCardEvent) return yellowCardEvent.getTeam();
        if (event instanceof RedCardEvent redCardEvent) return redCardEvent.getTeam();
        if (event instanceof CornerEvent cornerEvent) return cornerEvent.getTeam();
        if (event instanceof OffsideEvent offsideEvent) return offsideEvent.getPlayer() != null ? offsideEvent.getPlayer().getTeam() : null;
        if (event instanceof SubstitutionEvent substitutionEvent) return substitutionEvent.getTeam();
        if (event instanceof PassEvent passEvent) return passEvent.getTeam();
        if (event instanceof InterceptionEvent interceptionEvent) return interceptionEvent.getTeam();
        return null;
    }

    private String resolveVarTeam(VARReviewEvent event) {
        if (event.getReviewedGoalEvent() != null && event.getReviewedGoalEvent().getTeam() != null) {
            return event.getReviewedGoalEvent().getTeam().getName();
        }
        if (event.getReviewedPenaltyEvent() != null && event.getReviewedPenaltyEvent().getTeam() != null) {
            return event.getReviewedPenaltyEvent().getTeam().getName();
        }
        if (event.getReviewedOffsideEvent() != null && event.getReviewedOffsideEvent().getPlayer() != null && event.getReviewedOffsideEvent().getPlayer().getTeam() != null) {
            return event.getReviewedOffsideEvent().getPlayer().getTeam().getName();
        }
        return "Unknown";
    }

    private double sumXg(Match match, Team team) {
        double goals = match.getGoals().stream()
                .filter(goal -> team.equals(goal.getTeam()) && goal.isScored())
                .mapToDouble(GoalEvent::getXG)
                .sum();
        double shotsOn = match.getShotsOnTarget().stream()
                .filter(shot -> team.equals(shot.getTeam()))
                .mapToDouble(ShotOnTargetEvent::getXG)
                .sum();
        double shotsOff = match.getShotsOffTarget().stream()
                .filter(shot -> team.equals(shot.getTeam()))
                .mapToDouble(ShotOffTargetEvent::getXG)
                .sum();
        return goals + shotsOn + shotsOff;
    }

    private int sumStat(Match match, Team team, java.util.function.ToIntFunction<MatchPlayerStats> mapper) {
        return matchPlayerStatsRepository.findByMatchId(match.getId()).stream()
                .filter(stats -> stats.getPlayer() != null && stats.getPlayer().getTeam() != null)
                .filter(stats -> Objects.equals(stats.getPlayer().getTeam().getId(), team.getId()))
                .mapToInt(mapper)
                .sum();
    }

    private ZoxFormationDTO.ZoxPlayerPositionDTO calculatePlayerPosition(Player player, int index) {
        String position = player.getPosition() != null ? player.getPosition().toString() : "MID";
        double x = 50.0;
        double y = 50.0;
        switch (position.toUpperCase(Locale.ROOT)) {
            case "GK" -> { x = 8.0; y = 50.0; }
            case "DEF" -> { x = 28.0; y = 20.0 + (index % 4) * 18.0; }
            case "MID" -> { x = 52.0; y = 18.0 + (index % 4) * 18.0; }
            case "WNG" -> { x = 60.0; y = index % 2 == 0 ? 18.0 : 82.0; }
            case "ATT" -> { x = 82.0; y = 32.0 + (index % 2) * 36.0; }
        }

        return ZoxFormationDTO.ZoxPlayerPositionDTO.builder()
                .playerId(player.getId())
                .playerName(player.getName())
                .position(position)
                .x(x)
                .y(y)
                .rating((int) Math.round(getPlayerBaseRating(player)))
                .number(player.getSquadNumber())
                .build();
    }

    private String resolveResult(int homeGoals, int awayGoals) {
        if (homeGoals > awayGoals) return "HOME_WIN";
        if (awayGoals > homeGoals) return "AWAY_WIN";
        return "DRAW";
    }

    private String safeMostLikely(String result) {
        return switch (safe(result)) {
            case "HOME_WIN" -> "Home edge";
            case "AWAY_WIN" -> "Away edge";
            default -> "Balanced matchup";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safePosition(Position position) {
        return position == null ? "N/A" : position.name();
    }

    private String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Match loadMatch(Long matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
    }

    private Match loadMatchWithTeams(Long matchId) {
        return matchRepository.findWithTeamsById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
    }

    private Match loadMatchDetailed(Long matchId) {
        Match match = matchRepository.findWithTeamsAndLineupsById(matchId)
                .orElseGet(() -> loadMatchWithTeams(matchId));

        // Avoid Hibernate multiple-bag fetch issues by initializing lineup player lists
        // via separate collection loads instead of a single entity graph fetch.
        if (match.getId() != null) {
            lineupRepository.findByMatchId(match.getId()).forEach(lineup -> {
                if (lineup.getStartingPlayers() != null) {
                    lineup.getStartingPlayers().size();
                }
                if (lineup.getSubstitutes() != null) {
                    lineup.getSubstitutes().size();
                }
            });
        }

        return match;
    }

    private record TeamPreviewMetrics(
            String formation,
            String style,
            String recentForm,
            Integer formPoints,
            Double formationFitness,
            Double benchQuality,
            Integer positionMismatches,
            Double availabilityScore,
            List<String> absentees,
            long availableCount
    ) {}
}
