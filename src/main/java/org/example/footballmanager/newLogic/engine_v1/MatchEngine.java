package org.example.footballmanager.newLogic.engine_v1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.PlayerPositionDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.model.tactics.Formation;
import org.example.footballmanager.newLogic.model.tactics.Tactics;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.AttendanceService;
import org.example.footballmanager.newLogic.service.PlayerMovementDecisionService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.TacticsAdjustmentService;
import org.example.footballmanager.newLogic.util.events.EventCreator;
import org.example.footballmanager.newLogic.util.match.MatchContext;
import org.example.footballmanager.newLogic.util.players.PlayerFactory;
import org.example.footballmanager.newLogic.engine_v1.TeamStrengthCalculator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class MatchEngine {

    private record QuickSimScore(int homeGoals, int awayGoals) {}

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
    private final AttendanceService attendanceService;

    @Transactional(readOnly = true)
    public Match loadAndValidateMatch(long matchId) {
        Match match = matchRepository.findWithTeamsAndLineupsById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));
        initializeLineup(match.getHomeLineup());
        initializeLineup(match.getAwayLineup());
        return match;
    }

    private void initializeLineup(Lineup lineup) {
        if (lineup == null) {
            return;
        }
        lineup.getFormation();
        lineup.getOrderedStartingPlayers().size();
        lineup.getOrderedSubstitutePlayers().size();
    }

    private Lineup createLineupForMatch(Team team, List<Player> players, String formationName) {
        Lineup template = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(team.getId()).orElse(null);
        String resolvedFormation = Optional.ofNullable(template)
                .map(Lineup::getFormation)
                .filter(value -> value != null && !value.isBlank())
                .orElse(formationName);
        String resolvedStyle = Optional.ofNullable(template)
                .map(Lineup::getStyle)
                .orElse("BALANCED");
        List<Long> preferredStarterIds = Optional.ofNullable(template)
                .map(Lineup::getOrderedStarterIds)
                .orElse(List.of());
        List<Long> preferredBenchIds = Optional.ofNullable(template)
                .map(Lineup::getOrderedBenchIds)
                .orElse(List.of());
        return createLineupForMatch(team, players, resolvedFormation, resolvedStyle, preferredStarterIds, preferredBenchIds);
    }

    private Lineup createLineupForMatch(Team team,
                                        List<Player> players,
                                        String formationName,
                                        String styleName,
                                        List<Long> preferredStarterIds,
                                        List<Long> preferredBenchIds) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setFormation(formationName);
        lineup.setStyle(normalizeStyle(styleName));

        List<Player> eligiblePlayers = players.stream()
                .filter(p -> !p.isInjured())
                .toList();
        List<Player> basePool = eligiblePlayers.isEmpty() ? players : eligiblePlayers;

        Map<Long, Player> byId = basePool.stream()
                .collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));

        List<Player> managedStarting = selectStartingPlayers(basePool, preferredStarterIds, formationName).stream()
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

    static List<Player> selectStartingPlayers(List<Player> basePool, List<Long> preferredStarterIds) {
        return selectStartingPlayers(basePool, preferredStarterIds, "4-4-2");
    }

    static List<Player> selectStartingPlayers(List<Player> basePool, List<Long> preferredStarterIds, String formationName) {
        if (basePool == null || basePool.isEmpty()) {
            return List.of();
        }

        if (preferredStarterIds == null || preferredStarterIds.isEmpty()) {
            return selectFallbackStartingPlayers(basePool, formationName);
        }

        Map<Long, Player> byId = basePool.stream()
                .collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));

        List<Player> orderedCandidates = new ArrayList<>();
        if (preferredStarterIds != null && !preferredStarterIds.isEmpty()) {
            preferredStarterIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .filter(player -> orderedCandidates.stream().noneMatch(existing -> Objects.equals(existing.getId(), player.getId())))
                    .forEach(orderedCandidates::add);
        }
        basePool.stream()
                .filter(player -> orderedCandidates.stream().noneMatch(existing -> Objects.equals(existing.getId(), player.getId())))
                .forEach(orderedCandidates::add);

        Player primaryGoalkeeper = orderedCandidates.stream()
                .filter(player -> player.getPosition() == Position.GK)
                .findFirst()
                .orElse(null);

        List<Player> starters = new ArrayList<>();
        if (primaryGoalkeeper != null) {
            starters.add(primaryGoalkeeper);
        }

        orderedCandidates.stream()
                .filter(player -> primaryGoalkeeper == null || !Objects.equals(player.getId(), primaryGoalkeeper.getId()))
                .filter(player -> player.getPosition() != Position.GK)
                .forEach(player -> {
                    if (starters.size() < 11) {
                        starters.add(player);
                    }
                });

        if (starters.size() < 11) {
            orderedCandidates.stream()
                    .filter(player -> starters.stream().noneMatch(existing -> Objects.equals(existing.getId(), player.getId())))
                    .forEach(player -> {
                        if (starters.size() < 11) {
                            starters.add(player);
                        }
                    });
        }

        return List.copyOf(starters.subList(0, Math.min(11, starters.size())));
    }

    private static List<Player> selectFallbackStartingPlayers(List<Player> basePool, String formationName) {
        List<Player> remaining = new ArrayList<>(basePool);
        remaining.sort(Comparator
                .comparingInt(MatchEngine::selectionPriority)
                .thenComparing(Comparator.comparingInt(Player::getRating).reversed())
                .thenComparing(Player::getId));

        List<Player> starters = new ArrayList<>();
        Player goalkeeper = pickBestMatching(remaining, Position.GK);
        if (goalkeeper != null) {
            starters.add(goalkeeper);
            remaining.remove(goalkeeper);
        }

        for (Position desired : fallbackOutfieldOrder(formationName)) {
            if (starters.size() >= 11) {
                break;
            }
            Player chosen = pickBestMatching(remaining, desired);
            if (chosen != null) {
                starters.add(chosen);
                remaining.remove(chosen);
            }
        }

        remaining.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .forEach(player -> {
                    if (starters.size() < 11) {
                        starters.add(player);
                    }
                });

        if (starters.size() < 11) {
            remaining.stream()
                    .filter(player -> starters.stream().noneMatch(existing -> Objects.equals(existing.getId(), player.getId())))
                    .forEach(player -> {
                        if (starters.size() < 11) {
                            starters.add(player);
                        }
                    });
        }

        return List.copyOf(starters.subList(0, Math.min(11, starters.size())));
    }

    private static Player pickBestMatching(List<Player> remaining, Position desired) {
        return remaining.stream()
                .filter(player -> matchesDesiredSlot(player.getPosition(), desired))
                .min(Comparator
                        .comparingInt((Player player) -> slotFitScore(player.getPosition(), desired))
                        .thenComparing(Comparator.comparingInt(Player::getRating).reversed())
                        .thenComparing(Player::getId))
                .orElse(null);
    }

    private static int slotFitScore(Position actual, Position desired) {
        if (actual == null || desired == null) {
            return Integer.MAX_VALUE;
        }
        if (actual == desired) {
            return 0;
        }
        return switch (desired) {
            case DEF -> actual == Position.MID ? 1 : 10;
            case MID -> actual == Position.WNG ? 1 : actual == Position.ATT ? 2 : 10;
            case WNG -> actual == Position.MID ? 1 : actual == Position.ATT ? 2 : 10;
            case ATT -> actual == Position.WNG ? 1 : actual == Position.MID ? 2 : 10;
            default -> 10;
        };
    }

    private static boolean matchesDesiredSlot(Position actual, Position desired) {
        if (actual == null || desired == null) {
            return false;
        }
        if (actual == desired) {
            return true;
        }
        return switch (desired) {
            case MID -> actual == Position.WNG || actual == Position.ATT;
            case WNG -> actual == Position.MID || actual == Position.ATT;
            case ATT -> actual == Position.WNG || actual == Position.MID;
            case DEF -> actual == Position.MID;
            default -> false;
        };
    }

    private static int selectionPriority(Player player) {
        return switch (player.getPosition()) {
            case GK -> 0;
            case DEF -> 1;
            case MID -> 2;
            case WNG -> 3;
            case ATT -> 4;
            default -> 5;
        };
    }

    private static List<Position> fallbackOutfieldOrder(String formationName) {
        return switch (formationName == null ? "4-4-2" : formationName.trim()) {
            case "4-3-3" -> List.of(Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                    Position.MID, Position.MID, Position.MID, Position.WNG, Position.ATT, Position.WNG);
            case "4-2-3-1" -> List.of(Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                    Position.MID, Position.MID, Position.WNG, Position.MID, Position.WNG, Position.ATT);
            case "4-1-4-1", "4-5-1" -> List.of(Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                    Position.MID, Position.MID, Position.MID, Position.MID, Position.MID, Position.ATT);
            case "3-5-2" -> List.of(Position.DEF, Position.DEF, Position.DEF,
                    Position.WNG, Position.MID, Position.MID, Position.MID, Position.WNG, Position.ATT, Position.ATT);
            case "3-4-3" -> List.of(Position.DEF, Position.DEF, Position.DEF,
                    Position.MID, Position.MID, Position.MID, Position.MID, Position.WNG, Position.ATT, Position.WNG);
            case "3-4-2-1" -> List.of(Position.DEF, Position.DEF, Position.DEF,
                    Position.WNG, Position.MID, Position.MID, Position.WNG, Position.MID, Position.MID, Position.ATT);
            case "5-3-2" -> List.of(Position.DEF, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                    Position.MID, Position.MID, Position.MID, Position.ATT, Position.ATT);
            case "5-4-1" -> List.of(Position.DEF, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                    Position.MID, Position.MID, Position.MID, Position.MID, Position.ATT);
            default -> List.of(Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                    Position.MID, Position.MID, Position.MID, Position.MID, Position.ATT, Position.ATT);
        };
    }

    public Match createMatch(Team userTeam) {
        GameClock clock = seasonService.getOrCreateClock();
        int seasonYear = seasonService.getActiveSeasonYear();
        int week = seasonService.getCurrentWeek();

        Competition superLiga = competitionRepository.findById(1L)
                .orElseThrow(() -> new RuntimeException("Superliga not found"));
        Competition activeLeague = userTeam.getCompetition() != null ? userTeam.getCompetition() : superLiga;

        SeasonCompetition sc = seasonService.ensureSeasonCompetition(activeLeague, seasonYear);
        seasonService.ensureEntriesForSeasonCompetition(activeLeague, seasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(activeLeague, seasonYear);
        if (week == SeasonService.PLAYOFF_WEEK && Objects.equals(activeLeague.getTier(), 1)) {
            seasonService.ensurePlayoffWeekFixtures(activeLeague, seasonYear);
        } else if (week == SeasonService.FRIENDLY_WEEK) {
            seasonService.ensureFriendlyWeekFixtures(activeLeague, seasonYear);
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
                activeLeague.getId(), seasonYear, week
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
            match.setCompetition(activeLeague);
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
            match.setCompetition(activeLeague);
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
        match.setCompetition(activeLeague);
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
    public boolean isSimulationRunning(long matchId) {
        return runningMatches.contains(matchId);
    }
    public void markSimulationFinished(long matchId) {
        runningMatches.remove(matchId);
    }
    public Tactics createHomeTactics(Match match) {
        return createTacticsFromLineup(match.getHomeLineup(), "4-4-2");
    }
    public Tactics createAwayTactics(Match match) {
        return createTacticsFromLineup(match.getAwayLineup(), "4-2-3-1");
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
        MatchStartEvent matchStartEvent = new MatchStartEvent(1, 0,
                match.getHomeTeam().getName(), match.getAwayTeam().getName());
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

        MatchEndEvent ended = new MatchEndEvent(90, rt.tick, rt.homeGoals, rt.awayGoals);
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
                String assistName = null;
                Long assistId = null;
                Player assistantPlayer = teamPlayers.stream()
                        .filter(p -> p.getPosition() != Position.GK && !p.equals(shooter))
                        .skip(random.nextInt(Math.max(1, (int) teamPlayers.stream()
                                .filter(p -> p.getPosition() != Position.GK && !p.equals(shooter)).count())))
                        .findFirst().orElse(null);
                if (assistantPlayer != null) {
                    assistName = assistantPlayer.getName();
                    assistId = assistantPlayer.getId();
                }
                GoalEvent goal = new GoalEvent(minute, rt.tick,
                        shooter.getId(), shooter.getName(),
                        assistId, assistName,
                        isHome ? "HOME" : "AWAY", 0.8,
                        rt.homeGoals + 1, rt.awayGoals);
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
                ShotSavedEvent shot = new ShotSavedEvent(minute, rt.tick, 0,
                        shooter.getId(), shooter.getName(), isHome ? "HOME" : "AWAY",
                        0L, "GK", 0.5, "Shot saved", 0.0, 0.0);
                rt.runtimeEvents.add(shot);
            }
            case MISSED -> {
                ShotMissedEvent shot = new ShotMissedEvent(minute, rt.tick, 0,
                        shooter.getId(), shooter.getName(), isHome ? "HOME" : "AWAY",
                        0.3, "Shot missed", 0.0, 0.0);
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

        GoalEvent fallbackGoal = new GoalEvent(
                78 + random.nextInt(11), rt.tick,
                scorer.getId(), scorer.getName(),
                null, null,
                homeScores ? "HOME" : "AWAY", 0.5,
                homeScores ? rt.homeGoals + 1 : rt.homeGoals,
                !homeScores ? rt.awayGoals + 1 : rt.awayGoals);

        Player fallbackAssistant = outfield.stream()
                .filter(p -> !p.equals(scorer))
                .findFirst().orElse(null);
        if (fallbackAssistant != null) {
            fallbackGoal = new GoalEvent(
                    fallbackGoal.minute(), fallbackGoal.tick(),
                    fallbackGoal.scorerId(), fallbackGoal.scorerName(),
                    fallbackAssistant.getId(), fallbackAssistant.getName(),
                    fallbackGoal.teamSide(), fallbackGoal.xG(),
                    fallbackGoal.homeScoreAfter(), fallbackGoal.awayScoreAfter());
        }

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

            SetPieceEvent throwIn = new SetPieceEvent(minute, 0,
                    "HOME".equals(rt.restartTeam) ? "HOME" : "AWAY",
                    taker != null ? taker.getId() : null,
                    taker != null ? taker.getName() : "Unknown",
                    SetPieceEvent.SetPieceType.THROW_IN, 0.0, 0.0);
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

            SetPieceEvent corner = new SetPieceEvent(minute, 0,
                    lastTouchHome ? "AWAY" : "HOME",
                    taker != null ? taker.getId() : null,
                    taker != null ? taker.getName() : "Unknown",
                    SetPieceEvent.SetPieceType.CORNER, 0.0, 0.0);
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

        SetPieceEvent goalKick = new SetPieceEvent(minute, 0,
                "HOME".equals(rt.restartTeam) ? "HOME" : "AWAY",
                goalkeeper != null ? goalkeeper.getId() : null,
                goalkeeper != null ? goalkeeper.getName() : "GK",
                SetPieceEvent.SetPieceType.GOAL_KICK, 0.0, 0.0);
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
                boolean scoredPenaltyGoal = false;

                Player goalkeeper = findGoalkeeper(isHome ? rt.awaySquad : rt.homeSquad);
                DuelCalculator.DuelResult penResult = DuelCalculator.resolveDuel(
                        carrier,
                        goalkeeper,
                        context,
                        DuelCalculator.DuelType.SHOOTING
                );

                boolean scored = penResult.getOutcome() == DuelCalculator.DuelOutcomeQuality.CLEAN;
                PenaltyEvent penalty = new PenaltyEvent(minute, rt.tick,
                        carrier.getId(), carrier.getName(),
                        isHome ? "HOME" : "AWAY", scored, !scored, 0.76);
                rt.runtimeEvents.add(penalty);

                if (scored) {
                    GoalEvent goal = new GoalEvent(minute, rt.tick,
                            carrier.getId(), carrier.getName(),
                            null, null,
                            isHome ? "HOME" : "AWAY", 0.76,
                            isHome ? rt.homeGoals + 1 : rt.homeGoals,
                            isHome ? rt.awayGoals : rt.awayGoals + 1);
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
                SetPieceEvent fk = new SetPieceEvent(minute, 0,
                        isHome ? "HOME" : "AWAY",
                        carrier.getId(), carrier.getName(),
                        SetPieceEvent.SetPieceType.FREE_KICK, 0.0, 0.0);
                rt.runtimeEvents.add(fk);
                rt.activeStoppage = MatchRuntime.StoppageType.FREE_KICK;
                rt.stoppageTicks = 6;
                rt.restartTeam = isHome ? "HOME" : "AWAY";
            }

            if (result.getCardHint() == DuelCalculator.CardHint.YELLOW) {
                CardEvent yc = new CardEvent(minute, rt.tick,
                        defender.getId(), defender.getName(),
                        isHome ? "AWAY" : "HOME", CardEvent.CardType.YELLOW);
                rt.runtimeEvents.add(yc);
            } else if (result.getCardHint() == DuelCalculator.CardHint.RED) {
                CardEvent rc = new CardEvent(minute, rt.tick,
                        defender.getId(), defender.getName(),
                        isHome ? "AWAY" : "HOME", CardEvent.CardType.RED);
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

        InjuryEvent injuryEvent = new InjuryEvent(minute, rt.tick,
                injured.getId(), injured.getName(),
                "HOME".equals(rt.playerTeamSide.getOrDefault(injured.getId(), "HOME")) ? "HOME" : "AWAY");
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

        SubstitutionEvent substitutionEvent = new SubstitutionEvent(minute, rt.tick,
                playerOut.getId(), playerOut.getName(),
                playerIn.getId(), playerIn.getName(), side);
        rt.runtimeEvents.add(substitutionEvent);
    }

    // ========== VAR REVIEW ==========

    private void createVARReview(GoalEvent goal, PenaltyEvent penalty, MatchRuntime rt, Match match, int minute) {
        rt.activeStoppage = MatchRuntime.StoppageType.VAR_REVIEW;
        rt.stoppageTicks = 8;

        String decision;
        if (goal != null) {
            if (random.nextDouble() < 0.80) {
                decision = "Confirmed";
            } else {
                decision = "Overturned";
                if ("HOME".equals(goal.teamSide())) {
                    rt.homeGoals--;
                } else {
                    rt.awayGoals--;
                }
                rt.runtimeGoals.remove(goal);
                rt.kickoffFromCenter = false;
            }
        } else if (penalty != null) {
            decision = random.nextDouble() < 0.80 ? "Confirmed" : "Overturned";
        } else {
            decision = "Confirmed";
        }

        VARReviewEvent var = new VARReviewEvent();
        var.setMinute(minute);
        var.setDecision(decision);
        log.info("VAR Review: {} at minute {}", decision, minute);
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

    private boolean isInPenaltyBox(org.example.footballmanager.newLogic.dto.PlayerPositionDTO carrier, boolean attacksRight) {
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
        boolean countForStandings = currentWeek <= SeasonService.LEAGUE_ROUNDS;
        boolean requireWinner = currentWeek == SeasonService.PLAYOFF_WEEK;
        if (currentWeek == SeasonService.PLAYOFF_WEEK && Objects.equals(league.getTier(), 1)) {
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
            simulateFixture(fixture, league, season, sc, clock, countForStandings, requireWinner);
        }
        if (countForStandings) {
            log.info("Round finished - standings updated for league {}", league.getName());
        } else {
            log.info("Special round finished for league {} without standings update", league.getName());
        }
    }

    private void simulateFixture(MatchFixture fixture,
                                 Competition league,
                                 Season season,
                                 SeasonCompetition sc,
                                 GameClock clock,
                                 boolean updateStandings,
                                 boolean requireWinner) {
        Team home = fixture.getHomeTeam();
        Team away = fixture.getAwayTeam();
        if (home == null || away == null) {
            return;
        }

        List<Player> homePlayers = ensureTeamPlayers(home);
        List<Player> awayPlayers = ensureTeamPlayers(away);
        Lineup homeLineup = createLineupForMatch(home, homePlayers, "4-4-2");
        Lineup awayLineup = createLineupForMatch(away, awayPlayers, "4-4-2");
        QuickSimScore quickSimScore = simulateQuickScore(homeLineup, awayLineup);
        int homeGoals = quickSimScore.homeGoals();
        int awayGoals = quickSimScore.awayGoals();

        if (requireWinner && homeGoals == awayGoals) {
            if (pickDecisiveWinner(home, away).equals(home)) {
                homeGoals++;
            } else {
                awayGoals++;
            }
        }

        Match simulatedMatch = new Match();
        simulatedMatch.setHomeTeam(home);
        simulatedMatch.setAwayTeam(away);
        simulatedMatch.setStadium(home.getStadium());
        simulatedMatch.setCompetition(league);
        simulatedMatch.setSeasonYear(season.getSeasonYear());
        simulatedMatch.setRoundNumber(fixture.getRoundNumber());
        simulatedMatch.setWeekNumber(fixture.getWeekNumber());
        simulatedMatch.setMatchDate(fixture.getMatchDate() != null ? fixture.getMatchDate() : clock.getCurrentDate());
        simulatedMatch.setHomeGoals(homeGoals);
        simulatedMatch.setAwayGoals(awayGoals);
        attendanceService.ensureAttendance(simulatedMatch);
        simulatedMatch.setPlayed(true);
        simulatedMatch.setStarted(true);
        simulatedMatch.setHomeResultRevealed(false);
        simulatedMatch.setAwayResultRevealed(false);
        simulatedMatch.setHomeLineup(homeLineup);
        simulatedMatch.setAwayLineup(awayLineup);
        simulatedMatch.setHomeFormation(homeLineup.getFormation());
        simulatedMatch.setAwayFormation(awayLineup.getFormation());
        simulatedMatch = matchRepository.save(simulatedMatch);

        fixture.setPlayed(true);
        fixture.setPlayedMatch(simulatedMatch);
        matchFixtureRepository.save(fixture);

        generateSimulatedMatchEvents(simulatedMatch, homeGoals, awayGoals);
        List<MatchEvent> allEvents = matchEventRepository.findByMatch(simulatedMatch);
        List<GoalEvent> goals = allEvents.stream().filter(e -> e instanceof GoalEvent).map(e -> (GoalEvent) e).toList();
        List<CardEvent> cards = allEvents.stream().filter(e -> e instanceof CardEvent).map(e -> (CardEvent) e).toList();

        List<Player> ratedHome = matchStatisticEngine.assignRatings(new ArrayList<>(homeLineup.getOrderedStartingPlayers()), goals);
        List<Player> ratedAway = matchStatisticEngine.assignRatings(new ArrayList<>(awayLineup.getOrderedStartingPlayers()), goals);
        Map<Long, Integer> defaultMinutes = new HashMap<>();
        ratedHome.forEach(p -> defaultMinutes.put(p.getId(), 90));
        ratedAway.forEach(p -> defaultMinutes.put(p.getId(), 90));
        matchStatisticEngine.savePlayerStats(simulatedMatch, ratedHome, goals, cards, defaultMinutes);
        matchStatisticEngine.savePlayerStats(simulatedMatch, ratedAway, goals, cards, defaultMinutes);

        if (!updateStandings) {
            log.info("Simulated special match: {} {}:{} {}", home.getName(), homeGoals, awayGoals, away.getName());
            return;
        }

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

        competitionEntryRepository.saveAll(List.of(homeEntry, awayEntry));

        log.info("Simulated match: {} {}:{} {} | Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                home.getName(), homeGoals, awayGoals, away.getName(),
                homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
    }

    private Team pickDecisiveWinner(Team home, Team away) {
        double homeReputation = home.getReputation() != null ? home.getReputation() : 50.0;
        double awayReputation = away.getReputation() != null ? away.getReputation() : 50.0;
        double homeChance = Math.max(0.38, Math.min(0.72, (homeReputation + 6.0) / (homeReputation + awayReputation + 6.0)));
        return random.nextDouble() < homeChance ? home : away;
    }
    private void processSpecialEvents(MatchEvent event, MatchRuntime rt, Match match) {

        if (event instanceof GoalEvent goal) {
            if (goal.teamSide() != null && goal.teamSide().equals(match.getHomeTeam() != null ? "HOME" : null)) {
                rt.homeGoals++;
            } else {
                rt.awayGoals++;
            }
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
        match.setCompetition(sc.getCompetition());
        match.setSeasonYear(sc.getSeasonYear());

        List<Player> homePlayers = ensureTeamPlayers(home);
        List<Player> awayPlayers = ensureTeamPlayers(away);
        Lineup homeLineup = createLineupForMatch(home, homePlayers, "4-4-2");
        Lineup awayLineup = createLineupForMatch(away, awayPlayers, "4-4-2");
        QuickSimScore quickSimScore = simulateQuickScore(homeLineup, awayLineup);
        int homeGoals = quickSimScore.homeGoals();
        int awayGoals = quickSimScore.awayGoals();

        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        if (match.getStadium() == null) {
            match.setStadium(home.getStadium());
        }
        attendanceService.ensureAttendance(match);
        match.setPlayed(true);
        match.setStarted(true);
        match.setHomeResultRevealed(false);
        match.setAwayResultRevealed(false);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        match.setHomeFormation(homeLineup.getFormation());
        match.setAwayFormation(awayLineup.getFormation());

        match = matchRepository.save(match);
        generateSimulatedMatchEvents(match, homeGoals, awayGoals);

        List<MatchEvent> allEvents = matchEventRepository.findByMatch(match);
        List<GoalEvent> goals = allEvents.stream().filter(e -> e instanceof GoalEvent).map(e -> (GoalEvent) e).toList();
        List<CardEvent> cards = allEvents.stream().filter(e -> e instanceof CardEvent).map(e -> (CardEvent) e).toList();
        List<Player> ratedHome = matchStatisticEngine.assignRatings(new ArrayList<>(homeLineup.getOrderedStartingPlayers()), goals);
        List<Player> ratedAway = matchStatisticEngine.assignRatings(new ArrayList<>(awayLineup.getOrderedStartingPlayers()), goals);
        Map<Long, Integer> defaultMinutes = new HashMap<>();
        ratedHome.forEach(player -> defaultMinutes.put(player.getId(), 90));
        ratedAway.forEach(player -> defaultMinutes.put(player.getId(), 90));
        matchStatisticEngine.savePlayerStats(match, ratedHome, goals, cards, defaultMinutes);
        matchStatisticEngine.savePlayerStats(match, ratedAway, goals, cards, defaultMinutes);

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

        competitionEntryRepository.saveAll(List.of(homeEntry, awayEntry));

        log.info("Simulated match: {} {}:{} {} | Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                home.getName(), homeGoals, awayGoals, away.getName(),
                homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
    }
    public void generateSimulatedMatchEvents(Match simulatedMatch, int homeGoals, int awayGoals) {
        Team home = simulatedMatch.getHomeTeam();
        Team away = simulatedMatch.getAwayTeam();

        List<Player> homePlayers = simulatedMatch.getHomeLineup() != null && !simulatedMatch.getHomeLineup().getOrderedStartingPlayers().isEmpty()
                ? new ArrayList<>(simulatedMatch.getHomeLineup().getOrderedStartingPlayers())
                : ensureTeamPlayers(home);
        List<Player> awayPlayers = simulatedMatch.getAwayLineup() != null && !simulatedMatch.getAwayLineup().getOrderedStartingPlayers().isEmpty()
                ? new ArrayList<>(simulatedMatch.getAwayLineup().getOrderedStartingPlayers())
                : ensureTeamPlayers(away);

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
                int remainingGoals = remainingHomeGoals + remainingAwayGoals - 1;
                int minMinute = lastMinute + 1;
                int maxMinute = 90 - remainingGoals * 3;

                if (maxMinute < minMinute) maxMinute = minMinute;
                if (maxMinute > 90) maxMinute = 90;

                int minute = rnd.nextInt(minMinute, maxMinute + 1);
                goal = new GoalEvent(minute, 0,
                        goal.scorerId(), goal.scorerName(),
                        goal.assistantId(), goal.assistantName(),
                        goal.teamSide(), goal.xG(),
                        isHomeGoal ? homeGoals - remainingHomeGoals + 1 : homeGoals - remainingHomeGoals,
                        !isHomeGoal ? awayGoals - remainingAwayGoals + 1 : awayGoals - remainingAwayGoals);

                if (isHomeGoal) {
                    remainingHomeGoals--;
                } else {
                    remainingAwayGoals--;
                }

                // Persist event
                matchEventRepository.save(goal);
                lastMinute = minute;

            }
        }

        // 3. Generate additional statistics (shots, corners, cards...)
        matchStatisticEngine.generateFakeAdditionalStats(simulatedMatch, homePlayers, awayPlayers, homeGoals, awayGoals, rnd);
    }

    private List<Player> ensureTeamPlayers(Team team) {
        List<Player> players = playerRepository.findByTeam(team);
        if (!players.isEmpty()) {
            return players;
        }
        playerFactory.createRandomTeamPlayers(team.getName(), team);
        return playerRepository.findByTeam(team);
    }

    private QuickSimScore simulateQuickScore(Lineup homeLineup, Lineup awayLineup) {
        Tactics homeTactics = createTacticsFromLineup(homeLineup, "4-4-2");
        Tactics awayTactics = createTacticsFromLineup(awayLineup, "4-4-2");

        double homeStrength = TeamStrengthCalculator.calculateTeamStrength(
                homeLineup.getOrderedStartingPlayers(),
                homeTactics.getFormation(),
                homeTactics,
                true
        );
        double awayStrength = TeamStrengthCalculator.calculateTeamStrength(
                awayLineup.getOrderedStartingPlayers(),
                awayTactics.getFormation(),
                awayTactics,
                false
        );

        double homeExpectedGoals = calculateExpectedGoals(homeStrength, awayStrength, homeTactics, awayTactics, true);
        double awayExpectedGoals = calculateExpectedGoals(awayStrength, homeStrength, awayTactics, homeTactics, false);

        return new QuickSimScore(
                sampleGoals(homeExpectedGoals, random, 6),
                sampleGoals(awayExpectedGoals, random, 6)
        );
    }

    private double calculateExpectedGoals(double ownStrength,
                                          double opponentStrength,
                                          Tactics ownTactics,
                                          Tactics opponentTactics,
                                          boolean isHome) {
        double totalStrength = Math.max(1.0, ownStrength + opponentStrength);
        double strengthShare = ownStrength / totalStrength;
        double attackingIntent = (
                ownTactics.getAggression() * 0.18 +
                ownTactics.getPressing() * 0.16 +
                ownTactics.getCounterAttack() * 0.14 +
                ownTactics.getPossession() * 0.10 +
                ownTactics.getBallControl() * 0.10
        ) / 10.0;
        double opponentResistance = opponentTactics.getFormation().getDefenseModifier()
                * (0.92 + opponentTactics.getDefenseLine() / 30.0 + opponentTactics.getPressing() / 45.0);
        double homeBias = isHome ? 1.08 : 0.95;
        double expectedGoals = (0.45 + strengthShare * 2.25 + attackingIntent * 0.60)
                * ownTactics.getFormation().getOffenseModifier()
                * ownTactics.getFormation().getPossessionModifier()
                * homeBias
                / Math.max(0.85, opponentResistance);

        return Math.max(0.15, Math.min(4.2, expectedGoals));
    }

    private int sampleGoals(double lambda, Random rnd, int hardCap) {
        if (lambda <= 0.0) {
            return 0;
        }
        double threshold = Math.exp(-lambda);
        double product = 1.0;
        int goals = 0;
        do {
            goals++;
            product *= rnd.nextDouble();
        } while (product > threshold && goals <= hardCap);
        return Math.max(0, Math.min(hardCap, goals - 1));
    }

    private Tactics createTacticsFromLineup(Lineup lineup, String defaultFormation) {
        String formationName = lineup != null && lineup.getFormation() != null && !lineup.getFormation().isBlank()
                ? lineup.getFormation()
                : defaultFormation;
        String style = normalizeStyle(lineup != null ? lineup.getStyle() : null);

        Tactics tactics = new Tactics();
        tactics.setName(style);
        tactics.setFormation(createFormationProfile(formationName));

        switch (style) {
            case "ATTACKING" -> {
                tactics.setAggression(7.4);
                tactics.setDefenseLine(6.1);
                tactics.setPressing(6.8);
                tactics.setPossession(5.5);
                tactics.setCounterAttack(4.5);
                tactics.setBallControl(5.7);
            }
            case "DEFENSIVE" -> {
                tactics.setAggression(3.8);
                tactics.setDefenseLine(3.4);
                tactics.setPressing(4.4);
                tactics.setPossession(4.3);
                tactics.setCounterAttack(6.4);
                tactics.setBallControl(4.2);
            }
            case "COUNTER" -> {
                tactics.setAggression(5.4);
                tactics.setDefenseLine(4.5);
                tactics.setPressing(5.2);
                tactics.setPossession(3.9);
                tactics.setCounterAttack(8.2);
                tactics.setBallControl(4.8);
            }
            case "POSSESSION" -> {
                tactics.setAggression(4.8);
                tactics.setDefenseLine(5.6);
                tactics.setPressing(5.7);
                tactics.setPossession(8.4);
                tactics.setCounterAttack(3.6);
                tactics.setBallControl(8.1);
            }
            case "HIGH_PRESS" -> {
                tactics.setAggression(7.8);
                tactics.setDefenseLine(7.2);
                tactics.setPressing(8.7);
                tactics.setPossession(5.4);
                tactics.setCounterAttack(4.2);
                tactics.setBallControl(5.2);
            }
            case "DIRECT" -> {
                tactics.setAggression(5.9);
                tactics.setDefenseLine(5.1);
                tactics.setPressing(5.6);
                tactics.setPossession(3.5);
                tactics.setCounterAttack(7.2);
                tactics.setBallControl(4.2);
            }
            default -> {
                tactics.setAggression(5.4);
                tactics.setDefenseLine(5.0);
                tactics.setPressing(5.3);
                tactics.setPossession(5.1);
                tactics.setCounterAttack(4.9);
                tactics.setBallControl(5.1);
            }
        }

        return tactics;
    }

    private Formation createFormationProfile(String formationName) {
        String resolvedFormation = formationName == null || formationName.isBlank() ? "4-4-2" : formationName;
        int[] parts = Arrays.stream(resolvedFormation.split("-"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .mapToInt(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        return 0;
                    }
                })
                .filter(value -> value > 0)
                .toArray();

        int defenders = parts.length > 0 ? parts[0] : 4;
        int attackers = parts.length > 0 ? parts[parts.length - 1] : 2;
        int midfielders = parts.length > 2
                ? Arrays.stream(parts, 1, parts.length - 1).sum()
                : (parts.length == 2 ? parts[1] : 4);

        Formation formation = new Formation();
        formation.setName(resolvedFormation);
        formation.setOffenseModifier(clamp(0.90, 1.16,
                1.0 + (attackers - 2) * 0.05 + (midfielders - 4) * 0.015 - (defenders - 4) * 0.02));
        formation.setDefenseModifier(clamp(0.88, 1.16,
                1.0 + (defenders - 4) * 0.05 - (attackers - 2) * 0.025));
        formation.setPossessionModifier(clamp(0.90, 1.14,
                1.0 + (midfielders - 4) * 0.04 + (attackers == 1 ? 0.02 : 0.0)));
        return formation;
    }

    private String normalizeStyle(String style) {
        if (style == null || style.isBlank()) {
            return "BALANCED";
        }
        String normalized = style.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT" -> normalized;
            default -> "BALANCED";
        };
    }

    private double clamp(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }
}
