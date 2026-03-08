package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.PlayerMovementDecisionService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.TacticsAdjustmentService;
import org.example.footballmanager.util.events.EventCreator;
import org.example.footballmanager.util.match.MatchContext;
import org.example.footballmanager.util.players.PlayerFactory;
import org.example.footballmanager.util.teams.TeamStrengthCalculator;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class MatchEngine {

    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final MatchEventRepository matchEventRepository;
    private final Random random = new Random();
    private final Set<Long> runningMatches = ConcurrentHashMap.newKeySet();
    private final MatchPlaybackEngine matchPlaybackEngine;
    private final PlayerMovementDecisionService movementService;
    private final GameClockRepository gameClockRepository;
    private final CompetitionRepository competitionRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerFactory playerFactory;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final EventCreator eventCreator;
    private final MatchStatisticEngine matchStatisticEngine;
    private final SeasonService seasonService;

    public Match loadAndValidateMatch(long matchId) {
        return matchRepository.findById(matchId).orElseThrow(() -> new RuntimeException("Match not found"));
    }
    private Lineup createLineupForMatch(Team team, List<Player> players, String formationName) {
        Lineup template = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(team.getId()).orElse(null);
        List<Long> preferredStarterIds = Optional.ofNullable(template)
                .map(Lineup::getOrderedStarterIds)
                .orElse(List.of());
        List<Long> preferredBenchIds = Optional.ofNullable(template)
                .map(Lineup::getOrderedBenchIds)
                .orElse(List.of());
        return createLineupForMatch(team, players, formationName, preferredStarterIds, preferredBenchIds);
    }

    private Lineup createLineupForMatch(Team team,
                                        List<Player> players,
                                        String formationName,
                                        List<Long> preferredStarterIds,
                                        List<Long> preferredBenchIds) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setFormation(formationName);

        List<Player> eligiblePlayers = players.stream()
                .filter(p -> !p.isInjured())
                .toList();
        List<Player> basePool = eligiblePlayers.isEmpty() ? players : eligiblePlayers;

        Map<Long, Player> byId = basePool.stream()
                .collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));

        List<Player> orderedStarters = new ArrayList<>();
        if (preferredStarterIds != null && !preferredStarterIds.isEmpty()) {
            preferredStarterIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .forEach(orderedStarters::add);
        }
        basePool.stream()
                .filter(p -> orderedStarters.stream().noneMatch(op -> Objects.equals(op.getId(), p.getId())))
                .forEach(orderedStarters::add);

        List<Player> managedStarting = orderedStarters.subList(0, Math.min(11, orderedStarters.size())).stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        List<Player> orderedBench = new ArrayList<>();
        if (preferredBenchIds != null && !preferredBenchIds.isEmpty()) {
            preferredBenchIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .filter(p -> managedStarting.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                    .forEach(orderedBench::add);
        }
        basePool.stream()
                .filter(p -> managedStarting.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                .filter(p -> orderedBench.stream().noneMatch(op -> Objects.equals(op.getId(), p.getId())))
                .forEach(orderedBench::add);
        List<Player> managedSubs = orderedBench.subList(0, Math.min(7, orderedBench.size())).stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        lineup.setStartingPlayers(managedStarting);
        lineup.setSubstitutes(managedSubs);
        lineup.setStarterOrderFromIds(managedStarting.stream().map(Player::getId).toList());
        lineup.setBenchOrderFromIds(managedSubs.stream().map(Player::getId).toList());
        return lineupRepository.save(lineup);
    }
    public Match createMatch(Team userTeam) {
        GameClock clock = seasonService.getOrCreateClock();
        int seasonYear = seasonService.getActiveSeasonYear();
        int week = seasonService.getCurrentWeek();

        Competition superLiga = competitionRepository.findById(1L)
                .orElseThrow(() -> new RuntimeException("Superliga not found"));

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(superLiga, seasonYear)
                .orElseThrow(() -> new RuntimeException("SeasonCompetition not found"));
        seasonService.ensureEntriesForSeasonCompetition(superLiga, seasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(superLiga, seasonYear);
        if (week == SeasonService.PLAYOFF_WEEK) {
            seasonService.ensurePlayoffWeekFixtures(superLiga, seasonYear);
        } else if (week == SeasonService.FRIENDLY_WEEK) {
            seasonService.ensureFriendlyWeekFixtures(superLiga, seasonYear);
        }

        List<CompetitionEntry> leagueEntries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> allTeamsInLeague = leagueEntries.stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
                .toList();

        if (allTeamsInLeague.size() < 2) {
            throw new RuntimeException("Not enough teams in league for match");
        }

        // Verify user's team is in the league
        if (!allTeamsInLeague.stream().anyMatch(t -> t.getId().equals(userTeam.getId()))) {
            throw new RuntimeException("User's team '" + userTeam.getName() + "' is not in the league");
        }

        // Find fixture for user's team in this week
        List<MatchFixture> weekFixtures = matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(
                superLiga.getId(), seasonYear, week
        );
        MatchFixture userFixture = weekFixtures.stream()
                .filter(f -> f.getHomeTeam() != null && f.getAwayTeam() != null)
                .filter(f -> Objects.equals(f.getHomeTeam().getId(), userTeam.getId()) || Objects.equals(f.getAwayTeam().getId(), userTeam.getId()))
                .findFirst()
                .orElse(null);

        Team homeTeam;
        Team awayTeam;
        Match match;
        if (userFixture != null) {
            // Use scheduled fixture
            boolean userTeamHome = Objects.equals(userFixture.getHomeTeam().getId(), userTeam.getId());
            homeTeam = userFixture.getHomeTeam();
            awayTeam = userFixture.getAwayTeam();
            match = new Match();
            match.setHomeTeam(homeTeam);
            match.setAwayTeam(awayTeam);
            match.setCompetition(superLiga);
            match.setSeasonYear(seasonYear);
            match.setRoundNumber(userFixture.getRoundNumber());
            match.setWeekNumber(userFixture.getWeekNumber());
            match.setMatchDate(userFixture.getMatchDate() != null ? userFixture.getMatchDate() : clock.getCurrentDate());
            log.info("Using scheduled fixture for user team '{}' - {}",
                    userTeam.getName(), userTeamHome ? "HOME" : "AWAY");
        } else {
            // No scheduled fixture - randomly pick opponent and home/away
            List<Team> possibleOpponents = allTeamsInLeague.stream()
                    .filter(t -> !t.getId().equals(userTeam.getId()))
                    .toList();
            if (possibleOpponents.isEmpty()) {
                throw new RuntimeException("No opponent available for team '" + userTeam.getName() + "'");
            }
            Team opponent = possibleOpponents.get(random.nextInt(possibleOpponents.size()));
            
            // Randomly assign user team to home or away (50/50)
            boolean userTeamHome = random.nextBoolean();
            homeTeam = userTeamHome ? userTeam : opponent;
            awayTeam = userTeamHome ? opponent : userTeam;
            
            match = new Match();
            match.setCompetition(superLiga);
            match.setSeasonYear(seasonYear);
            match.setRoundNumber(week);
            match.setWeekNumber(week);
            match.setMatchDate(clock.getCurrentDate());
            log.info("No scheduled fixture - randomly generated match for user team '{}' - {}",
                    userTeam.getName(), userTeamHome ? "HOME" : "AWAY");
        }

        log.info("Creating match: {} vs {}", homeTeam.getName(), awayTeam.getName());

        List<Player> homePlayers = playerRepository.findByTeam(homeTeam);
        List<Player> awayPlayers = playerRepository.findByTeam(awayTeam);

        if (homePlayers.isEmpty() || awayPlayers.isEmpty()) {
            log.warn("No players for team - populating...");
            if (homePlayers.isEmpty()) {
                playerFactory.createRandomTeamPlayers(homeTeam.getName(), homeTeam);
                homePlayers = playerRepository.findByTeam(homeTeam);
            }
            if (awayPlayers.isEmpty()) {
                playerFactory.createRandomTeamPlayers(awayTeam.getName(), awayTeam);
                awayPlayers = playerRepository.findByTeam(awayTeam);
            }
        }

        Lineup homeLineup = createLineupForMatch(homeTeam, homePlayers, "4-4-2");
        Lineup awayLineup = createLineupForMatch(awayTeam, awayPlayers, "4-2-3-1");

        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        if (match.getMatchDate() == null) {
            ZoneId zone = ZoneId.of("Europe/Belgrade");
            LocalDateTime currentCET = LocalDateTime.now(zone);
            match.setMatchDate(currentCET);
        }
        match.setCompetition(superLiga);
        match.setSeasonYear(seasonYear);
        if (match.getRoundNumber() == null) match.setRoundNumber(week);
        if (match.getWeekNumber() == null) match.setWeekNumber(week);

        match = matchRepository.save(match);

        log.info("Created match ID: {}, Home: {} ({}), Away: {}",
                match.getId(), match.getHomeTeam().getName(),
                match.getHomeTeam().getId().equals(userTeam.getId()) ? "user team" : "opponent",
                match.getAwayTeam().getName());

        return match;
    }
    public boolean startSimulationOnlyIfNotRunning(long matchId) {
        if (!runningMatches.add(matchId)) {
            log.info("Match {} is already being simulated!", matchId);
            return true;
        }
        return false;
    }
    public Tactics createHomeTactics(Match match) {
        Tactics tactics = new Tactics();
        Formation formation = new Formation();
        formation.setName(match.getHomeLineup().getFormation() != null ? match.getHomeLineup().getFormation() : "4-4-2");
        formation.setOffenseModifier(1.05);
        formation.setDefenseModifier(0.95);
        formation.setPossessionModifier(1.0);
        tactics.setFormation(formation);
        return tactics;
    }
    public Tactics createAwayTactics(Match match) {
        Tactics tactics = new Tactics();
        Formation formation = new Formation();
        formation.setName(match.getAwayLineup().getFormation() != null ? match.getAwayLineup().getFormation() : "4-2-3-1");
        formation.setOffenseModifier(1.1);
        formation.setDefenseModifier(0.98);
        formation.setPossessionModifier(1.05);
        tactics.setFormation(formation);
        return tactics;
    }
    public MatchRuntime simulateFullMatch(Match match) {
        MatchRuntime rt = new MatchRuntime();
        rt = matchPlaybackEngine.initializeRuntimeAndPositions(rt);
        rt.ticksPerMinute = 27;
        rt.homePlayers = new ArrayList<>(match.getHomeLineup().getOrderedStartingPlayers());
        rt.awayPlayers = new ArrayList<>(match.getAwayLineup().getOrderedStartingPlayers());
        rt.homeSquad = new ArrayList<>(match.getHomeLineup().getOrderedStartingPlayers());
        rt.awaySquad = new ArrayList<>(match.getAwayLineup().getOrderedStartingPlayers());
        List<Player> homeBench = new ArrayList<>(match.getHomeLineup().getOrderedSubstitutePlayers());
        List<Player> awayBench = new ArrayList<>(match.getAwayLineup().getOrderedSubstitutePlayers());
        rt.matchRef = match;
        rt.playerMinutes = new HashMap<>();
        rt.playerTeamSide = new HashMap<>();
        for (Player p : rt.homeSquad) {
            rt.playerMinutes.put(p.getId(), 90);
            rt.playerTeamSide.put(p.getId(), "HOME");
        }
        for (Player p : rt.awaySquad) {
            rt.playerMinutes.put(p.getId(), 90);
            rt.playerTeamSide.put(p.getId(), "AWAY");
        }

        rt.runtimeEvents = new ArrayList<>();
        rt.runtimeGoals = new ArrayList<>();
        rt.homeTactics = createHomeTactics(match);
        rt.awayTactics = createAwayTactics(match);
        rt.lastTouchTeam = rt.currentCarrier != null ? rt.currentCarrier.getTeam() : "HOME";
        rt.restartTeam = rt.lastTouchTeam;

        MatchContext context = new MatchContext(match, rt.crowd, rt.referee, rt.homeTactics, rt.awayTactics);

        // Match start event
        MatchStartEvent matchStartEvent = new MatchStartEvent();
        matchStartEvent.setMinute(1);
        matchStartEvent.setMatch(match);
        matchStartEvent.setHomeTeamName(match.getHomeTeam().getName());
        matchStartEvent.setAwayTeamName(match.getAwayTeam().getName());
        rt.runtimeEvents.add(matchStartEvent);

        // Track shot state across ticks
        PlayerMovementDecisionService.ShotOutcome pendingShotOutcome = null;
        int shotMinute = 0;
        Integer pendingShooterPositionId = null;
        String pendingShooterTeam = null;

        // ========== FULL-MATCH TICK SIMULATION LOOP ==========
        int totalTicks = 90 * rt.ticksPerMinute;
        for (int tick = 0; tick < totalTicks; tick++) {
            rt.tick = tick;
            int minute = Math.min(90, tick / rt.ticksPerMinute + 1);
            context.setCurrentMinute(minute);

            // 1. Handle active stoppages (countdown)
            if (rt.activeStoppage != null) {
                rt.stoppageTicks--;
                if (rt.stoppageTicks <= 0) {
                    rt.activeStoppage = null;
                    resetBallToPlay(rt);
                }
            }

            // 2. Player movement
            movementService.updatePositions(rt);

            // 3. Possession and carrier decisions (skip during stoppages/shots)
            boolean wasShooting = rt.isShooting;
            if (rt.activeStoppage == null) {
                movementService.handlePossessionAndActions(rt, random);
                if (rt.currentCarrier != null) {
                    rt.lastTouchTeam = rt.currentCarrier.getTeam();
                }
            }

            // 4. If a shot was just initiated, resolve outcome with DuelCalculator
            if (rt.isShooting && !wasShooting) {
                pendingShotOutcome = resolveShotWithDuel(rt, context);
                movementService.lastShotOutcome = pendingShotOutcome;
                shotMinute = minute;
                if (rt.currentCarrier != null) {
                    pendingShooterPositionId = rt.currentCarrier.getId();
                    pendingShooterTeam = rt.currentCarrier.getTeam();
                }
            }

            // 5. Update ball position (handles shot/rebound animation)
            boolean wasShootingBefore = rt.isShooting;
            movementService.updateBallPosition(rt);

            // 6. If shot just resolved, create the corresponding event
            if (wasShootingBefore && !rt.isShooting && pendingShotOutcome != null) {
                createShotEvent(
                        pendingShotOutcome,
                        rt,
                        match,
                        shotMinute,
                        pendingShooterPositionId,
                        pendingShooterTeam
                );
                pendingShotOutcome = null;
                pendingShooterPositionId = null;
                pendingShooterTeam = null;
            }

            // 7. Ball out of bounds detection (not during shot/rebound/pass/stoppage)
            if (!rt.isShooting && !rt.isRebounding && !rt.isPassing && rt.activeStoppage == null) {
                checkBallOutOfBounds(rt, match, minute);
            }

            // 8. Every simulated minute: fatigue, tactics, tackle duels
            if (tick % rt.ticksPerMinute == 0) {
                updateFatigue(context);
                updatePossession(context, rt.homePlayers, rt.awayPlayers,
                        rt.homeTactics.getFormation(), rt.awayTactics.getFormation());
                tacticsAdjustmentService.adjustTactics(context);
                checkTackleDuel(rt, match, context, minute);
                maybePerformTacticalSubstitution(rt, match, minute, homeBench, awayBench);
                maybeTriggerInjury(rt, match, minute, homeBench, awayBench);
            }

            // 9. Record tick state for replay
            rt.recordTick();
        }

        if (rt.homeGoals + rt.awayGoals == 0) {
            createFallbackGoal(rt, match);
        }

        MatchEndedEvent ended = new MatchEndedEvent();
        ended.setMinute(90);
        ended.setMatch(match);
        rt.runtimeEvents.add(ended);

        log.info("Engine finished match simulation. Events: {}", rt.runtimeEvents.size());
        return rt;
    }

    // ========== SHOT RESOLUTION VIA DUEL CALCULATOR ==========

    /** Resolve shot outcome using DuelCalculator: shooter vs GK */
    private PlayerMovementDecisionService.ShotOutcome resolveShotWithDuel(MatchRuntime rt, MatchContext context) {
        if (rt.currentCarrier == null) return PlayerMovementDecisionService.ShotOutcome.MISSED;

        Player shooter = findPlayerByPositionId(rt, rt.currentCarrier.getId());
        boolean attacksRight = rt.currentCarrier.getTeam().equals("HOME");
        double goalX = attacksRight ? 100.0 : 0.0;
        double distToGoal = Math.abs(rt.currentCarrier.getX() - goalX);
        String defendingTeam = attacksRight ? "AWAY" : "HOME";

        // Find the opponent goalkeeper
        Player goalkeeper = null;
        List<Player> defendingSquad = defendingTeam.equals("HOME") ? rt.homeSquad : rt.awaySquad;
        for (Player p : defendingSquad) {
            if (p.getPosition() == Position.GK) {
                goalkeeper = p;
                break;
            }
        }
        // Fallback: use first player if no GK found
        if (goalkeeper == null && !defendingSquad.isEmpty()) {
            goalkeeper = defendingSquad.getFirst();
        }
        if (shooter == null || goalkeeper == null) return PlayerMovementDecisionService.ShotOutcome.MISSED;

        DuelCalculator.DuelResult result = DuelCalculator.resolveDuel(
                shooter, goalkeeper, context, DuelCalculator.DuelType.SHOOTING);
        PlayerMovementDecisionService.ShotOutcome base = switch (result.getOutcome()) {
            case CLEAN -> PlayerMovementDecisionService.ShotOutcome.GOAL;
            case PARTIAL -> random.nextDouble() < 0.35
                    ? PlayerMovementDecisionService.ShotOutcome.GOAL
                    : PlayerMovementDecisionService.ShotOutcome.SAVED;
            case FAIL -> random.nextDouble() < 0.08
                    ? PlayerMovementDecisionService.ShotOutcome.SAVED
                    : PlayerMovementDecisionService.ShotOutcome.MISSED;
        };

        // Long shots should be much less efficient.
        if (distToGoal > 33) {
            if (base == PlayerMovementDecisionService.ShotOutcome.GOAL && random.nextDouble() < 0.88) {
                return random.nextDouble() < 0.55
                        ? PlayerMovementDecisionService.ShotOutcome.SAVED
                        : PlayerMovementDecisionService.ShotOutcome.MISSED;
            }
            if (base == PlayerMovementDecisionService.ShotOutcome.SAVED && random.nextDouble() < 0.35) {
                return PlayerMovementDecisionService.ShotOutcome.MISSED;
            }
        } else if (distToGoal > 26) {
            if (base == PlayerMovementDecisionService.ShotOutcome.GOAL && random.nextDouble() < 0.55) {
                return random.nextDouble() < 0.60
                        ? PlayerMovementDecisionService.ShotOutcome.SAVED
                        : PlayerMovementDecisionService.ShotOutcome.MISSED;
            }
        }
        return base;
    }

    /** Create the appropriate event after a shot resolves */
    private void createShotEvent(
            PlayerMovementDecisionService.ShotOutcome outcome,
            MatchRuntime rt,
            Match match,
            int minute,
            Integer shooterPositionId,
            String shooterTeam
    ) {
        if (shooterPositionId == null || shooterTeam == null) {
            return;
        }

        Player shooter = findPlayerByPositionId(rt, shooterPositionId);
        boolean isHome = "HOME".equals(shooterTeam);
        Team team = isHome ? match.getHomeTeam() : match.getAwayTeam();
        List<Player> teamPlayers = isHome ? rt.homeSquad : rt.awaySquad;

        switch (outcome) {
            case GOAL -> {
                GoalEvent goal = new GoalEvent();
                goal.setMinute(minute);
                goal.setMatch(match);
                goal.setTeam(team);
                goal.setScorer(shooter);
                // Find potential assistant (another outfield player on same team)
                teamPlayers.stream()
                        .filter(p -> p.getPosition() != Position.GK && !p.equals(shooter))
                        .skip(random.nextInt(Math.max(1, (int) teamPlayers.stream()
                                .filter(p -> p.getPosition() != Position.GK && !p.equals(shooter)).count())))
                        .findFirst()
                        .ifPresent(goal::setAssistant);
                goal.apply();
                processSpecialEvents(goal, rt, match);
                rt.runtimeEvents.add(goal);
                rt.runtimeGoals.add(goal);
                rt.kickoffFromCenter = true;
                rt.restartTeam = isHome ? "AWAY" : "HOME";

                // VAR review with 15% probability
                if (random.nextDouble() < 0.15) {
                    createVARReview(goal, null, rt, match, minute);
                } else {
                    // Goal celebration stoppage
                    rt.activeStoppage = MatchRuntime.StoppageType.GOAL_CELEBRATION;
                    rt.stoppageTicks = 10; // ~3 seconds
                }
            }
            case SAVED -> {
                ShotOnTargetEvent shot = new ShotOnTargetEvent();
                shot.setMinute(minute);
                shot.setMatch(match);
                shot.setTeam(team);
                shot.setShooter(shooter);
                rt.runtimeEvents.add(shot);
            }
            case MISSED -> {
                ShotOffTargetEvent shot = new ShotOffTargetEvent();
                shot.setMinute(minute);
                shot.setMatch(match);
                shot.setTeam(team);
                shot.setShooter(shooter);
                rt.runtimeEvents.add(shot);
            }
            default -> {}
        }
    }

    private void createFallbackGoal(MatchRuntime rt, Match match) {
        boolean homeScores = random.nextDouble() < 0.55;
        Team scoringTeam = homeScores ? match.getHomeTeam() : match.getAwayTeam();
        List<Player> scoringSquad = homeScores ? rt.homeSquad : rt.awaySquad;

        if (scoringSquad == null || scoringSquad.isEmpty()) {
            return;
        }

        List<Player> outfield = scoringSquad.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .toList();
        if (outfield.isEmpty()) {
            return;
        }

        Player scorer = outfield.get(random.nextInt(outfield.size()));

        GoalEvent fallbackGoal = new GoalEvent();
        fallbackGoal.setMinute(78 + random.nextInt(11)); // 78-88
        fallbackGoal.setMatch(match);
        fallbackGoal.setTeam(scoringTeam);
        fallbackGoal.setScorer(scorer);
        fallbackGoal.apply();

        outfield.stream()
                .filter(p -> !p.equals(scorer))
                .findFirst()
                .ifPresent(fallbackGoal::setAssistant);

        processSpecialEvents(fallbackGoal, rt, match);
        rt.runtimeEvents.add(fallbackGoal);
        rt.runtimeGoals.add(fallbackGoal);
        rt.kickoffFromCenter = true;
        rt.restartTeam = homeScores ? "AWAY" : "HOME";
        log.info("Fallback goal injected for demo match {} to avoid scoreless simulation.", match.getId());
    }

    // ========== BALL OUT OF BOUNDS ==========

    private void checkBallOutOfBounds(MatchRuntime rt, Match match, int minute) {
        double bx = rt.ball.getX();
        double by = rt.ball.getY();

        if (by <= 0 || by >= 100) {
            rt.ball.setY(by <= 0 ? 1 : 99);
            switchPossession(rt);
            rt.restartTeam = rt.currentCarrier != null ? rt.currentCarrier.getTeam() : rt.restartTeam;

            Team throwInTeam = "HOME".equals(rt.restartTeam) ? match.getHomeTeam() : match.getAwayTeam();
            List<Player> throwInSquad = "HOME".equals(rt.restartTeam) ? rt.homeSquad : rt.awaySquad;
            Player taker = throwInSquad.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .findFirst()
                    .orElse(throwInSquad.isEmpty() ? null : throwInSquad.getFirst());

            ThrowInEvent throwIn = new ThrowInEvent();
            throwIn.setMinute(minute);
            throwIn.setMatch(match);
            throwIn.setTeam(throwInTeam);
            throwIn.setTaker(taker);
            rt.runtimeEvents.add(throwIn);

            rt.activeStoppage = MatchRuntime.StoppageType.THROW_IN;
            rt.stoppageTicks = 5;
            return;
        }

        if (bx > 0 && bx < 100) {
            return;
        }

        boolean ballWentRight = bx >= 100;
        boolean lastTouchHome = "HOME".equals(rt.lastTouchTeam);
        boolean isCorner = (ballWentRight && !lastTouchHome) || (!ballWentRight && lastTouchHome);

        if (isCorner) {
            Team attackingTeam = lastTouchHome ? match.getAwayTeam() : match.getHomeTeam();
            List<Player> attackingSquad = lastTouchHome ? rt.awaySquad : rt.homeSquad;
            Player taker = attackingSquad.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .findFirst()
                    .orElse(attackingSquad.isEmpty() ? null : attackingSquad.getFirst());

            CornerEvent corner = new CornerEvent();
            corner.setMinute(minute);
            corner.setMatch(match);
            corner.setTeam(attackingTeam);
            corner.setPlayer(taker);
            rt.runtimeEvents.add(corner);

            rt.restartTeam = attackingTeam.equals(match.getHomeTeam()) ? "HOME" : "AWAY";
            rt.ball.setX(ballWentRight ? 99 : 1);
            rt.ball.setY(by < 50 ? 1 : 99);
            rt.activeStoppage = MatchRuntime.StoppageType.CORNER;
            rt.stoppageTicks = 8;
            return;
        }

        rt.ball.setX(ballWentRight ? 94 : 6);
        rt.ball.setY(50);
        rt.activeStoppage = MatchRuntime.StoppageType.GOAL_KICK;
        rt.stoppageTicks = 5;
        switchPossession(rt);
        rt.restartTeam = rt.currentCarrier != null ? rt.currentCarrier.getTeam() : rt.restartTeam;

        Team goalKickTeam = "HOME".equals(rt.restartTeam) ? match.getHomeTeam() : match.getAwayTeam();
        List<Player> goalKickSquad = "HOME".equals(rt.restartTeam) ? rt.homeSquad : rt.awaySquad;
        Player goalkeeper = goalKickSquad.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .findFirst()
                .orElse(goalKickSquad.isEmpty() ? null : goalKickSquad.getFirst());

        GoalKickEvent goalKick = new GoalKickEvent();
        goalKick.setMinute(minute);
        goalKick.setMatch(match);
        goalKick.setTeam(goalKickTeam);
        goalKick.setGoalkeeper(goalkeeper);
        rt.runtimeEvents.add(goalKick);
    }

    private void checkTackleDuel(MatchRuntime rt, Match match, MatchContext context, int minute) {
        if (rt.currentCarrier == null || rt.activeStoppage != null || rt.isPassing) return;
        // Keep minute-level tackle events only when pressure is actually close.
        if (random.nextDouble() > 0.18) return;

        Player carrier = findPlayerByPositionId(rt, rt.currentCarrier.getId());
        boolean isHome = rt.currentCarrier.getTeam().equals("HOME");
        List<Player> defendingSquad = isHome ? rt.awaySquad : rt.homeSquad;
        Team defendingTeam = isHome ? match.getAwayTeam() : match.getHomeTeam();

        // Pick a random defender from the opposing squad
        List<Player> defenders = defendingSquad.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .toList();
        if (defenders.isEmpty() || carrier == null) return;
        PlayerPositionDTO nearestDefenderPos = rt.players.stream()
                .filter(p -> !p.getTeam().equals(rt.currentCarrier.getTeam()))
                .min(Comparator.comparingDouble(p ->
                        Math.hypot(p.getX() - rt.currentCarrier.getX(), p.getY() - rt.currentCarrier.getY())))
                .orElse(null);
        if (nearestDefenderPos == null) return;
        double defenderDistance = Math.hypot(nearestDefenderPos.getX() - rt.currentCarrier.getX(),
                nearestDefenderPos.getY() - rt.currentCarrier.getY());
        if (defenderDistance > 4.2) return;

        Player defender = findPlayerByPositionId(rt, nearestDefenderPos.getId());
        if (defender == null) {
            defender = defenders.get(random.nextInt(defenders.size()));
        }

        DuelCalculator.DuelResult result = DuelCalculator.resolveDuel(
                carrier, defender, context, DuelCalculator.DuelType.TACKLE);

        if (result.isFoulOccurred()) {
            Team fouledTeam = isHome ? match.getHomeTeam() : match.getAwayTeam();
            boolean attacksRight = isHome;
            boolean inPenaltyBox = isInPenaltyBox(rt.currentCarrier, attacksRight);

            if (inPenaltyBox) {
                PenaltyEvent penalty = new PenaltyEvent();
                penalty.setMinute(minute);
                penalty.setMatch(match);
                penalty.setTeam(fouledTeam);
                penalty.setTaker(carrier);
                boolean scoredPenaltyGoal = false;

                Player goalkeeper = findGoalkeeper(isHome ? rt.awaySquad : rt.homeSquad);
                DuelCalculator.DuelResult penResult = DuelCalculator.resolveDuel(
                        carrier,
                        goalkeeper,
                        context,
                        DuelCalculator.DuelType.SHOOTING
                );

                boolean scored = penResult.getOutcome() == DuelCalculator.DuelOutcomeQuality.CLEAN;
                penalty.setScored(scored);
                rt.runtimeEvents.add(penalty);

                if (scored) {
                    GoalEvent goal = new GoalEvent();
                    goal.setMinute(minute);
                    goal.setMatch(match);
                    goal.setTeam(fouledTeam);
                    goal.setScorer(carrier);
                    goal.apply();
                    processSpecialEvents(goal, rt, match);
                    rt.runtimeEvents.add(goal);
                    rt.runtimeGoals.add(goal);
                    rt.kickoffFromCenter = true;
                    rt.restartTeam = isHome ? "AWAY" : "HOME";
                    scoredPenaltyGoal = true;

                    if (random.nextDouble() < 0.20) {
                        createVARReview(goal, penalty, rt, match, minute);
                    }
                } else if (random.nextDouble() < 0.20) {
                    createVARReview(null, penalty, rt, match, minute);
                }

                rt.activeStoppage = MatchRuntime.StoppageType.PENALTY;
                rt.stoppageTicks = 10;
                if (!scoredPenaltyGoal) {
                    rt.restartTeam = isHome ? "HOME" : "AWAY";
                }
            } else {
                FreeKickEvent fk = new FreeKickEvent();
                fk.setMinute(minute);
                fk.setMatch(match);
                fk.setTeam(fouledTeam);
                fk.setTaker(carrier);
                fk.setPlayer(carrier);
                rt.runtimeEvents.add(fk);
                rt.activeStoppage = MatchRuntime.StoppageType.FREE_KICK;
                rt.stoppageTicks = 6;
                rt.restartTeam = isHome ? "HOME" : "AWAY";
            }

            if (result.getCardHint() == DuelCalculator.CardHint.YELLOW) {
                YellowCardEvent yc = new YellowCardEvent();
                yc.setMinute(minute);
                yc.setMatch(match);
                yc.setTeam(defendingTeam);
                yc.setPlayer(defender);
                rt.runtimeEvents.add(yc);
            } else if (result.getCardHint() == DuelCalculator.CardHint.RED) {
                RedCardEvent rc = new RedCardEvent();
                rc.setMinute(minute);
                rc.setMatch(match);
                rc.setTeam(defendingTeam);
                rc.setPlayer(defender);
                rt.runtimeEvents.add(rc);
            }
        } else if (result.getOutcome() == DuelCalculator.DuelOutcomeQuality.FAIL) {
            // Carrier lost the ball: switch possession.
            switchPossession(rt);
        }
    }

    private void maybePerformTacticalSubstitution(MatchRuntime rt,
                                                  Match match,
                                                  int minute,
                                                  List<Player> homeBench,
                                                  List<Player> awayBench) {
        if (minute < 55 || minute > 85) return;

        if (rt.homeSubstitutionsUsed < 3 && !homeBench.isEmpty() && random.nextDouble() < 0.08) {
            Player out = pickMostFatigued(rt.homeSquad);
            if (out != null) {
                Player in = pickReplacementFor(homeBench, out.getPosition());
                if (in != null) {
                    applySubstitution(rt, match, "HOME", out, in, minute, homeBench);
                }
            }
        }

        if (rt.awaySubstitutionsUsed < 3 && !awayBench.isEmpty() && random.nextDouble() < 0.08) {
            Player out = pickMostFatigued(rt.awaySquad);
            if (out != null) {
                Player in = pickReplacementFor(awayBench, out.getPosition());
                if (in != null) {
                    applySubstitution(rt, match, "AWAY", out, in, minute, awayBench);
                }
            }
        }
    }

    private void maybeTriggerInjury(MatchRuntime rt,
                                    Match match,
                                    int minute,
                                    List<Player> homeBench,
                                    List<Player> awayBench) {
        if (minute < 8 || minute > 88) return;

        double injuryChancePerMinute = 0.0022;
        if (rt.homeSubstitutionsUsed < 3 && random.nextDouble() < injuryChancePerMinute) {
            Player injured = randomOutfield(rt.homeSquad);
            if (injured != null) {
                applyInjury(rt, match, injured, minute);
                Player replacement = pickReplacementFor(homeBench, injured.getPosition());
                if (replacement != null) {
                    applySubstitution(rt, match, "HOME", injured, replacement, minute, homeBench);
                }
            }
        }

        if (rt.awaySubstitutionsUsed < 3 && random.nextDouble() < injuryChancePerMinute) {
            Player injured = randomOutfield(rt.awaySquad);
            if (injured != null) {
                applyInjury(rt, match, injured, minute);
                Player replacement = pickReplacementFor(awayBench, injured.getPosition());
                if (replacement != null) {
                    applySubstitution(rt, match, "AWAY", injured, replacement, minute, awayBench);
                }
            }
        }
    }

    private void applyInjury(MatchRuntime rt, Match match, Player injured, int minute) {
        int days = rollInjuryDays();
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();

        injured.setInjuryDaysRemaining(days);
        injured.setInjurySeasonNumber(season);
        injured.setInjuryWeekNumber(week);
        playerRepository.save(injured);

        InjuryEvent injuryEvent = new InjuryEvent();
        injuryEvent.setMinute(minute);
        injuryEvent.setMatch(match);
        injuryEvent.setPlayer(injured);
        rt.runtimeEvents.add(injuryEvent);
    }

    private int rollInjuryDays() {
        double roll = random.nextDouble();
        if (roll < 0.72) return random.nextInt(10) + 1;
        if (roll < 0.95) return random.nextInt(6) + 11;
        return random.nextInt(4) + 17;
    }

    private Player pickMostFatigued(List<Player> squad) {
        return squad.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .max(Comparator.comparingDouble(p -> p.getSkills() != null ? p.getSkills().getFatigue() : 0.0))
                .orElse(null);
    }

    private Player randomOutfield(List<Player> squad) {
        List<Player> candidates = squad.stream().filter(p -> p.getPosition() != Position.GK).toList();
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private Player pickReplacementFor(List<Player> bench, Position targetPosition) {
        if (bench == null || bench.isEmpty()) return null;
        Player exact = bench.stream()
                .filter(p -> p.getPosition() == targetPosition)
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }
        // Never send a goalkeeper into an outfield slot.
        if (targetPosition != Position.GK) {
            return bench.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .findFirst()
                    .orElse(null);
        }
        return bench.getFirst();
    }

    private void applySubstitution(MatchRuntime rt,
                                   Match match,
                                   String side,
                                   Player playerOut,
                                   Player playerIn,
                                   int minute,
                                   List<Player> bench) {
        if (playerOut == null || playerIn == null) return;
        if ("HOME".equals(side)) {
            if (rt.homeSubstitutionsUsed >= 3) return;
            rt.homeSubstitutionsUsed++;
            rt.homeSquad = rt.homeSquad.stream()
                    .map(p -> Objects.equals(p.getId(), playerOut.getId()) ? playerIn : p)
                    .collect(Collectors.toCollection(ArrayList::new));
            rt.homePlayers = rt.homePlayers.stream()
                    .map(p -> Objects.equals(p.getId(), playerOut.getId()) ? playerIn : p)
                    .collect(Collectors.toCollection(ArrayList::new));
        } else {
            if (rt.awaySubstitutionsUsed >= 3) return;
            rt.awaySubstitutionsUsed++;
            rt.awaySquad = rt.awaySquad.stream()
                    .map(p -> Objects.equals(p.getId(), playerOut.getId()) ? playerIn : p)
                    .collect(Collectors.toCollection(ArrayList::new));
            rt.awayPlayers = rt.awayPlayers.stream()
                    .map(p -> Objects.equals(p.getId(), playerOut.getId()) ? playerIn : p)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (bench != null) {
            bench.removeIf(p -> Objects.equals(p.getId(), playerIn.getId()));
        }

        rt.playerMinutes.put(playerOut.getId(), Math.max(1, minute));
        rt.playerMinutes.put(playerIn.getId(), Math.max(0, 91 - minute));
        rt.playerTeamSide.put(playerIn.getId(), side);

        SubstitutionEvent substitutionEvent = new SubstitutionEvent();
        substitutionEvent.setMinute(minute);
        substitutionEvent.setMatch(match);
        substitutionEvent.setPlayerOut(playerOut);
        substitutionEvent.setPlayerIn(playerIn);
        substitutionEvent.setTeam("HOME".equals(side) ? match.getHomeTeam() : match.getAwayTeam());
        rt.runtimeEvents.add(substitutionEvent);
    }

    // ========== VAR REVIEW ==========

    private void createVARReview(GoalEvent goal, PenaltyEvent penalty, MatchRuntime rt, Match match, int minute) {
        VARReviewEvent var = new VARReviewEvent();
        var.setMinute(minute);
        var.setMatch(match);
        rt.activeStoppage = MatchRuntime.StoppageType.VAR_REVIEW;
        rt.stoppageTicks = 8; // ~2.4 seconds of review

        if (goal != null) {
            var.setReviewedGoalEvent(goal);
            var.setNumber(1);
            // 80% confirmed, 20% overturned
            if (random.nextDouble() < 0.80) {
                var.setDecision("Confirmed");
            } else {
                var.setDecision("Overturned");
                var.setOverturnReason(randomOverturnReason());
                // Reverse the goal
                if (goal.getTeam().equals(match.getHomeTeam())) {
                    rt.homeGoals--;
                } else {
                    rt.awayGoals--;
                }
                goal.setScored(false);
                rt.runtimeGoals.remove(goal);
                rt.kickoffFromCenter = false;
            }
        } else if (penalty != null) {
            var.setReviewedPenaltyEvent(penalty);
            var.setNumber(2);
            if (random.nextDouble() < 0.80) {
                var.setDecision("Confirmed");
            } else {
                var.setDecision("Overturned");
                var.setOverturnReason("encroachment");
            }
        }
        rt.runtimeEvents.add(var);
    }

    private String randomOverturnReason() {
        double roll = random.nextDouble();
        if (roll < 0.45) return "offside";
        if (roll < 0.80) return "foul in build-up";
        return "handball";
    }

    // ========== HELPER METHODS ==========

    /** Map a PlayerPositionDTO id (1-22) to the actual JPA Player entity */
    private Player findPlayerByPositionId(MatchRuntime rt, int positionId) {
        if (positionId >= 1 && positionId <= 11 && positionId <= rt.homeSquad.size()) {
            return rt.homeSquad.get(positionId - 1);
        } else if (positionId >= 12 && positionId <= 22 && (positionId - 12) < rt.awaySquad.size()) {
            return rt.awaySquad.get(positionId - 12);
        }
        return null;
    }

    /** Switch ball possession to the nearest player of the other team */
    private void switchPossession(MatchRuntime rt) {
        if (rt.currentCarrier == null) return;
        String otherTeam = rt.currentCarrier.getTeam().equals("HOME") ? "AWAY" : "HOME";
        rt.currentCarrier = rt.players.stream()
                .filter(p -> p.getTeam().equals(otherTeam))
                .min(Comparator.comparingDouble(p ->
                        Math.hypot(p.getX() - rt.ball.getX(), p.getY() - rt.ball.getY())))
                .orElse(rt.currentCarrier);
        rt.lastTouchTeam = otherTeam;
    }

    /** Reset ball to a playable position after a stoppage ends */
    private void resetBallToPlay(MatchRuntime rt) {
        if (rt.kickoffFromCenter) {
            rt.ball.setX(50);
            rt.ball.setY(50);
            String teamForKickoff = rt.restartTeam != null ? rt.restartTeam : "HOME";
            rt.currentCarrier = rt.players.stream()
                    .filter(p -> teamForKickoff.equals(p.getTeam()))
                    .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - 50, p.getY() - 50)))
                    .orElse(rt.currentCarrier);
            if (rt.currentCarrier != null) {
                rt.currentCarrier.setX(50);
                rt.currentCarrier.setY(50);
                rt.lastTouchTeam = rt.currentCarrier.getTeam();
            }
            rt.kickoffFromCenter = false;
            return;
        }

        if (rt.currentCarrier != null) {
            rt.ball.setX(rt.currentCarrier.getX());
            rt.ball.setY(rt.currentCarrier.getY());
            rt.lastTouchTeam = rt.currentCarrier.getTeam();
            return;
        }

        rt.currentCarrier = rt.players.stream()
                .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - rt.ball.getX(), p.getY() - rt.ball.getY())))
                .orElse(null);
        if (rt.currentCarrier != null) {
            rt.lastTouchTeam = rt.currentCarrier.getTeam();
        }
    }

    private Player findGoalkeeper(List<Player> squad) {
        if (squad == null || squad.isEmpty()) {
            return null;
        }
        return squad.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .findFirst()
                .orElse(squad.getFirst());
    }

    private boolean isInPenaltyBox(org.example.footballmanager.dto.PlayerPositionDTO carrier, boolean attacksRight) {
        if (carrier == null) {
            return false;
        }
        if (attacksRight) {
            return carrier.getX() >= 84 && carrier.getY() >= 22 && carrier.getY() <= 78;
        }
        return carrier.getX() <= 16 && carrier.getY() >= 22 && carrier.getY() <= 78;
    }
    public void simulateRestOfMatchDay(Competition league, Season season, Team alreadyPlayedHome, Team alreadyPlayedAway) {
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow();

        GameClock clock = gameClockRepository.findById(1L).orElseThrow();

        int currentWeek = seasonService.getCurrentWeek();
        if (currentWeek == SeasonService.PLAYOFF_WEEK) {
            seasonService.ensurePlayoffWeekFixtures(league, season.getSeasonYear());
        } else if (currentWeek == SeasonService.FRIENDLY_WEEK) {
            seasonService.ensureFriendlyWeekFixtures(league, season.getSeasonYear());
        }
        List<MatchFixture> roundFixtures = matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(
                league.getId(), season.getSeasonYear(), currentWeek
        );

        for (MatchFixture fixture : roundFixtures) {
            Team home = fixture.getHomeTeam();
            Team away = fixture.getAwayTeam();
            if (home == null || away == null) continue;
            if (alreadyPlayedHome != null && alreadyPlayedAway != null) {
                boolean isUserFixture = (Objects.equals(home.getId(), alreadyPlayedHome.getId()) && Objects.equals(away.getId(), alreadyPlayedAway.getId()))
                        || (Objects.equals(home.getId(), alreadyPlayedAway.getId()) && Objects.equals(away.getId(), alreadyPlayedHome.getId()));
                if (isUserFixture) continue;
            }

            int homeGoals = random.nextInt(6);
            int awayGoals = random.nextInt(6);
            Match simulatedMatch = new Match();
            simulatedMatch.setHomeTeam(home);
            simulatedMatch.setAwayTeam(away);
            simulatedMatch.setCompetition(league);
            simulatedMatch.setSeasonYear(season.getSeasonYear());
            simulatedMatch.setRoundNumber(fixture.getRoundNumber());
            simulatedMatch.setWeekNumber(fixture.getWeekNumber());
            simulatedMatch.setMatchDate(fixture.getMatchDate() != null ? fixture.getMatchDate() : clock.getCurrentDate());
            simulatedMatch.setHomeGoals(homeGoals);
            simulatedMatch.setAwayGoals(awayGoals);
            simulatedMatch.setPlayed(true);
            simulatedMatch.setStarted(true);

            List<Player> homePlayers = playerRepository.findByTeam(home);
            List<Player> awayPlayers = playerRepository.findByTeam(away);
            if (homePlayers.isEmpty()) {
                playerFactory.createRandomTeamPlayers(home.getName(), home);
                homePlayers = playerRepository.findByTeam(home);
            }
            if (awayPlayers.isEmpty()) {
                playerFactory.createRandomTeamPlayers(away.getName(), away);
                awayPlayers = playerRepository.findByTeam(away);
            }

            Lineup homeLineup = createLineupForMatch(home, homePlayers, "4-4-2");
            Lineup awayLineup = createLineupForMatch(away, awayPlayers, "4-4-2");
            simulatedMatch.setHomeLineup(homeLineup);
            simulatedMatch.setAwayLineup(awayLineup);
            simulatedMatch.setHomeFormation("4-4-2");
            simulatedMatch.setAwayFormation("4-4-2");
            simulatedMatch = matchRepository.save(simulatedMatch);
            fixture.setPlayed(true);
            fixture.setPlayedMatch(simulatedMatch);
            matchFixtureRepository.save(fixture);

            generateSimulatedMatchEvents(simulatedMatch, homeGoals, awayGoals);
            List<GoalEvent> goals = matchEventRepository.findGoalsByMatch(simulatedMatch);
            List<YellowCardEvent> yellows = matchEventRepository.findYellowCardsByMatch(simulatedMatch);
            List<RedCardEvent> reds = matchEventRepository.findRedCardsByMatch(simulatedMatch);

            List<Player> ratedHome = matchStatisticEngine.assignRatings(new ArrayList<>(homeLineup.getStartingPlayers()), goals);
            List<Player> ratedAway = matchStatisticEngine.assignRatings(new ArrayList<>(awayLineup.getStartingPlayers()), goals);
            Map<Long, Integer> defaultMinutes = new HashMap<>();
            ratedHome.forEach(p -> defaultMinutes.put(p.getId(), 90));
            ratedAway.forEach(p -> defaultMinutes.put(p.getId(), 90));
            matchStatisticEngine.savePlayerStats(simulatedMatch, ratedHome, goals, yellows, reds, defaultMinutes);
            matchStatisticEngine.savePlayerStats(simulatedMatch, ratedAway, goals, yellows, reds, defaultMinutes);

            CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home)
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("Team " + home.getName() + " is not in the league"));

            CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("Team " + away.getName() + " is not in the league"));

            homeEntry.setPoints(homeEntry.getPoints() + (homeGoals > awayGoals ? 3 : homeGoals == awayGoals ? 1 : 0));
            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);

            awayEntry.setPoints(awayEntry.getPoints() + (awayGoals > homeGoals ? 3 : awayGoals == homeGoals ? 1 : 0));
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

            homeEntry.setWins((homeEntry.getWins() != null ? homeEntry.getWins() : 0) + (homeGoals > awayGoals ? 1 : 0));
            homeEntry.setDraws((homeEntry.getDraws() != null ? homeEntry.getDraws() : 0) + (homeGoals == awayGoals ? 1 : 0));
            homeEntry.setLosses((homeEntry.getLosses() != null ? homeEntry.getLosses() : 0) + (homeGoals < awayGoals ? 1 : 0));

            awayEntry.setWins((awayEntry.getWins() != null ? awayEntry.getWins() : 0) + (awayGoals > homeGoals ? 1 : 0));
            awayEntry.setDraws((awayEntry.getDraws() != null ? awayEntry.getDraws() : 0) + (awayGoals == homeGoals ? 1 : 0));
            awayEntry.setLosses((awayEntry.getLosses() != null ? awayEntry.getLosses() : 0) + (awayGoals < homeGoals ? 1 : 0));

            competitionEntryRepository.save(homeEntry);
            competitionEntryRepository.save(awayEntry);

            log.info("Simulated match: {} {}:{} {} | Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                    home.getName(), homeGoals, awayGoals, away.getName(),
                    homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                    awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
        }
        log.info("Round finished - standings updated for league {}", league.getName());
    }
    private void processSpecialEvents(MatchEvent event, MatchRuntime rt, Match match) {

        if (event instanceof GoalEvent goal) {
            goal.setMatch(match);
            if (goal.getTeam().equals(match.getHomeTeam())) {
                rt.homeGoals++;
            } else {
                rt.awayGoals++;
            }
            goal.setScoreAfterGoal(rt.homeGoals + ":" + rt.awayGoals);
           // rt.runtimeGoals.add(goal);
        }
    }
    private void updateFatigue(MatchContext context) {
        context.setFatigueFactor(
                Math.max(0.7, context.getFatigueFactor() - 0.002));
    }
    private void updatePossession(MatchContext context, List<Player> homePlayers, List<Player> awayPlayers, Formation homeFormation, Formation awayFormation) {

        double homeStrength =
                TeamStrengthCalculator.calculateTeamStrength(
                        homePlayers,
                        homeFormation,
                        context.getHomeTactics(),
                        true);

        double awayStrength =
                TeamStrengthCalculator.calculateTeamStrength(
                        awayPlayers,
                        awayFormation,
                        context.getAwayTactics(),
                        false);

        double total = homeStrength + awayStrength;

        if (random.nextDouble() < homeStrength / total) {
            context.setPossessionTeam(context.getMatch().getHomeTeam());
        } else {
            context.setPossessionTeam(context.getMatch().getAwayTeam());
        }
    }
    public void simulateSingleMatch(Team home, Team away, SeasonCompetition sc, GameClock clock) {

        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setMatchDate(clock.getCurrentDate());

        Random rnd = new Random();
        int homeGoals = rnd.nextInt(6);
        int awayGoals = rnd.nextInt(6);

        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);

        matchRepository.save(match);
        generateSimulatedMatchEvents(match, homeGoals, awayGoals);

        CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home)
                .orElseThrow(() -> new RuntimeException("Home team is not in league: " + home.getName()));

        CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                .orElseThrow(() -> new RuntimeException("Away team not in a league: " + away.getName()));

        if (homeGoals > awayGoals) {
            homeEntry.setPoints(homeEntry.getPoints() + 3);
            homeEntry.setWins(homeEntry.getWins() + 1);
        } else if (homeGoals == awayGoals) {
            homeEntry.setPoints(homeEntry.getPoints() + 1);
            awayEntry.setPoints(awayEntry.getPoints() + 1);
            homeEntry.setDraws(homeEntry.getDraws() + 1);
            awayEntry.setDraws(awayEntry.getDraws() + 1);
        } else {
            awayEntry.setPoints(awayEntry.getPoints() + 3);
            awayEntry.setWins(awayEntry.getWins() + 1);
        }

        homeEntry.setLosses(homeEntry.getLosses() + (homeGoals < awayGoals ? 1 : 0));
        awayEntry.setLosses(awayEntry.getLosses() + (awayGoals < homeGoals ? 1 : 0));

        homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
        homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);
        awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
        awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

        competitionEntryRepository.save(homeEntry);
        competitionEntryRepository.save(awayEntry);

        log.info("Simulated match: {} {}:{} {} | Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                home.getName(), homeGoals, awayGoals, away.getName(),
                homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
    }
    public void generateSimulatedMatchEvents(Match simulatedMatch, int homeGoals, int awayGoals) {
        Team home = simulatedMatch.getHomeTeam();
        Team away = simulatedMatch.getAwayTeam();

        List<Player> homePlayers = playerRepository.findByTeam(home);
        List<Player> awayPlayers = playerRepository.findByTeam(away);

        if (homePlayers.isEmpty() || awayPlayers.isEmpty()) {
            log.warn("No players for event generation - team: {}", home.getName());
            return;
        }
        Random rnd = new Random();
        int remainingHomeGoals = homeGoals;
        int remainingAwayGoals = awayGoals;
        int lastMinute = 0;
        while (remainingHomeGoals > 0 || remainingAwayGoals > 0) {
            boolean isHomeGoal;
            if (remainingHomeGoals == 0) {
                isHomeGoal = false;
            } else if (remainingAwayGoals == 0) {
                isHomeGoal = true;
            } else {
                double homeChance = (double) remainingHomeGoals / (remainingHomeGoals + remainingAwayGoals);
                isHomeGoal = rnd.nextDouble() < (homeChance + 0.1); // +10% home bias when goals are equal
            }

            Team scoringTeam = isHomeGoal ? home : away;
            List<Player> scoringPlayers = isHomeGoal ? homePlayers : awayPlayers;
            List<Player> opponentPlayers = isHomeGoal ? awayPlayers : homePlayers;

            GoalEvent goal = eventCreator.createRandomGoalEventForSimulateMatch(simulatedMatch, scoringTeam, scoringPlayers, opponentPlayers, rnd);

            if (goal != null) {
                int remainingGoals = remainingHomeGoals + remainingAwayGoals - 1; // -1 because this goal is already accounted for
                int minMinute = lastMinute + 1;
                int maxMinute = 90 - remainingGoals * 3; // keep at least 3 minutes for each remaining goal

                if (maxMinute < minMinute) maxMinute = minMinute;
                if (maxMinute > 90) maxMinute = 90;

                int minute = rnd.nextInt(minMinute, maxMinute + 1); // bound is exclusive, therefore +1
                goal.setMinute(minute);
                goal.setMatch(simulatedMatch);
                // Decrease remaining goals counter
                if (isHomeGoal) {
                    remainingHomeGoals--;
                } else {
                    remainingAwayGoals--;
                }
                if(isHomeGoal) {goal.setScoreAfterGoal((homeGoals-remainingHomeGoals) + ":" + (awayGoals-remainingAwayGoals));}
                else{goal.setScoreAfterGoal((homeGoals-remainingHomeGoals) + ":" + (awayGoals-remainingAwayGoals));}

                // Persist event
                matchEventRepository.save(goal);
                lastMinute = minute;

            }
        }

        // 3. Generate additional statistics (shots, corners, cards...)
        matchStatisticEngine.generateFakeAdditionalStats(simulatedMatch, homePlayers, awayPlayers, homeGoals, awayGoals, rnd);
    }
}
