package org.tifo.footballmanager.newLogic.controller;

import org.tifo.footballmanager.newLogic.dto.LeagueMilestonesDTO;
import org.tifo.footballmanager.newLogic.dto.MatchDTO;
import org.tifo.footballmanager.newLogic.dto.PlayerDTO;
import org.tifo.footballmanager.newLogic.dto.PublicSquadMemberDTO;
import org.tifo.footballmanager.newLogic.dto.TacticsEditorDTO;
import org.tifo.footballmanager.newLogic.dto.TacticsSlotDTO;
import org.tifo.footballmanager.newLogic.dto.TacticsRuleDTO;
import org.tifo.footballmanager.newLogic.service.TeamTacticsService;
import org.tifo.footballmanager.newLogic.dto.TacticsEditorSaveRequest;
import org.tifo.footballmanager.newLogic.dto.TeamSummaryDTO;
import org.tifo.footballmanager.newLogic.dto.TeamMedicalOverviewDTO;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.MatchType;
import org.tifo.footballmanager.newLogic.model.Lineup;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.MatchPlayerStats;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.commonmanager.model.User;
import org.tifo.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.tifo.footballmanager.newLogic.repository.LineupRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.service.LeagueMilestoneService;
import org.tifo.footballmanager.newLogic.service.ScheduleInsightService;
import org.tifo.footballmanager.newLogic.service.SeasonService;
import org.tifo.footballmanager.newLogic.service.TeamMedicalService;
import org.tifo.footballmanager.newLogic.service.PlusFeatureService;
import org.tifo.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.tifo.footballmanager.newLogic.sim.model.Ball;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.PlayerSkills;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;
import org.tifo.footballmanager.newLogic.service.ClubOwnershipLinker;
import org.tifo.footballmanager.newLogic.service.SquadRegistrationService;
import org.springframework.data.domain.PageRequest;
import org.tifo.footballmanager.newLogic.util.SortWhitelist;
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
    private final org.tifo.footballmanager.newLogic.service.PlusFeatureService plusFeatures;
    private final ScheduleInsightService scheduleInsightService;
    private final SeasonService seasonService;
    private final TeamMedicalService teamMedicalService;
    private final TeamTacticsService teamTacticsService;
    private final org.tifo.footballmanager.newLogic.service.MatchLockService matchLocks;
    private final ClubOwnershipLinker clubOwnership;
    private final SquadRegistrationService squadRegistration;
    private final org.tifo.footballmanager.newLogic.service.TacticsProfileBackupService tacticsBackup;

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
                          org.tifo.footballmanager.newLogic.service.MatchLockService matchLocks,
                          PlusFeatureService plusFeatures,
                          ClubOwnershipLinker clubOwnership,
                          SquadRegistrationService squadRegistration,
                          org.tifo.footballmanager.newLogic.service.TacticsProfileBackupService tacticsBackup) {
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
        this.matchLocks = matchLocks;
        this.plusFeatures = plusFeatures;
        this.clubOwnership = clubOwnership;
        this.squadRegistration = squadRegistration;
        this.tacticsBackup = tacticsBackup;
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
    public ResponseEntity<Map<String, Object>> getTeamProfile(@PathVariable Long teamId,
                                                              @AuthenticationPrincipal User principal) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", team.getName());
        profile.put("founded", team.getFoundedYear() == null ? 1954 : team.getFoundedYear());
        profile.put("stadium", team.getStadium() != null ? team.getStadium().getName() : "N/A");
        profile.put("budget", team.getBudget());
        profile.put("reputation", team.getReputation() > 70 ? "High" : team.getReputation() > 40 ? "Medium" : "Low");

        // Who runs this club. Added in P2-20 because a profile that does not say is a profile you
        // cannot get from here to: the manager is the one thing on a club page a reader actually wants.
        //
        // // Read through User.footballTeam, not by matching a name. The name-join is what this
        // repository paid for with P0-18 and three live siblings (P0-20), and managerOf deliberately
        // refuses to guess when two clubs share a name — a profile naming the wrong manager is worse
        // than one naming none.
        User manager = clubOwnership.managerOf(team);
        if (manager != null) {
            String managerName = manager.getDisplayName();
            profile.put("managerUserId", manager.getId());
            profile.put("managerName", managerName != null && !managerName.isBlank()
                    ? managerName
                    : manager.getUsername());
            // Whether the name above is one he picked or a login address. The page says so, rather
            // than presenting "velibor@example.com" as though it were how he wants to be known.
            profile.put("managerHasChosenName",
                    managerName != null && !managerName.isBlank());
        }

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

        // Owner, 2026-10-14: a club profile that does not say how strong the club is, is a name and a
        // badge. Elo, league points and world position are what a reader actually came for, and they
        // were missing only because nobody had asked for this page for a club they do not run.
        profile.put("elo", team.getEloRating());
        profile.put("eloPrevious", team.getEloPreviousRating());
        profile.put("eloDelta", team.getEloDelta());
        Long worldPosition = teamRepository.findWorldPositionByElo(team.getId());
        profile.put("worldPosition", worldPosition);
        profile.put("humanControlled", Boolean.TRUE.equals(team.isHumanControlled()));

        // Whether the reader may rename this club — the same gate the rename endpoint applies, so the
        // pencil appears exactly where the write would be accepted. Read rather than guessed from
        // humanControlled: an AI club is human-controlled false, but so is a club whose manager has
        // not been linked yet, and the page should not offer a pencil that answers 403.
        profile.put("canRename", mayManage(principal, teamId));

        return ResponseEntity.ok(profile);
    }

    /**
     * Renames a club — the manager's own club, the same way he renames his ground.
     *
     * <p><b>Gated, and gated the same way the stadium's name is.</b> The team id in the path says
     * <i>which</i> club; {@code mayManage} says whether it is the caller's. A rename is a write to
     * another line's season — the league table prints the name — so any manager able to reach this
     * endpoint for any id could rename a rival.
     *
     * <p><b>Sixty characters, because that is the stadium's rule.</b> One field, one button, one
     * validation; a club name with a different limit from the ground's would be two rules for the
     * same act.
     *
     * <p><b>The tactics backup is re-keyed with it.</b> {@code var/tactics-editor-profiles.json} is
     * keyed by club name and the database is keyed by id, so a rename that did not touch the file
     * would leave the club's hand-authored tactics under a name no club has any more — readable by
     * nobody and dropped by the next reset. See
     * {@link org.tifo.footballmanager.newLogic.service.TacticsProfileBackupService#renameClub}.
     */
    @PostMapping("/{teamId}/name")
    public ResponseEntity<Map<String, Object>> renameTeam(@PathVariable Long teamId,
                                                          @RequestParam("name") String name,
                                                          @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "You can only rename your own club."));
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 60) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A club name must be between 1 and 60 characters."));
        }

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        String previous = team.getName();
        team.setName(trimmed);
        teamRepository.save(team);

        if (previous != null && !previous.equals(trimmed)) {
            tacticsBackup.renameClub(previous, trimmed);
        }

        return ResponseEntity.ok(Map.of("name", trimmed));
    }

    /**
     * Kit editor (P0-32 follow-up): change the club's home/away kit colours and pattern.
     */
    @PutMapping("/{teamId}/kit")
    public ResponseEntity<Map<String, Object>> updateKit(@PathVariable Long teamId,
                                                          @RequestBody Map<String, String> body,
                                                          @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, teamId)) {
            return notYourClub();
        }
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }
        String home = body.get("homeKitColor");
        String away = body.get("awayKitColor");
        String pattern = body.get("kitPattern");
        if (home != null && home.matches("#[0-9a-fA-F]{6}")) team.setHomeKitColor(home);
        if (away != null && away.matches("#[0-9a-fA-F]{6}")) team.setAwayKitColor(away);
        if (pattern != null) {
            try { team.setKitPattern(Team.KitPattern.valueOf(pattern.toUpperCase())); }
            catch (IllegalArgumentException ignored) { }
        }
        teamRepository.save(team);
        return ResponseEntity.ok(Map.of(
                "homeKitColor", team.getHomeKitColor(),
                "awayKitColor", team.getAwayKitColor(),
                "kitPattern", team.getKitPattern().name()));
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
        // Owned plus loaned in. A loanee keeps Player.team on the club that owns him, so without the
        // union he is invisible on the squad screen and the lineup editor — which is the same thing as
        // the feature not existing. See SquadRegistrationService.availablePlayers.
        List<Player> teamPlayers = squadRegistration.availablePlayers(teamId);
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

    /**
     * Another club's squad, as much of it as a stranger may see (owner, 2026-10-21).
     *
     * <p>The owner asked for a squad screen any manager can open on any club:
     * <i>"svaki user bi trebalo da moze da klikne na squad za drugi tim samo ga vidi bez skilova, vidi
     * formu, rejting, apps, goals, assists"</i>. This is the read behind it, and it answers with
     * {@link PublicSquadMemberDTO} — form, rating, appearances, goals, assists and the injury state,
     * and <b>no skill field and no talent at all</b>. A shape with no skill on it cannot leak one.
     *
     * <p><b>Why not reuse {@code /players}.</b> That route answers {@link PlayerDTO}, which carries the
     * player's whole skill set; no screen renders it for a club you do not own, but it is sent, and the
     * owner's request is that a stranger sees the player <i>without</i> the skills. Hiding them in the
     * page would leave them one devtools tab away — the same shape of mistake as a hidden button
     * guarding an action.
     *
     * <p><b>Not a permission check.</b> Any signed-in manager may read any club's squad; that is the
     * feature, not a hole. This route is a read, and it cannot write.
     */
    @GetMapping("/{teamId}/squad")
    public ResponseEntity<List<PublicSquadMemberDTO>> getPublicSquad(@PathVariable Long teamId) {
        List<Player> teamPlayers = squadRegistration.availablePlayers(teamId);
        Map<Long, List<MatchPlayerStats>> statsByPlayerId = teamPlayers.isEmpty()
                ? Map.of()
                : matchPlayerStatsRepository.findByPlayerIdIn(teamPlayers.stream().map(Player::getId).toList())
                .stream()
                .filter(stats -> stats.getPlayer() != null)
                .collect(Collectors.groupingBy(stats -> stats.getPlayer().getId()));

        List<PublicSquadMemberDTO> squad = teamPlayers
                .stream()
                .map(player -> PublicSquadMemberDTO.from(player,
                        statsByPlayerId.getOrDefault(player.getId(), List.of()).size(),
                        averageRating10(statsByPlayerId.get(player.getId()))))
                .toList();
        return ResponseEntity.ok(squad);
    }

    /**
     * The season's mean match rating for one player, to one decimal, or null if he has not played.
     *
     * <p>The same figure the owning manager's squad screen shows, computed the same way — an average of
     * the per-match ratings on a ten-point scale, rounded to one decimal. Null rather than zero for a
     * player with no appearances, because "no rating yet" and "rated zero" are different facts.
     */
    private Double averageRating10(List<MatchPlayerStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        double average = stats.stream().mapToInt(MatchPlayerStats::getRating).average().orElse(0.0) / 10.0;
        return Math.round(average * 10.0) / 10.0;
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
        Long viewerTeamId = clubOwnership.clubIdOf(user);
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
                                                                 @RequestParam(value = "competitionType", required = false) String competitionType,
                                                                 @RequestParam(value = "matchType", required = false) String matchType,
                                                                 @RequestParam(value = "allCompetitions", required = false, defaultValue = "false") boolean allCompetitions,
                                                                 @AuthenticationPrincipal User user) {
        // Who is asking, because this is the surface where a manager is most likely to see his own
        // result by accident: the schedule is the page a manager opens to see what is next.
        Long viewerTeamId = clubOwnership.clubIdOf(user);
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        int currentActiveSeasonYear = seasonService.getActiveSeasonYear();
        int activeSeasonYear = seasonYear != null ? seasonYear : currentActiveSeasonYear;
        // A caller asking for one kind of football cannot be answered from the league alone. This route
        // resolves a single competition -- the club's league -- so a cup or an international fixture was
        // invisible here, and the frontend's cup and international screens were pointing at fabricated data
        // because there was nothing real to ask for. Each row already carries `competitionType`, so the
        // filter is a query choice and a `filter`, not a new endpoint.
        //
        // Read as a single enum name, case-insensitively, and ignored when it names nothing we know. A
        // filter that silently returned the league instead would be worse than no filter, because the
        // screen would fill with plausible rows from the wrong competition.
        CompetitionType wantedType = parseCompetitionType(competitionType);
        MatchType wantedMatchType = parseMatchType(matchType);

        // Two filters, because a friendly belongs to no competition. The calendar puts friendlies in weeks
        // 6, 11 and 12 -- "a club is not handed a friendly, it asks for one and the other club may refuse" --
        // so they have no league to be found through and `competitionType` cannot select them. That is the
        // whole reason this screen was pointing at fabricated data, and it is why `MatchType` exists.
        // **`allCompetitions` asks for every fixture, and only the dashboard's two cards do.**
        //
        // Without it the route answers one competition — the club's league — whenever no filter is named,
        // because that is what a Schedule page wants. But "your next match" and "recent matches" are not
        // league questions: a Champions Cup round can fall between two league rounds, and a manager told
        // "no next match" while holding five group fixtures is being told something false (owner,
        // 2026-10-10).
        //
        // An explicit parameter rather than a missing one, because the default has to keep meaning the
        // league for the Schedule page, and a filter that silently returned the league instead would be
        // worse than no filter at all.
        Competition competition = (wantedType == null && wantedMatchType == null && !allCompetitions)
                ? resolveScheduleCompetition(team, activeSeasonYear) : null;
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
                .filter(fixture -> wantedType == null
                        || (fixture.getCompetition() != null && fixture.getCompetition().getType() == wantedType))
                .filter(fixture -> wantedMatchType == null || fixture.resolvedMatchType() == wantedMatchType)
                .map(fixture -> {
                    Long opponentId = resolveOpponentId(fixture, teamId);
                    return toScheduleRow(teamId, fixture, headToHeadByOpponent.get(opponentId), snapshots, viewerTeamId);
                })
                .toList();

        return ResponseEntity.ok(schedule);
    }

    /** {@code null} for absent or unrecognised, so the route keeps its old behaviour rather than guessing. */
    private static MatchType parseMatchType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MatchType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAType) {
            return null;
        }
    }

    /** {@code null} for absent or unrecognised, so the route keeps its old behaviour rather than guessing. */
    private static CompetitionType parseCompetitionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CompetitionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAType) {
            return null;
        }
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

    /**
     * The doctor operates: the absence is cut by a share of what is left, and the club pays for it.
     *
     * <p>Owner, 2026-10-22: *"stavi da medical center (uz neko placanje koje i nije najjeftinije) moze
     * da se prepolovi vreme. koliko moze i koliko kosta zavisi od doktora, bez doktora ne moze."*
     *
     * <p>Guarded like the recovery session above, because it moves money and it heals a player.
     */
    @PostMapping("/{teamId}/medical/surgery/{playerId}")
    public ResponseEntity<TeamMedicalOverviewDTO> applySurgery(@PathVariable Long teamId,
                                                                @PathVariable Long playerId,
                                                                @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        TeamMedicalOverviewDTO overview = teamMedicalService.applySurgery(teamId, playerId);
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
        // Same lock as the tactics, and for the same reason: the eleven is part of what was decided.
        var lock = matchLocks.lockFor(teamId);
        if (lock.isPresent()) {
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("reason", matchLocks.refusalReason(lock.get(), teamId)));
        }
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        String formation = Objects.toString(payload.getOrDefault("formation", "4-4-2"), "4-4-2");
        String style = normalizeStyle(payload.get("style"));
        List<Long> starterIds = parseIdList(payload.getOrDefault("starterIds", List.of()), 11);
        List<Long> benchIds = parseIdList(payload.getOrDefault("benchIds", List.of()), 7);

        // The union, and this is the load-bearing one: a saved XI is filtered down to this list, so a
        // loanee missing here is a loanee the manager picked who then never appears in the match.
        List<Player> teamPlayers = squadRegistration.availablePlayers(teamId);
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
    public ResponseEntity<TacticsEditorDTO> getTacticsEditor(
            @PathVariable Long teamId,
            @RequestParam(value = "formation", required = false) String formation,
            @RequestParam(value = "tacticId", required = false) Long tacticId) {
        // tacticId is what makes the editor read the SAME store it writes. Without it the page
        // loads the club profile while Save writes the chosen tactic, and a moved player comes
        // back where he was - owner, 2026-10-19: "kad izmenim POZICIJU IGRAČA ... treba da sačuva".
        TacticsEditorDTO dto = teamTacticsService.getTacticsEditor(teamId, formation, tacticId);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    @GetMapping("/{teamId}/decide")
    public ResponseEntity<?> decide(
            @PathVariable Long teamId,
            @RequestParam("ballRow") double ballRow,
            @RequestParam("ballCol") double ballCol,
            @RequestParam(value = "formation", required = false) String formation,
            @RequestParam(value = "tacticId", required = false) Long tacticId,
            @RequestParam(value = "carrierSlot", required = false) String carrierSlot) {
        TacticsEditorDTO dto = teamTacticsService.getTacticsEditor(teamId, formation, tacticId);
        if (dto == null) return ResponseEntity.notFound().build();

        // Resolve target cell per slot, same logic as the tactic editor front-end.
        java.util.Map<String, String> rulesMap = new java.util.HashMap<>();
        for (TacticsRuleDTO r : dto.getMovementRules()) {
            if (r.getSlotKey() != null && r.getBallStateKey() != null
                    && r.getPossessionContext() != null && r.getTargetCellKey() != null) {
                rulesMap.put(r.getSlotKey() + "|" + r.getBallStateKey() + "|" + r.getPossessionContext(),
                        r.getTargetCellKey());
            }
        }

        int br = (int) Math.min(7, Math.max(1, Math.floor(ballRow)));
        int bc = (int) Math.min(6, Math.max(1, Math.floor(ballCol)));
        String ballState = "CELL_" + (br - 1) + "_" + (bc - 1);

        double awayBallRow = 9 - ballRow, awayBallCol = 8 - ballCol;
        int abr = (int) Math.min(7, Math.max(1, Math.floor(awayBallRow)));
        int abc = (int) Math.min(6, Math.max(1, Math.floor(awayBallCol)));
        String awayBallState = "CELL_" + (abr - 1) + "_" + (abc - 1);

        org.tifo.footballmanager.newLogic.sim.model.PlayerSkills skills = new org.tifo.footballmanager.newLogic.sim.model.PlayerSkills(14, 14, 14, 14, 14, 14, 14, 14);
        List<org.tifo.footballmanager.newLogic.sim.model.Player> players = new ArrayList<>();
        for (TacticsSlotDTO slot : dto.getSlotDefinitions()) {
            org.tifo.footballmanager.newLogic.sim.model.Position homePos = slotTarget(slot, ballState, rulesMap, true);
            players.add(new org.tifo.footballmanager.newLogic.sim.model.Player(slot.getSlotKey(), slot.getSlotKey(), "HOME", slot.getLine(),
                    homePos, homePos, skills));
            org.tifo.footballmanager.newLogic.sim.model.Position awayPos = slotTarget(slot, awayBallState, rulesMap, false);
            players.add(new org.tifo.footballmanager.newLogic.sim.model.Player("A_" + slot.getSlotKey(), slot.getSlotKey(), "AWAY", slot.getLine(),
                    awayPos, awayPos, skills));
        }

        // Carrier = chosen slot (or nearest non-GK home player to the ball).
        org.tifo.footballmanager.newLogic.sim.model.Player carrier = null;
        if (carrierSlot != null && !carrierSlot.isBlank()) {
            for (org.tifo.footballmanager.newLogic.sim.model.Player p : players) {
                if ("HOME".equals(p.getTeam()) && p.getId().equals(carrierSlot)) { carrier = p; break; }
            }
        }
        if (carrier == null) {
            double bestD = Double.MAX_VALUE;
            for (org.tifo.footballmanager.newLogic.sim.model.Player p : players) {
                if (!"HOME".equals(p.getTeam()) || p.isGoalkeeper()) continue;
                double d = SimUtils.distance(p.getPosition(), new org.tifo.footballmanager.newLogic.sim.model.Position(ballRow, ballCol));
                if (d < bestD) { bestD = d; carrier = p; }
            }
        }
        if (carrier == null) {  // fallback (shouldn't happen)
            double bestD = Double.MAX_VALUE;
            for (org.tifo.footballmanager.newLogic.sim.model.Player p : players) {
                if (!"HOME".equals(p.getTeam())) continue;
                double d = SimUtils.distance(p.getPosition(), new org.tifo.footballmanager.newLogic.sim.model.Position(ballRow, ballCol));
                if (d < bestD) { bestD = d; carrier = p; }
            }
        }
        if (carrier != null) {
            carrier.setPosition(new org.tifo.footballmanager.newLogic.sim.model.Position(ballRow, ballCol));
        }

        org.tifo.footballmanager.newLogic.sim.model.MatchState state = new org.tifo.footballmanager.newLogic.sim.model.MatchState();
        for (org.tifo.footballmanager.newLogic.sim.model.Player p : players) state.getPlayers().add(p);
        state.setCarrier(carrier);
        state.setLastTouchTeam("HOME");
        org.tifo.footballmanager.newLogic.sim.model.Ball ball = state.getBall();
        ball.setPosition(carrier != null ? carrier.getPosition() : new org.tifo.footballmanager.newLogic.sim.model.Position(ballRow, ballCol));
        ball.stop();
        state.clearPassContext();

        CleanDecisionEngine engine = new CleanDecisionEngine();
        DecisionResult res = engine.decideWithOptions(state);

        Map<String, Object> out = new LinkedHashMap<>();
        if (res.getChosen() != null) {
            out.put("chosen", optionToMap(res.getChosen()));
        }
        List<Map<String, Object>> opts = new ArrayList<>();
        for (DecisionOption o : res.getOptions()) opts.add(optionToMap(o));
        out.put("options", opts);

        // Per-receiver pass scores: the carrier evaluates every (eligible) teammate.
        List<Map<String, Object>> passCands = new ArrayList<>();
        for (DecisionOption po : engine.passCandidateOptions(state, carrier)) {
            passCands.add(optionToMap(po));
        }
        out.put("passCandidates", passCands);

        // Debug info for the lab tool: positions and offside margins.
        Map<String, Object> dbg = new LinkedHashMap<>();
        dbg.put("carrierPos", carrier.getId() + " @ " + carrier.getPosition().getRow() + "|" + carrier.getPosition().getColumn());
        dbg.put("ballState", ballState);
        dbg.put("awayBallState", awayBallState);
        dbg.put("rulesCount", rulesMap.size());
        List<String> sample = new ArrayList<>();
        for (TacticsSlotDTO sl : dto.getSlotDefinitions()) {
            String k = sl.getSlotKey() + "|" + awayBallState + "|WE_HAVE_BALL";
            sample.add(sl.getSlotKey() + " -> " + rulesMap.get(k));
            if (sample.size() >= 4) break;
        }
        dbg.put("awaySampleLookups", sample);
        List<String> homeTargets = new ArrayList<>();
        for (TacticsSlotDTO sl : dto.getSlotDefinitions()) {
            String k = sl.getSlotKey() + "|" + ballState + "|WE_HAVE_BALL";
            String cell = rulesMap.get(k);
            if (cell == null) cell = sl.getAnchorCellKey();
            if (cell == null) cell = "CELL_2_2";
            org.tifo.footballmanager.newLogic.sim.model.Position hp = slotTarget(sl, ballState, rulesMap, true);
            homeTargets.add(sl.getSlotKey() + " lookup=" + rulesMap.get(k) + " resolved=" + cell + " target=" + hp.getRow() + "|" + hp.getColumn());
        }
        dbg.put("homeSlotTargets", homeTargets);
        List<String> homeSample = new ArrayList<>();
        for (TacticsSlotDTO sl : dto.getSlotDefinitions()) {
            String k = sl.getSlotKey() + "|" + ballState + "|WE_HAVE_BALL";
            homeSample.add(sl.getSlotKey() + " -> " + rulesMap.get(k));
            if (homeSample.size() >= 6) break;
        }
        dbg.put("homeSampleLookups", homeSample);
        List<Map<String, Object>> inBox = new ArrayList<>();
        String team = carrier.getTeam();
        for (org.tifo.footballmanager.newLogic.sim.model.Player mate : state.getPlayers()) {
            if (!mate.getTeam().equals(team) || mate.equals(carrier)) continue;
            if (mate.isUnavailable()) continue;
            // isInBox
            double r = mate.getPosition().getRow();
            boolean inRows = "HOME".equals(team) ? r >= 6.5 : r <= 2.5;
            boolean inCols = Math.abs(mate.getPosition().getColumn() - 3.5) <= 1.5;
            if (!inRows || !inCols) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", mate.getId());
            m.put("pos", r + "|" + mate.getPosition().getColumn());
            m.put("offsideMargin", CleanDecisionEngine.offsideMargin(state, carrier, mate));
            inBox.add(m);
        }
        // Simulate findBestReceiver filtering to debug why PASS shows "no receiver"
        int eligibleCount = 0;
        List<String> eligibleIds = new ArrayList<>();
        for (org.tifo.footballmanager.newLogic.sim.model.Player tm : state.getPlayers()) {
            if (!tm.getTeam().equals(carrier.getTeam()) || tm.equals(carrier)) continue;
            if (tm.isUnavailable()) { eligibleIds.add(tm.getId() + "(unavail)"); continue; }
            boolean _sh = "HOME".equals(carrier.getTeam()) ? carrier.getPosition().getRow() >= 6.0 : carrier.getPosition().getRow() <= 3.0;
            boolean _oh = "HOME".equals(tm.getTeam()) ? tm.getPosition().getRow() < 4.5 : tm.getPosition().getRow() > 4.5;
            if (_sh && _oh) { eligibleIds.add(tm.getId() + "(ownhalf)"); continue; }
            double om = CleanDecisionEngine.offsideMargin(state, carrier, tm);
            if (om > CleanDecisionEngine.OFFSIDE_HARD_LIMIT) { eligibleIds.add(tm.getId() + "(offside " + String.format("%.2f", om) + ")"); continue; }
            boolean marg = om > 0;
            if (marg && !CleanDecisionEngine.carrierWillRiskOffsidePublic(carrier)) { eligibleIds.add(tm.getId() + "(risk fail)"); continue; }
            eligibleCount++;
            eligibleIds.add(tm.getId() + "(OK)");
        }
        dbg.put("eligibleForPass", eligibleIds);
        dbg.put("eligibleCount", eligibleCount);
        List<String> allPos = new ArrayList<>();
        for (org.tifo.footballmanager.newLogic.sim.model.Player pp : state.getPlayers()) {
            allPos.add(pp.getId() + "=" + pp.getPosition().getRow() + "|" + pp.getPosition().getColumn());
        }
        dbg.put("allPositions", allPos);
        List<Double> defRows = new ArrayList<>();
        for (org.tifo.footballmanager.newLogic.sim.model.Player opp : state.getPlayers()) {
            if (!opp.getTeam().equals("AWAY")) continue;
            defRows.add(opp.getPosition().getRow());
        }
        defRows.sort(Comparator.reverseOrder());
        dbg.put("awayDefRowsSorted", defRows);
        dbg.put("offsideLine2nd", defRows.size() >= 2 ? defRows.get(1) : null);
        out.put("debug", dbg);
        return ResponseEntity.ok(out);
    }

    private org.tifo.footballmanager.newLogic.sim.model.Position slotTarget(TacticsSlotDTO slot, String ballState, Map<String, String> rulesMap, boolean home) {
        String key = slot.getSlotKey() + "|" + ballState + "|WE_HAVE_BALL";
        String cell = rulesMap.get(key);
        if (cell == null) cell = slot.getAnchorCellKey();
        if (cell == null) cell = "CELL_2_2";
        int[] pw = parseCell(cell);
        int p = pw[0], w = pw[1];
        if (home) return new org.tifo.footballmanager.newLogic.sim.model.Position(p + 1.5, w + 1.5);
        return new org.tifo.footballmanager.newLogic.sim.model.Position(7.5 - p, 6.5 - w);
    }

    private int[] parseCell(String cell) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CELL_(\\d+)_(\\d+)").matcher(cell == null ? "" : cell);
        if (m.find()) return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
        return new int[] { 2, 2 };
    }

    private Map<String, Object> optionToMap(DecisionOption o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", o.getType() == null ? null : o.getType().name());
        m.put("target", o.getTarget() == null ? null : o.getTarget().getId());
        m.put("targetLabel", o.getTarget() == null ? null : o.getTarget().getLabel());
        m.put("score", o.getScore());
        m.put("reason", o.getReason());
        return m;
    }

    /**
     * Saves the club's tactical grid.
     *
     * <p>Unguarded, like the lineup template above: any manager could rewrite a rival's roles, and this is
     * the grid the match engine now reads — {@code TacticsRulesProvider} loads it and hands it to the
     * orchestrator, so the edit changed how the next match was actually simulated.
     */
    @PutMapping("/{teamId}/tactics-editor")
    public ResponseEntity<?> saveTacticsEditor(@PathVariable Long teamId,
                                               @RequestBody TacticsEditorSaveRequest request,
                                               @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // An hour before kickoff the match has already been computed, so a change now could not reach
        // it. Refusing is the honest answer; 423 says "locked" rather than "forbidden", because the
        // manager is allowed to do this - just not this week.
        var lock = matchLocks.lockFor(teamId);
        if (lock.isPresent()) {
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("reason", matchLocks.refusalReason(lock.get(), teamId)));
        }
        TacticsEditorDTO dto = teamTacticsService.saveTacticsEditor(teamId, request);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    /**
     * Whether this club's next match is already decided, for the pages that must not offer to change it.
     *
     * <p>An hour before kickoff the match has been computed, and everything a manager could still change
     * about it is a fiction. The server refuses those writes with 423; this is the other half, so the
     * page can say so <em>before</em> he tries — with the opponent, the day and the kickoff hour, because
     * a disabled button with no explanation is the thing that makes a manager think the game is broken.
     *
     * <p>A separate endpoint rather than a field on the tactics or lineup payload, because three pages
     * need this and none of them is the other two's business: the tactic editor, the squad page, and the
     * match page itself.
     *
     * @return {@code locked} false when the club has nothing to protect, in which case the rest is absent
     */
    @GetMapping("/{teamId}/match-lock")
    public ResponseEntity<Map<String, Object>> matchLock(@PathVariable Long teamId) {
        var lock = matchLocks.lockFor(teamId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (lock.isEmpty()) {
            out.put("locked", false);
            return ResponseEntity.ok(out);
        }

        org.tifo.footballmanager.newLogic.model.MatchFixture fixture = lock.get();
        Long teamIdHome = fixture.getHomeTeam() == null ? null : fixture.getHomeTeam().getId();
        boolean isHome = teamIdHome != null && teamIdHome.equals(teamId);
        org.tifo.footballmanager.newLogic.model.Team opponent =
                isHome ? fixture.getAwayTeam() : fixture.getHomeTeam();
        Integer kickoffHour = fixture.getDayNumber() == null
                ? null
                : org.tifo.footballmanager.newLogic.model.GameDay.of(fixture.getDayNumber()).kickoffHour();

        out.put("locked", true);
        out.put("reason", matchLocks.refusalReason(fixture, teamId));
        out.put("fixtureId", fixture.getId());
        out.put("opponent", opponent == null ? null : opponent.getName());
        out.put("homeOrAway", isHome ? "HOME" : "AWAY");
        out.put("day", fixture.getDayNumber());
        out.put("kickoffHour", kickoffHour);
        return ResponseEntity.ok(out);
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

    /** "Season 1 · Week 3 · Day 3 · 18:00", matching MatchDTO so both surfaces read the same. */
    private String buildSeasonDayLabel(MatchFixture fixture) {
        if (fixture.getSeasonYear() == null) {
            return null;
        }
        StringBuilder label = new StringBuilder("Season ").append(fixture.getSeasonYear());
        // The week belongs in the label for the same reason it belongs on the fixture: "Day 3" on its
        // own names eighty-four slots in a season. See MatchDTO#buildSeasonDayLabel.
        if (fixture.getWeekNumber() != null) {
            label.append(" \u00b7 Week ").append(fixture.getWeekNumber());
        }
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

        // **The league round, which is NOT the same as `round` and is not the week.**
        //
        // A league round sits on two days a week in this calendar, and the season runs a round every two
        // weeks: day 3 is round 1, day 5 round 2, day 7 round 3, then week 2 starts round 4. A fixture in
        // week 1 therefore carries `weekNumber = 1` and `roundNumber = 3`, and a row that said "week 1"
        // for its third round was the owner's exact complaint — "kad pise season 1 day 3, za sve ligaske
        // ne nza se koji je od koje nedelje" (owner, 2026-10-10).
        //
        // `roundNumber` is the cup's round, which is why this needs its own field. For a cup the two
        // numbers agree, and the row says "round"; for a league the row says "round N of week M".
        boolean isLeague = fixture.getCompetition() != null
                && fixture.getCompetition().getType() == CompetitionType.LEAGUE;
        row.put("leagueRound", isLeague ? (fixture.getRoundNumber() != null ? fixture.getRoundNumber() : 1) : null);
        row.put("seasonYear", fixture.getSeasonYear());
        row.put("competitionName", fixture.getCompetition() != null ? fixture.getCompetition().getName() : "Competition");
        // The id as well as the name: the schedule screen needs it to send you to the right league,
        // and a name alone cannot be clicked safely when two divisions can share one.
        row.put("competitionId", fixture.getCompetition() != null ? fixture.getCompetition().getId() : null);
        // League / Cup / Friendly, so a fixture says what kind of match it is without a trip to its
        // league page. Two divisions can share a name; they do not share a type.
        row.put("competitionType", fixture.getCompetition() != null && fixture.getCompetition().getType() != null
                ? fixture.getCompetition().getType().name() : null);
        // Who plays it, which is not what `competitionType` says. A Champions Cup round is `type = CUP`
        // and `scope = INTERNATIONAL`: by type alone every continental tie in a club's schedule reads as
        // a domestic cup, the International filter finds nothing, and a manager in the Champions Cup
        // cannot find his own fixtures (owner, 2026-10-16).
        row.put("competitionScope", fixture.getCompetition() != null && fixture.getCompetition().getScope() != null
                ? fixture.getCompetition().getScope().name() : null);
        // The match's own type, which is not the same thing: a friendly belongs to no competition, and
        // resolvedMatchType falls back to the competition for the rows that predate the column.
        row.put("matchType", fixture.resolvedMatchType() == null ? null : fixture.resolvedMatchType().name());
        // The game's own calendar, next to the wall-clock date. The owner asked for both: these are
        // different facts, and only this one is what the season is actually built on.
        row.put("day", fixture.getDayNumber());
        row.put("seasonDayLabel", buildSeasonDayLabel(fixture));
        // Without the replay id a hidden row's "Watch your match" has nothing to open - the fixture id
        // and the replay id are not the same number, and guessing costs the manager the button.
        row.put("replayId", playedMatch != null ? playedMatch.getReplayId() : null);
        row.put("matchDate", org.tifo.footballmanager.newLogic.util.DateTimes.format(fixture.getMatchDate()));
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
        row.put("prediction", org.tifo.footballmanager.newLogic.util.PredictionPayload.toMap(insights.prediction()));
        row.put("h2h", h2hSummary != null ? h2hSummary : emptyHeadToHeadSummary());
        return row;
    }


    private Map<Long, Map<String, Object>> buildHeadToHeadByOpponent(Long teamId) {
        return matchRepository.findByHomeTeamIdOrAwayTeamId(teamId, teamId).stream()
                .filter(Match::isPlayed)
                .filter(match -> match.getHomeTeam() != null && match.getAwayTeam() != null)
                // **A match whose result has not been revealed is not in the history yet.** The
                // head-to-head line is a summary of what the manager has been shown, and it counted a
                // match the manager had not been shown: the win and the goals of a game whose result
                // was still masked (owner, 2026-10-21: "pojavi se u next matches vec u h2h, to treba
                // da azurira kad klikne watch your match ili show results"). The same rule the row
                // itself uses to hide the score: the side this club is on has to be the revealed one.
                .filter(match -> resultIsRevealedFor(match, teamId))
                .collect(Collectors.groupingBy(match -> resolveOpponentId(match, teamId)))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> summarizeHeadToHead(teamId, entry.getValue())));
    }

    /**
     * Whether the club's own side of the result has been shown.
     *
     * <p>The reveal is per side, so a manager watching their own club sees their own result first.
     */
    private boolean resultIsRevealedFor(Match match, Long teamId) {
        boolean isHome = Objects.equals(match.getHomeTeam().getId(), teamId);
        return isHome ? match.isHomeResultRevealed() : match.isAwayResultRevealed();
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
        summary.put("lastMeetingDate", lastMeeting != null ? org.tifo.footballmanager.newLogic.util.DateTimes.format(lastMeeting.getMatchDate()) : "N/A");
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
