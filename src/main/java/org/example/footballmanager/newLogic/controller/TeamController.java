package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.LeagueMilestonesDTO;
import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.dto.TacticsEditorDTO;
import org.example.footballmanager.newLogic.dto.TacticsEditorSaveRequest;
import org.example.footballmanager.newLogic.dto.TeamSummaryDTO;
import org.example.footballmanager.newLogic.dto.TeamMedicalOverviewDTO;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.LeagueMilestoneService;
import org.example.footballmanager.newLogic.service.ScheduleInsightService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.TeamMedicalService;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.example.footballmanager.newLogic.service.TeamTacticsService;
import org.springframework.data.domain.PageRequest;
import org.example.footballmanager.newLogic.util.SortWhitelist;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final org.example.footballmanager.newLogic.service.PlusFeatureService plusFeatures;
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
                          TeamTacticsService teamTacticsService,
                          PlusFeatureService plusFeatures) {
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
        this.plusFeatures = plusFeatures;
    }

    @GetMapping
    public List<TeamSummaryDTO> getAllTeams(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "100") int size,
                                            @RequestParam(defaultValue = "name") String sortBy,
                                            @RequestParam(defaultValue = "asc") String direction) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                Set.of("id", "name", "reputation", "budget"));
        return teamRepository.findAll(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)), sort))
                .getContent()
                .stream()
                .map(TeamSummaryDTO::from)
                .toList();
    }

    /**
     * Creates a club.
     *
     * <p><b>This accepted a raw {@code Team} and saved it.</b> No administrator check, so any logged-in
     * manager could create a club; no validation, so a body carrying an {@code id} would overwrite an existing
     * row through {@code save()}; and the raw entity went back out with every column on it.
     *
     * <p>Now gated like the rest of the privileged surface, validated for a name, and answered with the
     * summary DTO the rest of this controller already returns.
     */
    @PostMapping("/create")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'DEV', 'ADMIN')")
    public ResponseEntity<TeamSummaryDTO> createTeam(@RequestBody TeamSummaryDTO request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("A club needs a name.");
        }
        Team team = new Team();
        team.setName(request.getName().trim());
        return ResponseEntity.ok(TeamSummaryDTO.from(teamRepository.save(team)));
    }

    // Lista igrača
    @GetMapping("/{teamId}/formations")
    public ResponseEntity<List<Map<String, String>>> getFormations(@PathVariable Long teamId) {
        List<Map<String, String>> formations = new ArrayList<>();
        formations.add(Map.of("name", "4-4-2", "description", "Classic two-striker formation with balanced midfield"));
        formations.add(Map.of("name", "4-3-3", "description", "Attacking wingers and central midfield control"));
        formations.add(Map.of("name", "4-2-3-1", "description", "Defensive midfield with attacking midfield trio"));
        formations.add(Map.of("name", "3-5-2", "description", "Three at the back with strong midfield presence"));
        formations.add(Map.of("name", "4-5-1", "description", "Defensive formation with single striker"));
        formations.add(Map.of("name", "3-4-3", "description", "Attacking formation with three forwards"));
        formations.add(Map.of("name", "5-3-2", "description", "Very defensive with five defenders"));
        formations.add(Map.of("name", "4-1-4-1", "description", "Single defensive midfielder"));
        formations.add(Map.of("name", "3-4-2-1", "description", "Three defenders with double number 10"));
        formations.add(Map.of("name", "5-4-1", "description", "Ultra defensive formation"));
        return ResponseEntity.ok(formations);
    }

    @GetMapping("/{teamId}/profile")
    public ResponseEntity<Map<String, Object>> getTeamProfile(@PathVariable Long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", team.getName());
        profile.put("founded", 1954); // TODO: Add founded year to Team entity
        profile.put("stadium", team.getStadium() != null ? team.getStadium().getName() : "N/A");
        profile.put("budget", team.getBudget());
        profile.put("reputation", team.getReputation() > 70 ? "High" : team.getReputation() > 40 ? "Medium" : "Low");

        // Which league this club is in, and under what name, so the profile can show it and link to
        // it. The club already had the competition; the profile simply never told anybody.
        if (team.getCompetition() != null) {
            profile.put("leagueId", team.getCompetition().getId());
            profile.put("leagueName", team.getCompetition().getName());
            profile.put("leagueTier", team.getCompetition().getTier());
        }
        
        // The badge is a column on the club now. It used to be a name.contains("Omladinac") check,
        // which had no room for a second badge and is one of the defects expertAudit.md flags. The
        // default is applied here rather than stored, so a club with no badge is honest about it.
        String logo = (team.getLogoUrl() != null && !team.getLogoUrl().isBlank())
                ? team.getLogoUrl()
                : "/images/default-team.png";
        profile.put("logo", logo);

        return ResponseEntity.ok(profile);
    }

    @GetMapping("/{teamId}/coaches")
    public ResponseEntity<List<Map<String, Object>>> getCoaches(@PathVariable Long teamId) {
        List<Map<String, Object>> coaches = new ArrayList<>();
        coaches.add(Map.of("name", "John Smith", "role", "Head Coach", "rating", 85));
        coaches.add(Map.of("name", "Peter Johnson", "role", "Assistant Coach", "rating", 78));
        coaches.add(Map.of("name", "Alice Brown", "role", "Fitness Coach", "rating", 80));
        return ResponseEntity.ok(coaches);
    }

    @GetMapping("/{teamId}/juniors")
    public ResponseEntity<List<Map<String, Object>>> getJuniors(@PathVariable Long teamId) {
        List<Map<String, Object>> juniors = new ArrayList<>();
        juniors.add(Map.of("name", "John Smith", "rating", 85, "potential", 3.3));
        juniors.add(Map.of("name", "Peter Johnson", "rating", 78, "potential", 4.0));
        juniors.add(Map.of("name", "Alice Brown", "rating", 80, "potential", 3.8));
        return ResponseEntity.ok(juniors);
    }

    @GetMapping("/{teamId}/players")
    public ResponseEntity<List<PlayerDTO>> getPlayers(@PathVariable Long teamId,
                                                     @AuthenticationPrincipal User user) {
        List<Player> teamPlayers = playerRepository.findByTeamId(teamId);
        Map<Long, List<MatchPlayerStats>> statsByPlayerId = teamPlayers.isEmpty()
                ? Map.of()
                : matchPlayerStatsRepository.findByPlayerIdIn(teamPlayers.stream().map(Player::getId).toList())
                .stream()
                .filter(stats -> stats.getPlayer() != null)
                .collect(Collectors.groupingBy(stats -> stats.getPlayer().getId()));

        List<PlayerDTO> players = teamPlayers
                .stream()
                .map(player -> toPlayerDto(player, statsByPlayerId.get(player.getId()), user))
                .toList();
        return ResponseEntity.ok(players);
    }

    // Detalji jednog igrača
    @GetMapping("/{teamId}/players/{playerId}")
    public ResponseEntity<PlayerDTO> getPlayer(@PathVariable Long teamId, @PathVariable Long playerId,
                                              @AuthenticationPrincipal User user) {
        return playerRepository.findById(playerId)
                .filter(p -> p.getTeam().getId().equals(teamId))
                .map(player -> toPlayerDto(player, matchPlayerStatsRepository.findByPlayerId(player.getId()), user))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Builds a player DTO for a specific viewer.
     *
     * <p>The talent is resolved by {@code PlusFeatureService} and passed in, rather than decided here:
     * the DTO has no idea who is looking, and every controller that re-derived the entitlement rule
     * is how four of the service's methods ended up with zero callers.
     */
    private PlayerDTO toPlayerDto(Player player, List<MatchPlayerStats> stats, User viewer) {
        Long viewerTeamId = plusFeatures.viewerTeamId(viewer);
        List<MatchPlayerStats> safeStats = stats == null ? List.of() : stats;
        double averageRating10 = safeStats.stream()
                .mapToInt(MatchPlayerStats::getRating)
                .average()
                .orElse(0.0) / 10.0;
        Double roundedAverageRating10 = safeStats.isEmpty()
                ? null
                : Math.round(averageRating10 * 10.0) / 10.0;
        return PlayerDTO.from(player, safeStats.size(), roundedAverageRating10,
                plusFeatures.talentOrNull(player, viewer, viewerTeamId));
    }


    @GetMapping("/{teamId}/matches")
    public ResponseEntity<List<MatchDTO>> getMatches(@PathVariable Long teamId,
                                                     @AuthenticationPrincipal User user) {
        Long viewerTeamId = user != null && user.getTifoCTeam() != null ? user.getTifoCTeam().getId() : null;
        Long dtoViewerTeamId = Objects.equals(viewerTeamId, teamId) ? viewerTeamId : null;
        List<MatchDTO> matches = matchRepository.findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(teamId, teamId)
                .stream()
                .map(match -> MatchDTO.from(match, dtoViewerTeamId))
                .toList();
        return ResponseEntity.ok(matches);
    }

    @GetMapping("/{teamId}/schedule")
    public ResponseEntity<List<Map<String, Object>>> getSchedule(@PathVariable Long teamId,
                                                                 @RequestParam(value = "seasonYear", required = false) Integer seasonYear,
                                                                 @AuthenticationPrincipal User user) {
        // Who is asking, because this is the surface where a manager is most likely to see his own
        // result by accident: the schedule is the page a manager opens to see what is next.
        Long viewerTeamId = user != null && user.getTifoCTeam() != null ? user.getTifoCTeam().getId() : null;
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        int currentActiveSeasonYear = seasonService.getActiveSeasonYear();
        int activeSeasonYear = seasonYear != null ? seasonYear : currentActiveSeasonYear;
        Competition competition = resolveScheduleCompetition(team, activeSeasonYear);
        List<MatchFixture> fixtures;
        if (competition != null) {
            // Same rule as CountryController's schedule GET, and the same reasoning: this is a read, and
            // generating a double round robin from it meant any manager opening a page wrote thousands of
            // rows. PyramidBuilder does this when the pyramid is built, which is the only place it belongs.
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
                    return toScheduleRow(teamId, fixture, headToHeadByOpponent.get(opponentId), snapshots, viewerTeamId);
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

    /**
     * Puts a player through medical recovery.
     *
     * <p>Unguarded: any manager could heal any player in the world, by id, with no club involved at all. The
     * same controller already refuses a stranger's player on the {@code /players/{playerId}} read through
     * {@code PlusFeatureService}, so the rule existed and this write simply did not use it.
     */
    @PostMapping("/{teamId}/medical/recovery/{playerId}")
    public ResponseEntity<TeamMedicalOverviewDTO> applyMedicalRecovery(@PathVariable Long teamId,
                                                                       @PathVariable Long playerId,
                                                                       @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
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

    /**
     * Builds the ground-side squad sheet for a club.
     *
     * <p><b>This wrote any club's eleven.</b> The team id in the path says <i>which</i> club; nothing said
     * <i>whose</i>. So any logged-in manager could open a league table, pick a rival's id, and set up his
     * team however he liked — and the save is unconditional, so the change survived.
     *
     * <p>{@code isOwnTeam} answers "is this your club" the same way the rest of the game answers it, which is
     * the point of routing it through {@code PlusFeatureService} rather than a local club lookup.
     */
    @PutMapping("/{teamId}/lineup-template")
    public ResponseEntity<Map<String, Object>> saveLineupTemplate(@PathVariable Long teamId,
                                                                  @RequestBody Map<String, Object> payload,
                                                                  @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return notYourClub();
        }
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

    /**
     * Saves the club's tactical grid.
     *
     * <p>Unguarded, like the lineup template above: any manager could rewrite a rival's roles, and this is
     * the grid the match engine now reads — {@code TacticsRulesProvider} loads it and hands it to the
     * orchestrator, so the edit changed how the next match was actually simulated.
     */
    @PutMapping("/{teamId}/tactics-editor")
    public ResponseEntity<TacticsEditorDTO> saveTacticsEditor(@PathVariable Long teamId,
                                                              @RequestBody TacticsEditorSaveRequest request,
                                                              @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        TacticsEditorDTO dto = teamTacticsService.saveTacticsEditor(teamId, request);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    /**
     * Whether this caller may change <b>this</b> club.
     *
     * <p>Fails closed: no principal, no club id, no club and a caller who is neither its manager nor an
     * administrator are all "not yours". The owner is let through, because a fix that locks the owner out of
     * his own game is worse than the hole it closes.
     *
     * <p>Not an annotation. The rule needs the principal <i>and</i> the row — an ownership check is not a role
     * — and {@code @PreAuthorize("...")} cannot be given a path variable to compare against.
     */
    private boolean mayManage(User user, Long teamId) {
        if (user == null || teamId == null) {
            return false;
        }
        if (user.getRole() != null && user.getRole().name().equals("OWNER")) {
            return true;
        }
        return plusFeatures.isOwnTeam(user, teamId);
    }

    private ResponseEntity<Map<String, Object>> notYourClub() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "You can only change your own club."));
    }

    private String normalizeStyle(Object rawStyle) {
        String style = rawStyle == null ? "BALANCED" : String.valueOf(rawStyle).trim().toUpperCase(Locale.ROOT);
        return ALLOWED_STYLES.contains(style) ? style : "BALANCED";
    }

    /** "Season 1 · Day 3 · 18:00", matching MatchDTO so both surfaces read the same. */
    private String buildSeasonDayLabel(MatchFixture fixture) {
        if (fixture.getSeasonYear() == null) {
            return null;
        }
        StringBuilder label = new StringBuilder("Season ").append(fixture.getSeasonYear());
        if (fixture.getDayNumber() != null) {
            label.append(" \u00b7 Day ").append(fixture.getDayNumber());
        }
        if (fixture.getMatchDate() != null) {
            label.append(" \u00b7 ").append(String.format("%02d:%02d",
                    fixture.getMatchDate().getHour(), fixture.getMatchDate().getMinute()));
        }
        return label.toString();
    }

    private Map<String, Object> toScheduleRow(Long teamId,
                                              MatchFixture fixture,
                                              Map<String, Object> h2hSummary,
                                              Map<Long, ScheduleInsightService.TeamSnapshot> snapshots,
                                              Long viewerTeamId) {
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
        // Team.logoUrl was populated for every seeded club and read by nobody: the schedule carried
        // names and ids but no crest, so the frontend had nothing to show for an opponent and fell
        // back to a generic badge on every fixture. Null is legitimate here - most of the 310 clubs
        // have no crest - and the frontend already has a default for it.
        row.put("homeTeamLogoUrl", fixture.getHomeTeam().getLogoUrl());
        row.put("awayTeamLogoUrl", fixture.getAwayTeam().getLogoUrl());
        row.put("opponentId", opponent != null ? opponent.getId() : null);
        row.put("opponentName", opponent != null ? opponent.getName() : "Unknown");
        row.put("isHome", isHome);
        // Whether the manager has asked to see this result. The same rule MatchDTO applies, because a
        // schedule row and a match row are one fact in two shapes and must not disagree: a score that
        // shows on one surface and not the other is worse than one that shows on neither.
        boolean viewerIsInThisMatch = viewerTeamId != null
                && Objects.equals(viewerTeamId, teamId)
                && playedMatch != null;
        boolean resultRevealed = true;
        if (viewerIsInThisMatch) {
            resultRevealed = isHome ? playedMatch.isHomeResultRevealed() : playedMatch.isAwayResultRevealed();
        }
        boolean resultHidden = playedMatch != null && viewerIsInThisMatch && !resultRevealed;

        // A hidden result publishes no score at all rather than a zero. Zero is a real result - it is
        // what a 0-0 reads as - so masking to zero would show a goalless draw that never happened.
        row.put("homeGoals", resultHidden ? null : (playedMatch != null ? playedMatch.getHomeGoals() : 0));
        row.put("awayGoals", resultHidden ? null : (playedMatch != null ? playedMatch.getAwayGoals() : 0));
        row.put("resultHidden", resultHidden);
        row.put("resultRevealed", resultRevealed);
        row.put("played", fixture.isPlayed());
        row.put("round", fixture.getRoundNumber() != null ? fixture.getRoundNumber() : 1);
        row.put("week", fixture.getWeekNumber() != null ? fixture.getWeekNumber() : fixture.getRoundNumber());
        row.put("seasonYear", fixture.getSeasonYear());
        row.put("competitionName", fixture.getCompetition() != null ? fixture.getCompetition().getName() : "Competition");
        // The id as well as the name: the schedule screen needs it to send you to the right league,
        // and a name alone cannot be clicked safely when two divisions can share one.
        row.put("competitionId", fixture.getCompetition() != null ? fixture.getCompetition().getId() : null);
        // League / Cup / Friendly, so a fixture says what kind of match it is without a trip to its
        // league page. Two divisions can share a name; they do not share a type.
        row.put("competitionType", fixture.getCompetition() != null && fixture.getCompetition().getType() != null
                ? fixture.getCompetition().getType().name() : null);
        // The game's own calendar, next to the wall-clock date. The owner asked for both: these are
        // different facts, and only this one is what the season is actually built on.
        row.put("day", fixture.getDayNumber());
        row.put("seasonDayLabel", buildSeasonDayLabel(fixture));
        // Without the replay id a hidden row's "Watch your match" has nothing to open - the fixture id
        // and the replay id are not the same number, and guessing costs the manager the button.
        row.put("replayId", playedMatch != null ? playedMatch.getReplayId() : null);
        row.put("matchDate", formatDateTime(fixture.getMatchDate()));
        row.put("stadium", resolveStadiumName(fixture));
        // The picture comes from the ground's own field rather than from matching its name. The name
        // matcher was a function nothing called, which is why three real ground images were
        // unreachable; a null here falls back to the Dunjareal ground on the client.
        row.put("stadiumImage", fixture.getHomeTeam() != null
                && fixture.getHomeTeam().getStadium() != null
                ? fixture.getHomeTeam().getStadium().getImage()
                : null);
        // Which club's ground this is, so the venue can be a link to that club rather than a
        // dead piece of text.
        row.put("stadiumOwnerTeamId", fixture.getHomeTeam() != null ? fixture.getHomeTeam().getId() : null);
        row.put("stadiumOwnerTeamName", fixture.getHomeTeam() != null ? fixture.getHomeTeam().getName() : null);
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
