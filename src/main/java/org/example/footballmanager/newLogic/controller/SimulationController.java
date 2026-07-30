package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.dto.TacticsRuleDTO;
import org.example.footballmanager.newLogic.dto.TacticsSlotDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.*;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private static final int DEFAULT_SEASON_YEAR = 2025;

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final SeasonRepository seasonRepository;
    private final MatchRepository matchRepository;
    private final MatchStore matchStore;
    private final CurrentRoundSimulationStateService stateService;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionRepository competitionRepository;
    private final SeasonService seasonService;
    private final TrainingProgressionService trainingProgressionService;
    private final ObjectMapper objectMapper;
    private final AsyncSimulationRunner asyncSimulationRunner;
    private final PlayerRepository playerRepository;
    private final TeamTacticsService teamTacticsService;
    private final FormationSlotCatalog formationSlotCatalog;
    private final LineupRepository lineupRepository;

    private MatchOrchestrator newOrchestrator() {
        return new MatchOrchestrator(matchStore, teamRepository, playerRepository, lineupRepository);
    }

    private record TacticsWithSlots(TacticRules rules, List<String> slots) {}

    private TacticsWithSlots loadTacticsForTeam(Long teamId) {
        if (teamId == null) return null;
        try {
            org.example.footballmanager.newLogic.dto.TacticsEditorDTO editor = teamTacticsService.getTacticsEditor(teamId, null);
            if (editor == null) return null;

            List<String> slotKeys = editor.getSlotDefinitions().stream()
                .map(TacticsSlotDTO::getSlotKey).toList();
            List<TacticsRuleDTO> rulesList = editor.getMovementRules();

            TacticRules rules = TacticRules.createDefault(slotKeys);
            for (TacticsRuleDTO rule : rulesList) {
                if (rule == null) continue;
                boolean inPossession = "WE_HAVE_BALL".equals(rule.getPossessionContext());
                rules.setRule(rule.getSlotKey(), rule.getBallStateKey(), inPossession, rule.getTargetCellKey());
            }

            return new TacticsWithSlots(rules, slotKeys);
        } catch (Exception e) {
            log.warn("Failed to load tactics for teamId={}, using defaults", teamId, e);
            return null;
        }
    }

    private Long resolveTeamId(String teamName) {
        org.example.footballmanager.newLogic.model.Team dbTeam = teamRepository.findByName(teamName).orElse(null);
        return dbTeam != null ? dbTeam.getId() : null;
    }

    @Transactional
    @PostMapping("/current-round/prepare")
    public ResponseEntity<Map<String, Object>> prepareCurrentRound(@AuthenticationPrincipal User user) {
        PreparedMatchContext context = resolvePreparedMatch(user);
        if (context == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("action", "NO_MATCH_CURRENT_WEEK");
            payload.put("message", "Your club has no scheduled match in the current round.");
            stateService.setPrepareSnapshot(payload);
            return ResponseEntity.ok(payload);
        }

        long matchStoreId = simulateAndStore(context.homeName(), context.awayName(), true);
        MatchResult result = matchStore.getResult(matchStoreId);

        Long dbMatchId = persistMatchToDB(context.fixture(), result, matchStoreId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("action", "START_MATCH");
        payload.put("message", "Realistic simulation started - replay data is available.");
        payload.put("matchId", matchStoreId);
        payload.put("dbMatchId", dbMatchId);
        payload.put("position_socket", "/demo-position-updates");
        payload.put("event_socket", "/demo-match-events");
        payload.put("replay_metadata", "/api/zox/replay/" + matchStoreId + "/metadata");
        payload.put("replay_chunk_template", "/api/zox/replay/" + matchStoreId + "/chunks/{chunkIndex}");
        stateService.setPrepareSnapshot(payload);
        stateService.setFeedSnapshot(buildSingleMatchFeed(context, matchStoreId, result));
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/current-round/prepare/status")
    public ResponseEntity<Map<String, Object>> prepareStatus() {
        return ResponseEntity.ok(stateService.getPrepareSnapshot());
    }

    @PostMapping("/current-round/simulate-all")
    public ResponseEntity<Map<String, Object>> simulateCurrentRound(@AuthenticationPrincipal User user) {
        GameClock clock = seasonService.getOrCreateClock();
        int currentWeek = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int seasonYear = clock.getCurrentSeason() != null
                ? SeasonService.BASE_SEASON_YEAR + (clock.getCurrentSeason() - 1)
                : DEFAULT_SEASON_YEAR;

        List<MatchFixture> fixtures = matchFixtureRepository.findAll().stream()
                .filter(f -> Objects.equals(f.getSeasonYear(), seasonYear))
                .filter(f -> Objects.equals(f.getRoundNumber(), currentWeek))
                .filter(f -> !f.isPlayed())
                .filter(f -> f.getHomeTeam() != null && f.getAwayTeam() != null)
                .sorted(Comparator.comparing((MatchFixture f) -> f.getCompetition() == null ? Long.MAX_VALUE : f.getCompetition().getId())
                        .thenComparing(MatchFixture::getMatchDate, Comparator.nullsLast(LocalDateTime::compareTo)))
                .toList();

        if (fixtures.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("action", "ROUND_SIMULATED");
            payload.put("message", "No fixtures were available for the current round.");
            payload.put("simulatedCount", 0);
            payload.put("leaguesProcessed", 0);
            payload.put("leagueResults", List.of());
            stateService.setRoundSimulationSnapshot(payload);
            return ResponseEntity.ok(payload);
        }

        // Podeli na korisnikovu ligu i ostale
        String userTeamName = resolveUserTeamName(user);
        String userLeagueName = null;
        if (userTeamName != null) {
            Team userTeam = teamRepository.findByName(userTeamName).orElse(null);
            if (userTeam != null && userTeam.getCompetition() != null) {
                userLeagueName = userTeam.getCompetition().getName();
            }
        }
        List<MatchFixture> userLeagueFixtures = new ArrayList<>();
        List<MatchFixture> otherLeagueFixtures = new ArrayList<>();

        for (MatchFixture fixture : fixtures) {
            String leagueName = fixture.getCompetition() != null ? fixture.getCompetition().getName() : "League";
            if (leagueName.equals(userLeagueName)) {
                userLeagueFixtures.add(fixture);
            } else {
                otherLeagueFixtures.add(fixture);
            }
        }

        // Simuliraj korisnikovu ligu odmah (sinhrono)
        List<Map<String, Object>> leagueResults = new ArrayList<>();
        Map<String, List<Map<String, Object>>> leagues = new LinkedHashMap<>();
        int simulatedCount = 0;

        for (MatchFixture fixture : userLeagueFixtures) {
            long matchStoreId = simulateAndStore(fixture.getHomeTeam().getName(), fixture.getAwayTeam().getName(), isUserMatch(user, fixture));
            MatchResult result = matchStore.getResult(matchStoreId);
            persistMatchToDB(fixture, result, matchStoreId);
            simulatedCount++;

            Map<String, Object> matchPayload = new LinkedHashMap<>();
            matchPayload.put("fixtureId", fixture.getId());
            matchPayload.put("homeTeam", fixture.getHomeTeam().getName());
            matchPayload.put("awayTeam", fixture.getAwayTeam().getName());
            matchPayload.put("homeGoals", result != null ? result.homeGoals() : 0);
            matchPayload.put("awayGoals", result != null ? result.awayGoals() : 0);
            matchPayload.put("isUserMatch", isUserMatch(user, fixture));
            matchPayload.put("events", List.of());
            matchPayload.put("played", true);
            matchPayload.put("matchId", matchStoreId);

            String leagueName = fixture.getCompetition() != null ? fixture.getCompetition().getName() : "League";
            leagues.computeIfAbsent(leagueName, key -> new ArrayList<>()).add(matchPayload);
        }

        // Pokreni async za ostale lige
        if (!otherLeagueFixtures.isEmpty()) {
            List<Long> otherFixtureIds = otherLeagueFixtures.stream().map(MatchFixture::getId).toList();
            asyncSimulationRunner.simulateInBackground(otherFixtureIds);
        }

        // Build response
        for (Map.Entry<String, List<Map<String, Object>>> entry : leagues.entrySet()) {
            Map<String, Object> leaguePayload = new LinkedHashMap<>();
            leaguePayload.put("leagueName", entry.getKey());
            leaguePayload.put("userLeague", isUserLeague(user, entry.getKey()));
            leaguePayload.put("matches", entry.getValue());
            leagueResults.add(leaguePayload);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("action", "ROUND_SIMULATED");
        payload.put("message", simulatedCount > 0
                ? "Simulated your league. Other leagues are simulating in background."
                : "No fixtures were found for your league.");
        payload.put("simulatedCount", simulatedCount);
        payload.put("backgroundSimulating", !otherLeagueFixtures.isEmpty());
        payload.put("backgroundTotal", otherLeagueFixtures.size());
        payload.put("leaguesProcessed", leagueResults.size());
        payload.put("leagueResults", leagueResults.stream()
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("league", entry.get("leagueName"));
                    row.put("remainingBefore", 0);
                    row.put("simulated", ((List<?>) entry.get("matches")).size());
                    row.put("remainingAfter", 0);
                    return row;
                })
                .toList());

        stateService.setRoundSimulationSnapshot(payload);
        stateService.setFeedSnapshot(buildFeedPayload(leagueResults, seasonYear, currentWeek, user));
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/current-round/status")
    public ResponseEntity<Map<String, Object>> currentRoundStatus() {
        Map<String, Object> snapshot = stateService.getRoundSimulationSnapshot();
        if (asyncSimulationRunner.isRunning()) {
            Map<String, Object> payload = new LinkedHashMap<>(snapshot != null ? snapshot : Map.of());
            payload.put("backgroundSimulating", true);
            payload.put("backgroundSimulated", asyncSimulationRunner.getSimulatedCount());
            payload.put("backgroundTotal", asyncSimulationRunner.getTotalCount());
            return ResponseEntity.ok(payload);
        }
        return ResponseEntity.ok(snapshot != null ? snapshot : Map.of("status", "idle"));
    }

    @GetMapping("/current-round/feed")
    public ResponseEntity<Map<String, Object>> currentRoundFeed(@AuthenticationPrincipal User user) {
        Map<String, Object> payload = stateService.getFeedSnapshot();
        if (payload != null && !payload.isEmpty() && "ok".equals(payload.get("status"))) {
            return ResponseEntity.ok(payload);
        }
        return ResponseEntity.ok(buildFallbackFeed(user));
    }

    @PostMapping("/week/advance")
    public ResponseEntity<Map<String, Object>> advanceWeek(@AuthenticationPrincipal User user) {
        GameClock clock = seasonService.getOrCreateClock();
        int currentWeek = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int seasonYear = SeasonService.BASE_SEASON_YEAR + ((clock.getCurrentSeason() != null ? clock.getCurrentSeason() : 1) - 1);

        // Only check user's league fixtures — other leagues can continue in background
        String userTeamName = resolveUserTeamName(user);
        String userLeagueName = null;
        if (userTeamName != null) {
            Team userTeam = teamRepository.findByName(userTeamName).orElse(null);
            if (userTeam != null && userTeam.getCompetition() != null) {
                userLeagueName = userTeam.getCompetition().getName();
            }
        }
        List<MatchFixture> allFixturesForWeek = matchFixtureRepository.findAll().stream()
                .filter(f -> Objects.equals(f.getSeasonYear(), seasonYear))
                .filter(f -> Objects.equals(f.getRoundNumber(), currentWeek))
                .toList();
        String finalUserLeagueName = userLeagueName;
        List<MatchFixture> userFixturesForWeek = userLeagueName != null
                ? allFixturesForWeek.stream()
                    .filter(f -> f.getCompetition() != null && Objects.equals(f.getCompetition().getName(), finalUserLeagueName))
                    .toList()
                : allFixturesForWeek;
        long unplayedCount = userFixturesForWeek.stream().filter(f -> !f.isPlayed()).count();
        if (unplayedCount > 0) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "blocked");
            payload.put("action", "ROUND_NOT_COMPLETE");
            payload.put("message", "Still " + unplayedCount + " unplayed fixture(s) in your league. Simulate your league results first.");
            payload.put("remainingFixtures", unplayedCount);
            stateService.setAdvanceSnapshot(payload);
            return ResponseEntity.ok(payload);
        }

        boolean backgroundRunning = asyncSimulationRunner.isRunning();
        if (backgroundRunning) {
            log.info("Advancing week while background simulation is still running (other leagues).");
        }

        try {
            String teamName = resolveUserTeamName(user);
            if (teamName != null) {
                Team team = teamRepository.findByName(teamName).orElse(null);
                if (team != null) {
                    trainingProgressionService.runWeeklyTraining(team.getId());
                }
            }

            Competition superLiga = competitionRepository.findByName("Superliga Srbije").orElse(null);
            if (superLiga == null) {
                superLiga = competitionRepository.findByCountryIsoCodeAndType("SRB", null).stream().findFirst().orElse(null);
            }
            if (superLiga != null) {
                seasonService.advanceWeekAndHandleSeasonTransition(superLiga);
            } else {
                clock.setCurrentWeek(currentWeek + 1);
                if (clock.getCurrentDate() != null) {
                    clock.setCurrentDate(clock.getCurrentDate().plusWeeks(1));
                }
                seasonService.getOrCreateClock();
            }

            GameClock updatedClock = seasonService.getOrCreateClock();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("action", "WEEK_ADVANCED");
            payload.put("message", "Week advanced from " + currentWeek + " to " + updatedClock.getCurrentWeek() + ".");
            payload.put("newWeek", updatedClock.getCurrentWeek());
            stateService.setAdvanceSnapshot(payload);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            log.error("Failed to advance week", e);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "error");
            payload.put("action", "WEEK_ADVANCE_FAILED");
            payload.put("message", "Failed to advance week: " + e.getMessage());
            stateService.setAdvanceSnapshot(payload);
            return ResponseEntity.ok(payload);
        }
    }

    @GetMapping("/week/advance/status")
    public ResponseEntity<Map<String, Object>> advanceWeekStatus() {
        return ResponseEntity.ok(stateService.getAdvanceSnapshot());
    }

    private long simulateAndStore(String homeName, String awayName, boolean isUserMatch) {
        MatchOrchestrator orchestrator = newOrchestrator();

        // Load tactical editor rules from DB/backup for both teams
        Long homeTeamId = resolveTeamId(homeName);
        Long awayTeamId = resolveTeamId(awayName);
        TacticsWithSlots homeTactics = loadTacticsForTeam(homeTeamId);
        TacticsWithSlots awayTactics = loadTacticsForTeam(awayTeamId);

        long matchId = orchestrator.startMatch(
            homeName, awayName,
            homeTactics != null ? homeTactics.rules() : null,
            homeTactics != null ? homeTactics.slots() : null,
            awayTactics != null ? awayTactics.rules() : null,
            awayTactics != null ? awayTactics.slots() : null
        );

        // Set userMatch flag on the in-memory Match for logging control
        org.example.footballmanager.newLogic.model.Match storeMatch = matchStore.getMatch(matchId);
        if (storeMatch != null) {
            storeMatch.setUserMatch(isUserMatch);
        }

        orchestrator.simulate(matchId);
        return matchId;
    }

    private Long persistMatchToDB(MatchFixture fixture, MatchResult result, long matchStoreId) {
        try {
            Match match = new Match();
            match.setHomeTeam(fixture.getHomeTeam());
            match.setAwayTeam(fixture.getAwayTeam());
            match.setCompetition(fixture.getCompetition());
            match.setSeasonYear(fixture.getSeasonYear());
            match.setRoundNumber(fixture.getRoundNumber());
            match.setWeekNumber(fixture.getWeekNumber());
            match.setMatchDate(fixture.getMatchDate() != null ? fixture.getMatchDate() : LocalDateTime.now());
            match.setHomeGoals(result != null ? result.homeGoals() : 0);
            match.setAwayGoals(result != null ? result.awayGoals() : 0);
            match.setPossessionHome(result != null ? result.homePossession() : 50.0);
            match.setPossessionAway(result != null ? result.awayPossession() : 50.0);
            match.setPlayed(true);
            match.setStarted(true);
            match.setFinished(true);
            match.setReplayId(matchStoreId);
            match.setHomeResultRevealed(true);
            match.setAwayResultRevealed(true);

            if (result != null && result.events() != null) {
                try {
                    match.setEventJson(objectMapper.writeValueAsString(result.events()));
                } catch (Exception e) {
                    log.warn("Failed to serialize match events for matchStoreId={}", matchStoreId, e);
                }
            }

            // Persist lineup data from MatchStore
            try {
                org.example.footballmanager.newLogic.model.Match storeMatch = matchStore.getMatch(matchStoreId);
                if (storeMatch != null) {
                    java.util.Map<String, Object> lineupData = new java.util.LinkedHashMap<>();
                    
                    // Home lineup
                    java.util.List<java.util.Map<String, Object>> homeLineup = new java.util.ArrayList<>();
                    if (storeMatch.homeTeam() != null && storeMatch.homeTeam().startingXI() != null) {
                        for (org.example.footballmanager.newLogic.model.Player p : storeMatch.homeTeam().startingXI()) {
                            java.util.Map<String, Object> playerData = new java.util.LinkedHashMap<>();
                            playerData.put("id", p.getId());
                            playerData.put("name", p.getName());
                            playerData.put("position", p.getPosition() != null ? p.getPosition().name() : "UNKNOWN");
                            playerData.put("rating", p.getRating());
                            homeLineup.add(playerData);
                        }
                    }
                    lineupData.put("homeLineup", homeLineup);
                    
                    // Away lineup
                    java.util.List<java.util.Map<String, Object>> awayLineup = new java.util.ArrayList<>();
                    if (storeMatch.awayTeam() != null && storeMatch.awayTeam().startingXI() != null) {
                        for (org.example.footballmanager.newLogic.model.Player p : storeMatch.awayTeam().startingXI()) {
                            java.util.Map<String, Object> playerData = new java.util.LinkedHashMap<>();
                            playerData.put("id", p.getId());
                            playerData.put("name", p.getName());
                            playerData.put("position", p.getPosition() != null ? p.getPosition().name() : "UNKNOWN");
                            playerData.put("rating", p.getRating());
                            awayLineup.add(playerData);
                        }
                    }
                    lineupData.put("awayLineup", awayLineup);
                    
                    match.setLineupJson(objectMapper.writeValueAsString(lineupData));
                }
            } catch (Exception e) {
                log.warn("Failed to serialize lineup data for matchStoreId={}", matchStoreId, e);
            }

            match = matchRepository.save(match);

            fixture.setPlayed(true);
            fixture.setPlayedMatch(match);
            matchFixtureRepository.save(fixture);

            updateLeagueTable(match, result);

            log.info("Persisted match to DB: id={}, {} {} - {} {} (replayId={})",
                    match.getId(),
                    fixture.getHomeTeam().getName(), result != null ? result.homeGoals() : 0,
                    result != null ? result.awayGoals() : 0, fixture.getAwayTeam().getName(),
                    matchStoreId);

            return match.getId();
        } catch (Exception e) {
            log.error("Failed to persist match to DB for fixture={}", fixture.getId(), e);
            return null;
        }
    }

    private void updateLeagueTable(Match match, MatchResult result) {
        if (match.getCompetition() == null || match.getSeasonYear() == null) return;

        SeasonCompetition sc = seasonService.ensureSeasonCompetition(match.getCompetition(), match.getSeasonYear());

        int homeGoals = result != null ? result.homeGoals() : 0;
        int awayGoals = result != null ? result.awayGoals() : 0;

        CompetitionEntry homeEntry = seasonService.findOrCreateEntry(sc, match.getHomeTeam());
        CompetitionEntry awayEntry = seasonService.findOrCreateEntry(sc, match.getAwayTeam());

        if (homeGoals > awayGoals) { homeEntry.setPoints(homeEntry.getPoints() + 3); homeEntry.setWins(homeEntry.getWins() + 1); }
        else if (homeGoals == awayGoals) { homeEntry.setPoints(homeEntry.getPoints() + 1); homeEntry.setDraws(homeEntry.getDraws() + 1); }
        else { homeEntry.setLosses(homeEntry.getLosses() + 1); }
        homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
        homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);

        if (awayGoals > homeGoals) { awayEntry.setPoints(awayEntry.getPoints() + 3); awayEntry.setWins(awayEntry.getWins() + 1); }
        else if (homeGoals == awayGoals) { awayEntry.setPoints(awayEntry.getPoints() + 1); awayEntry.setDraws(awayEntry.getDraws() + 1); }
        else { awayEntry.setLosses(awayEntry.getLosses() + 1); }
        awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
        awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

        competitionEntryRepository.saveAll(List.of(homeEntry, awayEntry));
    }

    private PreparedMatchContext resolvePreparedMatch(@AuthenticationPrincipal User user) {
        String teamName = resolveUserTeamName(user);
        if (teamName == null) {
            return null;
        }

        Team team = teamRepository.findByName(teamName).orElse(null);
        if (team == null) {
            return null;
        }

        GameClock clock = seasonService.getOrCreateClock();
        int currentWeek = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int seasonYear = clock.getCurrentSeason() != null
                ? SeasonService.BASE_SEASON_YEAR + (clock.getCurrentSeason() - 1)
                : DEFAULT_SEASON_YEAR;

        List<MatchFixture> fixtures = matchFixtureRepository.findAll().stream()
                .filter(fixture -> Objects.equals(fixture.getSeasonYear(), seasonYear))
                .filter(fixture -> Objects.equals(fixture.getRoundNumber(), currentWeek))
                .filter(fixture -> !fixture.isPlayed())
                .filter(fixture -> fixture.getHomeTeam() != null && fixture.getAwayTeam() != null)
                .filter(fixture -> Objects.equals(fixture.getHomeTeam().getId(), team.getId())
                        || Objects.equals(fixture.getAwayTeam().getId(), team.getId()))
                .sorted(Comparator.comparing(MatchFixture::getMatchDate, Comparator.nullsLast(LocalDateTime::compareTo)))
                .toList();

        MatchFixture fixture = fixtures.stream().findFirst().orElse(null);
        if (fixture == null) {
            return null;
        }

        Team homeTeam = fixture.getHomeTeam();
        Team awayTeam = fixture.getAwayTeam();
        if (homeTeam == null || awayTeam == null) {
            return null;
        }

        return new PreparedMatchContext(homeTeam.getName(), awayTeam.getName(), fixture);
    }

    private String resolveUserTeamName(@AuthenticationPrincipal User user) {
        if (user == null) return null;
        if (user.getTifoCTeam() != null && user.getTifoCTeam().getName() != null) {
            return user.getTifoCTeam().getName();
        }
        if (user.getCTeam() != null && user.getCTeam().getName() != null) {
            return user.getCTeam().getName();
        }
        return null;
    }

    private Map<String, Object> buildSingleMatchFeed(PreparedMatchContext context, long matchId, MatchResult result) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("fixtureId", context.fixture().getId());
        match.put("homeTeam", context.homeName());
        match.put("awayTeam", context.awayName());
        match.put("homeGoals", result != null ? result.homeGoals() : 0);
        match.put("awayGoals", result != null ? result.awayGoals() : 0);
        match.put("events", List.of());
        match.put("isUserMatch", true);
        match.put("played", true);
        match.put("matchId", matchId);

        Map<String, Object> league = new LinkedHashMap<>();
        league.put("leagueName", context.fixture().getCompetition() != null ? context.fixture().getCompetition().getName() : "League");
        league.put("userLeague", true);
        league.put("matches", List.of(match));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("currentWeek", context.fixture().getRoundNumber() != null ? context.fixture().getRoundNumber() : 1);
        payload.put("userLeague", league.get("leagueName"));
        payload.put("leagues", List.of(league));
        return payload;
    }

    private Map<String, Object> buildFeedPayload(List<Map<String, Object>> leagues, int seasonYear, int currentWeek, User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("currentWeek", currentWeek);
        payload.put("userLeague", resolveUserLeagueName(user, leagues));
        payload.put("leagues", leagues);
        payload.put("seasonYear", seasonYear);
        return payload;
    }

    private Map<String, Object> buildFallbackFeed(User user) {
        GameClock clock = seasonService.getOrCreateClock();
        int currentWeek = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int seasonYear = clock.getCurrentSeason() != null
                ? SeasonService.BASE_SEASON_YEAR + (clock.getCurrentSeason() - 1)
                : DEFAULT_SEASON_YEAR;

        List<MatchFixture> fixtures = matchFixtureRepository.findAll().stream()
                .filter(fixture -> Objects.equals(fixture.getSeasonYear(), seasonYear))
                .filter(fixture -> Objects.equals(fixture.getRoundNumber(), currentWeek))
                .filter(fixture -> fixture.getHomeTeam() != null && fixture.getAwayTeam() != null)
                .sorted(Comparator.comparing((MatchFixture f) -> f.getCompetition() == null ? Long.MAX_VALUE : f.getCompetition().getId())
                        .thenComparing(MatchFixture::getMatchDate, Comparator.nullsLast(LocalDateTime::compareTo)))
                .toList();

        Map<String, List<Map<String, Object>>> leagues = new LinkedHashMap<>();
        for (MatchFixture fixture : fixtures) {
            String leagueName = fixture.getCompetition() != null ? fixture.getCompetition().getName() : "League";
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("fixtureId", fixture.getId());
            match.put("homeTeam", fixture.getHomeTeam().getName());
            match.put("awayTeam", fixture.getAwayTeam().getName());
            if (fixture.isPlayed() && fixture.getPlayedMatch() != null) {
                match.put("homeGoals", fixture.getPlayedMatch().getHomeGoals());
                match.put("awayGoals", fixture.getPlayedMatch().getAwayGoals());
                match.put("replayId", fixture.getPlayedMatch().getReplayId());
                match.put("matchId", fixture.getPlayedMatch().getReplayId());
            } else {
                match.put("homeGoals", 0);
                match.put("awayGoals", 0);
            }
            match.put("events", List.of());
            match.put("isUserMatch", isUserMatch(user, fixture));
            match.put("played", fixture.isPlayed());
            leagues.computeIfAbsent(leagueName, key -> new ArrayList<>()).add(match);
        }

        List<Map<String, Object>> leagueList = leagues.entrySet().stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("leagueName", entry.getKey());
            row.put("userLeague", isUserLeague(user, entry.getKey()));
            row.put("matches", entry.getValue());
            return row;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("currentWeek", currentWeek);
        payload.put("userLeague", resolveUserLeagueName(user, leagueList));
        payload.put("leagues", leagueList);
        payload.put("seasonYear", seasonYear);
        return payload;
    }

    private String resolveUserLeagueName(User user, List<Map<String, Object>> leagues) {
        String teamName = resolveUserTeamName(user);
        if (teamName == null) return "League";
        Team team = teamRepository.findByName(teamName).orElse(null);
        if (team != null && team.getCompetition() != null && team.getCompetition().getName() != null) {
            return team.getCompetition().getName();
        }
        return leagues.isEmpty() ? "League" : String.valueOf(leagues.get(0).get("leagueName"));
    }

    private boolean isUserLeague(User user, String leagueName) {
        String teamName = resolveUserTeamName(user);
        if (teamName == null) return false;
        Team team = teamRepository.findByName(teamName).orElse(null);
        return team != null && team.getCompetition() != null && Objects.equals(team.getCompetition().getName(), leagueName);
    }

    private boolean isUserMatch(User user, MatchFixture fixture) {
        String teamName = resolveUserTeamName(user);
        if (teamName == null || fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) {
            return false;
        }
        return Objects.equals(fixture.getHomeTeam().getName(), teamName)
                || Objects.equals(fixture.getAwayTeam().getName(), teamName);
    }

    private record PreparedMatchContext(String homeName, String awayName, MatchFixture fixture) {}
}
