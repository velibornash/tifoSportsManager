package org.tifo.footballmanager.newLogic.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.footballmanager.newLogic.model.*;
import org.tifo.footballmanager.newLogic.repository.*;
import org.tifo.footballmanager.newLogic.service.*;
import org.tifo.footballmanager.newLogic.sim.SimMatchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.tifo.footballmanager.newLogic.service.GameClockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** A season is a number counted from 1, matching `game_clock.current_season`. */
    private static final int DEFAULT_SEASON_YEAR = 1;

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final CurrentRoundSimulationStateService stateService;
    private final GameClockService gameClockService;
    private final CompetitionRepository competitionRepository;
    private final org.tifo.footballmanager.newLogic.repository.SubstitutionPlanRepository substitutionPlanRepository;
    private final SeasonService seasonService;
    private final TrainingProgressionService trainingProgressionService;
    private final AsyncSimulationRunner asyncSimulationRunner;
    private final SimMatchService simMatchService;
    private final ClubRatingService clubRatingService;
    private final ExhibitionMatchService exhibitionMatches;
    private final PracticeMatchService practiceMatches;

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

        SimMatchService.SimMatchOutcome sim = simulateAndStore(context.fixture(), true);
        Long dbMatchId = persistSimMatchToDB(context.fixture(), sim);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("action", "START_MATCH");
        payload.put("message", "Simulation finished - replay is available.");
        payload.put("matchId", sim.replayId());
        // **The key above holds a replay id, not a match id.** "dbMatchId" below is the match.
        // The honest name is added beside it so a reader is not left guessing.
        payload.put("replayId", sim.replayId());
        payload.put("dbMatchId", dbMatchId);
        payload.put("viewer_url", "/demo/service/ui/proposal/index.html?replayId=" + sim.replayId());
        stateService.setPrepareSnapshot(payload);
        stateService.setFeedSnapshot(buildSingleMatchFeed(context, sim));
        return ResponseEntity.ok(payload);
    }

    /**
     * Plays a practice match against another club (P2-8).
     *
     * <p>A manager's own decision, so it takes the acting club from the authenticated user rather than
     * from a body parameter: any authenticated manager can play one, and nobody can play one on
     * somebody else's behalf.
     *
     * <p>Played inline rather than scheduled. A fixture in a competition would be picked up by the
     * matchday job and counted into a table, and then counted <em>again</em> by the table
     * reconciliation pass, which rebuilds from {@code match} rows and knows nothing about practice
     * matches. This is also why the friendly fixtures already in the codebase could never be played.
     */
    @PostMapping("/exhibition")
    public ResponseEntity<Map<String, Object>> playExhibition(
            @AuthenticationPrincipal User user,
            @RequestParam Long againstTeamId) {
        Long homeTeamId = resolveUserTeamId(user);
        if (homeTeamId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "code", "NO_TEAM",
                            "message", "Your account does not manage a club."));
        }
        Long matchId = exhibitionMatches.playExhibition(homeTeamId, againstTeamId,
                currentSeason(), currentWeek());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("action", "EXHIBITION_PLAYED");
        payload.put("matchId", matchId);
        payload.put("message", "Exhibition played. It does not affect the table, ratings, "
                + "career records or morale. Fatigue and injury risk still apply.");
        return ResponseEntity.ok(payload);
    }

    /**
     * Plays a short practice match (half length, no side effects) (P2-32).
     *
     * <p>Daily limit: one per club per game day. Charges nothing — no fatigue, no injury,
     * no table, no ratings, no career record.
     */
    @PostMapping("/practice")
    public ResponseEntity<Map<String, Object>> playPractice(
            @AuthenticationPrincipal User user,
            @RequestParam Long againstTeamId) {
        Long homeTeamId = resolveUserTeamId(user);
        if (homeTeamId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "code", "NO_TEAM",
                            "message", "Your account does not manage a club."));
        }
        org.tifo.footballmanager.newLogic.model.GameClock clock = seasonService.getOrCreateClock();
        int gameDay = (clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason()) * 1000
                + (clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek()) * 10
                + (clock.getCurrentDay() == null ? 1 : clock.getCurrentDay());
        Long matchId = practiceMatches.playPractice(homeTeamId, againstTeamId,
                currentSeason(), currentWeek(), gameDay);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("action", "PRACTICE_PLAYED");
        payload.put("matchId", matchId);
        payload.put("message", "Practice match played (half length, no side effects).");
        return ResponseEntity.ok(payload);
    }

    private Integer currentSeason() {
        GameClock clock = seasonService.getOrCreateClock();
        return clock.getCurrentSeason() == null ? DEFAULT_SEASON_YEAR : clock.getCurrentSeason();
    }

    private Integer currentWeek() {
        GameClock clock = seasonService.getOrCreateClock();
        return clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
    }

    /**
     * The acting manager's club, resolved the same way the rest of this controller resolves it — by
     * name off the authenticated user — rather than from a body parameter, so no manager can play an
     * exhibition on somebody else's behalf.
     */
    private Long resolveUserTeamId(User user) {
        // Was a name lookup and a lookup by that name, to arrive back at the team the user already holds.
        Team team = resolveUserTeam(user);
        return team == null ? null : team.getId();
    }

    @GetMapping("/current-round/prepare/status")
    public ResponseEntity<Map<String, Object>> prepareStatus() {
        return ResponseEntity.ok(stateService.getPrepareSnapshot());
    }

    /**
     * Simulates the current round: the caller's own league now, and every other league in the world in the
     * background.
     *
     * <p><b>Administrators only, for the same reason as {@code /week/advance}.</b> The caller's own league
     * is a manager's business, but the other fixtures are handed to
     * {@code asyncSimulationRunner.simulateInBackground(otherFixtureIds)} — every club in every country, not
     * the caller's. The dashboard had already hidden the button, and the comment there already called
     * "simulate all results" an admin action; the route was the part that was missing.
     *
     * <p>The two routes that stay open are the ones that are genuinely the manager's own: {@code
     * /current-round/prepare} (his own match) and {@code /exhibition} (his own practice match).
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'DEV', 'ADMIN')")
    @PostMapping("/current-round/simulate-all")
    public ResponseEntity<Map<String, Object>> simulateCurrentRound(@AuthenticationPrincipal User user) {
        GameClock clock = seasonService.getOrCreateClock();
        int currentWeek = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int currentDay = clock.getCurrentDay() != null ? clock.getCurrentDay() : GameDay.FIRST;
        int seasonYear = clock.getCurrentSeason() != null ? clock.getCurrentSeason() : DEFAULT_SEASON_YEAR;

        List<MatchFixture> fixtures = matchFixtureRepository.findBySeasonYearAndWeekNumberAndDayNumber(seasonYear, currentWeek, currentDay).stream()
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
        // The user's own team entity, not a name resolved back to one - see resolveUserTeam.
        Team userTeam = resolveUserTeam(user);
        String userLeagueName = null;
        if (userTeam != null && userTeam.getId() != null) {
            // T-REST-0c: resolve the competition name through the repository, which has
            // @EntityGraph(attributePaths = {"competition"}) and therefore eagerly loads the
            // association. The old code read userTeam.getCompetition().getName() on a DETACHED
            // entity — resolveUserTeam returns user.getFootballTeam(), a lazy proxy whose
            // session is already closed by the time the name is read. That threw
            // LazyInitializationException for every manager, so simulate-all was a 500 for
            // everyone and no matchday could be played through this endpoint at all.
            userLeagueName = teamRepository.findById(userTeam.getId())
                    .map(t -> t.getCompetition() != null ? t.getCompetition().getName() : null)
                    .orElse(null);
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
            SimMatchService.SimMatchOutcome sim = simulateAndStore(fixture, isUserMatch(user, fixture));
            persistSimMatchToDB(fixture, sim);
            simulatedCount++;

            Map<String, Object> matchPayload = new LinkedHashMap<>();
            matchPayload.put("fixtureId", fixture.getId());
            matchPayload.put("homeTeam", fixture.getHomeTeam().getName());
            matchPayload.put("awayTeam", fixture.getAwayTeam().getName());
            matchPayload.put("homeGoals", sim.homeGoals());
            matchPayload.put("awayGoals", sim.awayGoals());
            matchPayload.put("isUserMatch", isUserMatch(user, fixture));
            matchPayload.put("events", List.of());
            matchPayload.put("played", true);
            matchPayload.put("matchId", sim.replayId());
            // The key above holds a REPLAY id. The honest name is beside it.
            matchPayload.put("replayId", sim.replayId());
            matchPayload.put("viewer_url", "/demo/service/ui/proposal/index.html?replayId=" + sim.replayId());

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
            // Reported while it runs and after it finishes. Without it, 148/154 leaves the owner to infer
            // that six fixtures failed - and those six are matches that will never be played.
            payload.put("backgroundFailed", asyncSimulationRunner.getFailedCount());
            payload.put("backgroundFailedIds", asyncSimulationRunner.getFailedIds());
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

    /**
     * A competition's id without opening its proxy.
     *
     * <p>{@code getId()} on a lazy proxy does not need a session when the id is already known, but calling
     * it on an uninitialised proxy in a detached graph is not something to rely on, so the id is taken
     * from the entity's own reference and compared as a value.
     */
    private static Long idOf(Competition competition) {
        return competition == null ? null : competition.getId();
    }

    /**
     * Advances the calendar a week, for everybody.
     *
     * <p><b>Administrators only, and the owner said so.</b> This route ends in
     * {@code gameClockService.advanceWeek()} — the same call the guarded {@code /api/game-clock/advance}
     * makes — so it moves every club in every country, not just the caller's. The dashboard had already
     * hidden the button from non-administrators, and that was the whole of the protection: the control was
     * invisible and the route was open.
     *
     * <p>{@code WorldAdvanceAuthorizationTest} was written against the {@code /api} surface, so this route
     * was simply not on its list. The guard is the same one the rest of the world-moving surface uses
     * ({@code APIController}'s {@code @PreAuthorize("hasAnyRole('OWNER', 'DEV', 'ADMIN', 'TEST')")}), and it is
     * deliberately the same role set: the owner must not be locked out of his own game.
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'DEV', 'ADMIN', 'TEST')")
    @PostMapping("/week/advance")
    public ResponseEntity<Map<String, Object>> advanceWeek(@AuthenticationPrincipal User user) {
        GameClock clock = seasonService.getOrCreateClock();
        int currentWeek = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int currentDay = clock.getCurrentDay() != null ? clock.getCurrentDay() : GameDay.FIRST;
        int seasonYear = clock.getCurrentSeason() != null ? clock.getCurrentSeason() : DEFAULT_SEASON_YEAR;

        // Only check user's league fixtures — other leagues can continue in background
        // The user's own team **id**, not a name resolved back to one - see resolveUserTeam.
        //
        // And the league name is read as a scalar inside SeasonService rather than by walking
        // userTeam.getCompetition() from here. This controller method has no transaction around it, so
        // that walk threw LazyInitializationException on Team#1 and Advance Week answered 500 while
        // doing nothing at all. See SeasonService.competitionNameOf.
        Long userTeamId = resolveUserTeamId(user);
        Long userLeagueId = seasonService.competitionIdOf(userTeamId);
        List<MatchFixture> allFixturesForWeek = matchFixtureRepository.findBySeasonYearAndWeekNumberAndDayNumber(seasonYear, currentWeek, currentDay);
        List<MatchFixture> userFixturesForWeek = userLeagueId != null
                ? allFixturesForWeek.stream()
                    // **The competition id, compared as a value.** This walked
                    // f.getCompetition().getName() on a lazy proxy from a method with no transaction —
                    // the same defect that killed the read two lines above it, and it only survived
                    // because the repository call happened to leave a session open. Two names compared by
                    // string is also two spellings of the same league waiting to disagree.
                    .filter(f -> f.getCompetition() != null
                            && Objects.equals(idOf(f.getCompetition()), userLeagueId))
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
            Team team = resolveUserTeam(user);
            if (team != null) {
                trainingProgressionService.runWeeklyTrainingIfDue(team.getId());
            }

            // The week is now seven day advances, nothing more. This used to call
            // advanceWeekAndHandleSeasonTransition, which did the week's work inline - injuries,
            // contracts, finance, training, youth, transfers - so Advance Week and Advance Day were
            // two different code paths and only one of them ran scheduled jobs. Everything that call
            // did is now a job, including the season rollover that used to hang off its tail.
            Map<String, Object> clockResult = gameClockService.advanceWeek();

            GameClock updatedClock = seasonService.getOrCreateClock();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("action", "WEEK_ADVANCED");
            payload.put("message", "Week advanced from " + currentWeek + " to " + updatedClock.getCurrentWeek() + ".");
            // The jobs that fired, so the advance is not a black box. This is how you confirm a
            // week of the season actually happened rather than merely the counter moving.
            payload.put("day", clockResult.get("day"));
            payload.put("dayLabel", clockResult.get("dayLabel"));
            payload.put("jobs", clockResult.get("jobs"));
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

    private SimMatchService.SimMatchOutcome simulateAndStore(MatchFixture fixture, boolean isUserMatch) {
        SimMatchService.SimMatchOutcome sim = simMatchService.simulate(fixture, isUserMatch);
        log.info("Sim match finished: {} {} - {} {} (replayId={})",
                fixture.getHomeTeam().getName(), sim.homeGoals(),
                fixture.getAwayTeam().getName(), sim.awayGoals(), sim.replayId());
        return sim;
    }

    private Long persistSimMatchToDB(MatchFixture fixture, SimMatchService.SimMatchOutcome sim) {
        long replayId = sim != null ? sim.replayId() : -1L;
        Long matchId = sim != null
                // The snapshots go with it, so the zone load can be read off the match that was played.
                ? simMatchService.persist(fixture, sim.outcome(), replayId, sim.snapshots())
                : simMatchService.persist(fixture, null, replayId, sim.snapshots());
        recordHowSubstitutionRulesWent(fixture, sim);
        rateClubsAfterAMatch(matchId);
        return matchId;
    }

    /**
     * Writes down how the manager's conditional rules actually went, once the match is over. (T1-16)
     *
     * <p>The engine has always assigned each rule a {@code VoidReason} when it could not fire, and
     * nothing has ever read it back — so a rule that quietly never fired was indistinguishable from a
     * rule that was never set. The manager's instruction to his coach could go unfulfilled with no
     * evidence anywhere, ever.
     *
     * <p>Separate from {@code simMatchService.persist} and in its own transaction, because it is a
     * courtesy record about an already-played match: if it fails, the result stands and the manager has
     * still got his match. Failing the save to also write the report would be the wrong trade.
     */
    private void recordHowSubstitutionRulesWent(MatchFixture fixture, SimMatchService.SimMatchOutcome sim) {
        if (fixture == null || sim == null || sim.ruleOutcomeJson() == null) return;
        try {
            var plans = substitutionPlanRepository.findByFixtureId(fixture.getId());
            if (plans.isEmpty()) return;
            var plan = plans.get();
            plan.setOutcomeJson(sim.ruleOutcomeJson());
            plan.setUpdatedAt(java.time.Instant.now());
            substitutionPlanRepository.save(plan);
        } catch (Exception e) {
            log.warn("Match {} was played but its substitution rule outcome could not be recorded: {}",
                    fixture.getId(), e.getMessage());
        }
    }

    /**
     * Rates the clubs after a single match, which is this path's batch of one.
     *
     * <p>A manager watching their own match should see their club's new rating and its +/- without
     * waiting for the next matchday or a restart, and this is the only place one match is played
     * outside the background runner. A replay rather than an increment, so it is harmless if something
     * else already moved the column; own transaction, so a failure here cannot cost the match that was
     * just saved.
     */
    private void rateClubsAfterAMatch(Long matchId) {
        try {
            ClubRatingService.Result rated = clubRatingService.recomputeDurably();
            log.info("Club Elo after match {}: {} match(es) replayed, {} club(s) rated, {} off their seed.",
                    matchId, rated.matchesReplayed(), rated.clubsRated(), rated.clubsMoved());
        } catch (RuntimeException e) {
            log.warn("Could not recompute club Elo after match {}: {}", matchId, e.getMessage());
        }
    }

    private PreparedMatchContext resolvePreparedMatch(@AuthenticationPrincipal User user) {
        // By IDENTITY, not by name (owner, 2026-10-07, P0-PREV-6).
        //
        // This read the user's team as a NAME, threw the entity away, and looked the team back up with
        // `findByName`. Two teams sharing a name - ordinary in this game, and exactly what the national
        // team and cup renames produced - and a manager's own club would be resolved to somebody else's,
        // so "play my match" would play a match belonging to another club, with no error anywhere.
        //
        // `user.getTifoCTeam()` IS the team, with its id. There was never a reason to round-trip it
        // through a string. The same lesson as `ZoxApiController.teamIdOf` in P0-PREV-5.
        Team team = resolveUserTeam(user);
        if (team == null || team.getId() == null) {
            return null;
        }

        GameClock clock = seasonService.getOrCreateClock();
        int currentWeek = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int currentDay = clock.getCurrentDay() != null ? clock.getCurrentDay() : GameDay.FIRST;
        int seasonYear = clock.getCurrentSeason() != null ? clock.getCurrentSeason() : DEFAULT_SEASON_YEAR;

        List<MatchFixture> fixtures = matchFixtureRepository.findBySeasonYearAndWeekNumberAndDayNumber(seasonYear, currentWeek, currentDay).stream()
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

    /**
     * The team a manager controls, by identity.
     *
     * <p>Was {@code resolveUserTeamName}, which returned a {@code String} and was then resolved back with
     * {@code findByName}. Nothing but the lookup stood between a manager and their own club, and a
     * duplicate name broke it silently.
     */
    /**
     * The football club this account manages, by identity.
     *
     * <p>This read the manager's club as a <b>name</b> and looked it back up with
     * {@code teamRepository.findFirstByNameOrderByIdAsc(...)} — at four call sites, including
     * {@link #isUserMatch}, which decides what "play my match" and the reveal button may act on. Two
     * clubs sharing a name meant a fixture belonging to somebody else reading as yours.
     *
     * <p>{@code User.footballTeam} is a <b>real foreign key to {@code Team}</b>, and its own javadoc
     * records that this exact name-join "produced four separate defects, all the same mistake in a
     * different costume". This controller was one of the readers still doing it.
     *
     * <p>The legacy {@code CTeam} name-join remains only as a fallback, because a legacy account row
     * predates the foreign key and reporting "no club" for an account that plainly has one would be worse
     * than a name lookup. That is the exception the field's own documentation asks for, and it is
     * labelled as one.
     */
    // Package-private, not private: the identity resolution is the thing P0-PREV-6 changed, and a test
    // that cannot call it can only assert on its own fixtures - which passes against broken code.
    Team resolveUserTeam(@AuthenticationPrincipal User user) {
        if (user == null) {
            return null;
        }
        if (user.getFootballTeam() != null && user.getFootballTeam().getId() != null) {
            return user.getFootballTeam();
        }
        // Legacy fallback only. `getCTeam()` is a footballtextmanager CTeam, a different model entirely,
        // so its id is not a football Team id and must never be compared with one.
        if (user.getCTeam() != null && user.getCTeam().getName() != null) {
            return teamRepository.findFirstByNameOrderByIdAsc(user.getCTeam().getName()).orElse(null);
        }
        return null;
    }

    private Map<String, Object> buildSingleMatchFeed(PreparedMatchContext context, SimMatchService.SimMatchOutcome sim) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("fixtureId", context.fixture().getId());
        match.put("homeTeam", context.homeName());
        match.put("awayTeam", context.awayName());
        match.put("homeGoals", sim.homeGoals());
        match.put("awayGoals", sim.awayGoals());
        match.put("events", List.of());
        match.put("isUserMatch", true);
        match.put("played", true);
        match.put("matchId", sim.replayId());
        // The key above holds a REPLAY id. The honest name is beside it.
        match.put("replayId", sim.replayId());
        match.put("viewer_url", "/demo/service/ui/proposal/index.html?replayId=" + sim.replayId());

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
        int currentDay = clock.getCurrentDay() != null ? clock.getCurrentDay() : GameDay.FIRST;
        int seasonYear = clock.getCurrentSeason() != null ? clock.getCurrentSeason() : DEFAULT_SEASON_YEAR;

        List<MatchFixture> fixtures = matchFixtureRepository.findBySeasonYearAndWeekNumberAndDayNumber(seasonYear, currentWeek, currentDay).stream()
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
        Team team = resolveUserTeam(user);
        if (team == null) return "League";
        // T-REST-0c: resolve through the repository, which eagerly loads competition via @EntityGraph.
        // The old code read team.getCompetition() on a detached entity and threw
        // LazyInitializationException for every manager.
        if (team.getId() != null) {
            String name = teamRepository.findById(team.getId())
                    .map(t -> t.getCompetition() != null ? t.getCompetition().getName() : null)
                    .orElse(null);
            if (name != null) return name;
        }
        return leagues.isEmpty() ? "League" : String.valueOf(leagues.get(0).get("leagueName"));
    }

    private boolean isUserLeague(User user, String leagueName) {
        Team team = resolveUserTeam(user);
        if (team == null || team.getId() == null) return false;
        // T-REST-0c: same detached-entity fix as resolveUserLeagueName.
        return teamRepository.findById(team.getId())
                .map(t -> t.getCompetition() != null && Objects.equals(t.getCompetition().getName(), leagueName))
                .orElse(false);
    }

    /**
     * Is this one of the manager's own fixtures?
     *
     * <p>Was {@code fixture.homeTeam.name.equals(userTeamName)} — the user's team as a name, compared
     * against both sides' names. This is the worst of them, because it decides what "play my match" and
     * the reveal button are allowed to act on: two clubs sharing a name meant a fixture belonging to
     * somebody else reading as yours.
     *
     * <p>By <b>id</b>, on both sides.
     */
    private boolean isUserMatch(User user, MatchFixture fixture) {
        Team team = resolveUserTeam(user);
        if (team == null || team.getId() == null
                || fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) {
            return false;
        }
        return Objects.equals(fixture.getHomeTeam().getId(), team.getId())
                || Objects.equals(fixture.getAwayTeam().getId(), team.getId());
    }

    private record PreparedMatchContext(String homeName, String awayName, MatchFixture fixture) {}
}
