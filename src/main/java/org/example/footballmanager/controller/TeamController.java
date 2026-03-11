package org.example.footballmanager.controller;

import org.example.footballmanager.dto.LeagueMilestonesDTO;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.dto.TacticsEditorDTO;
import org.example.footballmanager.dto.TacticsEditorSaveRequest;
import org.example.footballmanager.dto.TeamMedicalOverviewDTO;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchFixture;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.CompetitionEntryRepository;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.LeagueMilestoneService;
import org.example.footballmanager.service.ScheduleInsightService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.TeamMedicalService;
import org.example.footballmanager.service.TeamTacticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/teams")

public class TeamController {

    private static final Set<String> ALLOWED_STYLES = Set.of(
            "BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"
    );

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final LineupRepository lineupRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final LeagueMilestoneService leagueMilestoneService;
    private final ScheduleInsightService scheduleInsightService;
    private final SeasonService seasonService;
    private final TeamMedicalService teamMedicalService;
    private final TeamTacticsService teamTacticsService;

    public TeamController(TeamRepository teamRepository,
                          PlayerRepository playerRepository,
                          MatchRepository matchRepository,
                          MatchFixtureRepository matchFixtureRepository,
                          LineupRepository lineupRepository,
                          MatchPlayerStatsRepository matchPlayerStatsRepository,
                          CompetitionEntryRepository competitionEntryRepository,
                          LeagueMilestoneService leagueMilestoneService,
                          ScheduleInsightService scheduleInsightService,
                          SeasonService seasonService,
                          TeamMedicalService teamMedicalService,
                          TeamTacticsService teamTacticsService) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.lineupRepository = lineupRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.leagueMilestoneService = leagueMilestoneService;
        this.scheduleInsightService = scheduleInsightService;
        this.seasonService = seasonService;
        this.teamMedicalService = teamMedicalService;
        this.teamTacticsService = teamTacticsService;
    }

    @GetMapping
    public List<Team> getAllTeams() {
        return teamRepository.findAll();
    }

    @PostMapping("/create")
    public Team createTeam(@RequestBody Team team) {
        return teamRepository.save(team);
    }

    // Lista igrača
    @GetMapping("/{teamId}/players")
    public ResponseEntity<List<PlayerDTO>> getPlayers(@PathVariable Long teamId) {
        List<Player> teamPlayers = playerRepository.findByTeamId(teamId);
        Map<Long, List<MatchPlayerStats>> statsByPlayerId = teamPlayers.isEmpty()
                ? Map.of()
                : matchPlayerStatsRepository.findByPlayerIdIn(teamPlayers.stream().map(Player::getId).toList())
                .stream()
                .filter(stats -> stats.getPlayer() != null)
                .collect(Collectors.groupingBy(stats -> stats.getPlayer().getId()));

        List<PlayerDTO> players = teamPlayers
                .stream()
                .map(player -> toPlayerDto(player, statsByPlayerId.get(player.getId())))
                .toList();
        return ResponseEntity.ok(players);
    }

    // Detalji jednog igrača
    @GetMapping("/{teamId}/players/{playerId}")
    public ResponseEntity<PlayerDTO> getPlayer(@PathVariable Long teamId, @PathVariable Long playerId) {
        return playerRepository.findById(playerId)
                .filter(p -> p.getTeam().getId().equals(teamId))
                .map(player -> toPlayerDto(player, matchPlayerStatsRepository.findByPlayerId(player.getId())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private PlayerDTO toPlayerDto(Player player, List<MatchPlayerStats> stats) {
        List<MatchPlayerStats> safeStats = stats == null ? List.of() : stats;
        double averageRating10 = safeStats.stream()
                .mapToInt(MatchPlayerStats::getRating)
                .average()
                .orElse(0.0) / 10.0;
        Double roundedAverageRating10 = safeStats.isEmpty()
                ? null
                : Math.round(averageRating10 * 10.0) / 10.0;
        return PlayerDTO.from(player, safeStats.size(), roundedAverageRating10);
    }

    @GetMapping("/{teamId}/matches")
    public ResponseEntity<List<MatchDTO>> getMatches(@PathVariable Long teamId) {
        List<MatchDTO> matches = matchRepository.findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(teamId, teamId)
                .stream()
                .map(MatchDTO::from)
                .toList();
        return ResponseEntity.ok(matches);
    }

    @GetMapping("/{teamId}/schedule")
    public ResponseEntity<List<Map<String, Object>>> getSchedule(@PathVariable Long teamId,
                                                                 @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        int currentActiveSeasonYear = seasonService.getActiveSeasonYear();
        int activeSeasonYear = seasonYear != null ? seasonYear : currentActiveSeasonYear;
        Competition competition = resolveScheduleCompetition(team, activeSeasonYear);
        List<MatchFixture> fixtures;
        if (competition != null) {
            seasonService.ensureEntriesForSeasonCompetition(competition, activeSeasonYear);
            seasonService.ensureDoubleRoundRobinSchedule(competition, activeSeasonYear);
            if (activeSeasonYear == currentActiveSeasonYear
                    && seasonService.getCurrentWeek() == SeasonService.FRIENDLY_WEEK) {
                seasonService.ensureFriendlyWeekFixtures(competition, activeSeasonYear);
            }
            fixtures = matchFixtureRepository
                    .findTeamScheduleByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(competition.getId(), activeSeasonYear, teamId);
        } else {
            fixtures = matchFixtureRepository
                    .findTeamScheduleBySeasonYearOrderByRoundNumberAscMatchDateAsc(activeSeasonYear, teamId);
        }

        Map<Long, Map<String, Object>> headToHeadByOpponent = buildHeadToHeadByOpponent(teamId);
        Map<Long, ScheduleInsightService.TeamSnapshot> snapshots = scheduleInsightService.buildTeamSnapshots(fixtures.stream()
                .flatMap(fixture -> java.util.stream.Stream.of(fixture.getHomeTeam(), fixture.getAwayTeam()))
                .filter(Objects::nonNull)
                .toList());

        List<Map<String, Object>> schedule = fixtures
                .stream()
                .filter(fixture -> fixture.getHomeTeam() != null && fixture.getAwayTeam() != null)
                .map(fixture -> {
                    Long opponentId = resolveOpponentId(fixture, teamId);
                    return toScheduleRow(teamId, fixture, headToHeadByOpponent.get(opponentId), snapshots);
                })
                .toList();

        return ResponseEntity.ok(schedule);
    }

    private Competition resolveScheduleCompetition(Team team, int seasonYear) {
        if (team == null) {
            return null;
        }
        if (team.getCompetition() != null) {
            return team.getCompetition();
        }

        return Optional.ofNullable(competitionEntryRepository.findByTeam(team))
                .orElse(List.of())
                .stream()
                .map(entry -> entry.getSeasonCompetition())
                .filter(Objects::nonNull)
                .filter(seasonCompetition -> Objects.equals(seasonCompetition.getSeasonYear(), seasonYear))
                .map(seasonCompetition -> seasonCompetition.getCompetition())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @GetMapping("/{teamId}/milestones")
    public ResponseEntity<LeagueMilestonesDTO> getTeamMilestones(@PathVariable Long teamId,
                                                                 @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        return ResponseEntity.ok(leagueMilestoneService.buildTeamMilestones(team, activeSeasonYear));
    }

    @GetMapping("/{teamId}/medical")
    public ResponseEntity<TeamMedicalOverviewDTO> getMedicalOverview(@PathVariable Long teamId) {
        TeamMedicalOverviewDTO overview = teamMedicalService.buildOverview(teamId);
        return overview == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(overview);
    }

    @PostMapping("/{teamId}/medical/recovery/{playerId}")
    public ResponseEntity<TeamMedicalOverviewDTO> applyMedicalRecovery(@PathVariable Long teamId,
                                                                       @PathVariable Long playerId) {
        TeamMedicalOverviewDTO overview = teamMedicalService.applyRecovery(teamId, playerId);
        return overview == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(overview);
    }

    @GetMapping("/{teamId}/lineup-template")
    public ResponseEntity<Map<String, Object>> getLineupTemplate(@PathVariable Long teamId) {
        Lineup template = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElse(null);
        if (template == null) {
            return ResponseEntity.ok(Map.of(
                    "saved", false,
                    "formation", "4-4-2",
                    "style", "BALANCED",
                    "starterIds", List.of(),
                    "benchIds", List.of()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "saved", true,
                "formation", template.getFormation() == null ? "4-4-2" : template.getFormation(),
                "style", normalizeStyle(template.getStyle()),
                "starterIds", template.getOrderedStarterIds(),
                "benchIds", template.getOrderedBenchIds()
        ));
    }

    @PutMapping("/{teamId}/lineup-template")
    public ResponseEntity<Map<String, Object>> saveLineupTemplate(@PathVariable Long teamId,
                                                                  @RequestBody Map<String, Object> payload) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        String formation = Objects.toString(payload.getOrDefault("formation", "4-4-2"), "4-4-2");
        String style = normalizeStyle(payload.get("style"));
        List<Long> starterIds = parseIdList(payload.getOrDefault("starterIds", List.of()), 11);
        List<Long> benchIds = parseIdList(payload.getOrDefault("benchIds", List.of()), 7);

        List<Player> teamPlayers = playerRepository.findByTeamId(teamId);
        Map<Long, Player> byId = teamPlayers.stream()
                .filter(p -> !p.isInjured())
                .collect(java.util.stream.Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        List<Player> starters = starterIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        if (starters.size() < 11) {
            List<Player> finalStarters = starters;
            List<Player> fallback = byId.values().stream()
                    .filter(p -> finalStarters.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                    .sorted((a, b) -> Integer.compare(b.getRating(), a.getRating()))
                    .limit(11 - starters.size())
                    .toList();
            starters = java.util.stream.Stream.concat(starters.stream(), fallback.stream()).toList();
        }

        List<Player> finalStarters1 = starters;
        List<Player> bench = benchIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(p -> finalStarters1.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                .limit(7)
                .toList();
        if (bench.size() < 7) {
            List<Player> finalStarters2 = starters;
            List<Player> finalBench = bench;
            List<Player> fallbackBench = byId.values().stream()
                    .filter(p -> finalStarters2.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                    .filter(p -> finalBench.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                    .sorted((a, b) -> Integer.compare(b.getRating(), a.getRating()))
                    .limit(7 - bench.size())
                    .toList();
            bench = java.util.stream.Stream.concat(bench.stream(), fallbackBench.stream()).toList();
        }

        Lineup lineup = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElseGet(Lineup::new);
        lineup.setTeam(team);
        lineup.setMatch(null);
        lineup.setFormation(formation);
        lineup.setStyle(style);
        lineup.setStartingPlayers(new ArrayList<>(starters));
        lineup.setSubstitutes(new ArrayList<>(bench));
        lineup.setStarterOrderFromIds(new ArrayList<>(starters.stream().map(Player::getId).toList()));
        lineup.setBenchOrderFromIds(new ArrayList<>(bench.stream().map(Player::getId).toList()));
        lineup = lineupRepository.save(lineup);

        return ResponseEntity.ok(Map.of(
                "id", lineup.getId(),
                "saved", true,
                "formation", lineup.getFormation(),
                "style", normalizeStyle(lineup.getStyle()),
                "starterIds", lineup.getOrderedStarterIds(),
                "benchIds", lineup.getOrderedBenchIds()
        ));
    }

    @GetMapping("/{teamId}/tactics-editor")
    public ResponseEntity<TacticsEditorDTO> getTacticsEditor(@PathVariable Long teamId,
                                                             @RequestParam(value = "formation", required = false) String formation) {
        TacticsEditorDTO dto = teamTacticsService.getTacticsEditor(teamId, formation);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    @PutMapping("/{teamId}/tactics-editor")
    public ResponseEntity<TacticsEditorDTO> saveTacticsEditor(@PathVariable Long teamId,
                                                              @RequestBody TacticsEditorSaveRequest request) {
        TacticsEditorDTO dto = teamTacticsService.saveTacticsEditor(teamId, request);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    private String normalizeStyle(Object rawStyle) {
        String style = rawStyle == null ? "BALANCED" : String.valueOf(rawStyle).trim().toUpperCase(Locale.ROOT);
        return ALLOWED_STYLES.contains(style) ? style : "BALANCED";
    }

    private Map<String, Object> toScheduleRow(Long teamId,
                                              MatchFixture fixture,
                                              Map<String, Object> h2hSummary,
                                              Map<Long, ScheduleInsightService.TeamSnapshot> snapshots) {
        Match playedMatch = fixture.getPlayedMatch();
        boolean isHome = Objects.equals(fixture.getHomeTeam().getId(), teamId);
        Team opponent = isHome ? fixture.getAwayTeam() : fixture.getHomeTeam();
        ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(
                fixture.getHomeTeam(),
                fixture.getAwayTeam(),
                snapshots
        );

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fixtureId", fixture.getId());
        row.put("id", playedMatch != null ? playedMatch.getId() : null);
        row.put("homeTeamId", fixture.getHomeTeam().getId());
        row.put("awayTeamId", fixture.getAwayTeam().getId());
        row.put("homeTeam", fixture.getHomeTeam().getName());
        row.put("awayTeam", fixture.getAwayTeam().getName());
        row.put("opponentId", opponent != null ? opponent.getId() : null);
        row.put("opponentName", opponent != null ? opponent.getName() : "Unknown");
        row.put("isHome", isHome);
        row.put("homeGoals", playedMatch != null ? playedMatch.getHomeGoals() : 0);
        row.put("awayGoals", playedMatch != null ? playedMatch.getAwayGoals() : 0);
        row.put("played", fixture.isPlayed());
        row.put("round", fixture.getRoundNumber() != null ? fixture.getRoundNumber() : 1);
        row.put("week", fixture.getWeekNumber() != null ? fixture.getWeekNumber() : fixture.getRoundNumber());
        row.put("seasonYear", fixture.getSeasonYear());
        row.put("competitionName", fixture.getCompetition() != null ? fixture.getCompetition().getName() : "Competition");
        row.put("matchDate", formatDateTime(fixture.getMatchDate()));
        row.put("stadium", resolveStadiumName(fixture));
        row.put("homeTeamStrength", insights.homeTeamStrength());
        row.put("awayTeamStrength", insights.awayTeamStrength());
        row.put("homeTeamForm", insights.homeTeamForm());
        row.put("awayTeamForm", insights.awayTeamForm());
        row.put("prediction", toPredictionMap(insights.prediction()));
        row.put("h2h", h2hSummary != null ? h2hSummary : emptyHeadToHeadSummary());
        return row;
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

    private Map<Long, Map<String, Object>> buildHeadToHeadByOpponent(Long teamId) {
        return matchRepository.findByHomeTeamIdOrAwayTeamId(teamId, teamId).stream()
                .filter(Match::isPlayed)
                .filter(match -> match.getHomeTeam() != null && match.getAwayTeam() != null)
                .collect(Collectors.groupingBy(match -> resolveOpponentId(match, teamId)))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> summarizeHeadToHead(teamId, entry.getValue())));
    }

    private Map<String, Object> summarizeHeadToHead(Long teamId, List<Match> matches) {
        int wins = 0;
        int draws = 0;
        int losses = 0;
        int goalsFor = 0;
        int goalsAgainst = 0;

        List<Match> ordered = matches.stream()
                .sorted((left, right) -> {
                    if (left.getMatchDate() == null && right.getMatchDate() == null) return 0;
                    if (left.getMatchDate() == null) return 1;
                    if (right.getMatchDate() == null) return -1;
                    return right.getMatchDate().compareTo(left.getMatchDate());
                })
                .toList();

        for (Match match : ordered) {
            boolean isHome = Objects.equals(match.getHomeTeam().getId(), teamId);
            int teamGoals = isHome ? match.getHomeGoals() : match.getAwayGoals();
            int opponentGoals = isHome ? match.getAwayGoals() : match.getHomeGoals();
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

        Match lastMeeting = ordered.isEmpty() ? null : ordered.get(0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("played", ordered.size());
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

    private Long resolveOpponentId(Match match, Long teamId) {
        if (match.getHomeTeam() == null || match.getAwayTeam() == null) {
            return null;
        }
        return Objects.equals(match.getHomeTeam().getId(), teamId)
                ? match.getAwayTeam().getId()
                : match.getHomeTeam().getId();
    }

    private Long resolveOpponentId(MatchFixture fixture, Long teamId) {
        if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) {
            return null;
        }
        return Objects.equals(fixture.getHomeTeam().getId(), teamId)
                ? fixture.getAwayTeam().getId()
                : fixture.getHomeTeam().getId();
    }

    private String resolveStadiumName(MatchFixture fixture) {
        if (fixture.getPlayedMatch() != null && fixture.getPlayedMatch().getStadium() != null) {
            return fixture.getPlayedMatch().getStadium().getName();
        }
        if (fixture.getHomeTeam() != null && fixture.getHomeTeam().getStadium() != null) {
            return fixture.getHomeTeam().getStadium().getName();
        }
        return "N/A";
    }

    private String formatDateTime(java.time.LocalDateTime dateTime) {
        return dateTime != null ? dateTime.toString().substring(0, 16).replace("T", " ") : "N/A";
    }

    private List<Long> parseIdList(Object raw, int limit) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(v -> {
                    if (v instanceof Number n) return n.longValue();
                    try {
                        return Long.parseLong(String.valueOf(v));
                    } catch (Exception ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .limit(limit)
                .toList();
    }
}
