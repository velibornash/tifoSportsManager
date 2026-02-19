package org.example.footballmanager.simulator;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.service.DemoMatchRuntime;
import org.example.footballmanager.util.TacticsAdjustmentService;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class DemoSimulator {

    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final MatchRepository matchRepository;
    private final Random random = new Random();
    private final BroadcastPositonHandling  broadcastPositonHandling;
    private final Map<Long, DemoMatchRuntime> runtimes = new ConcurrentHashMap<>();
    private final Set<Long> runningMatches = ConcurrentHashMap.newKeySet();
    private final Map<Long, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final MatchEventMapper matchEventMapper = new MatchEventMapper();

    public DemoSimulator(TacticsAdjustmentService tacticsAdjustmentService,
                         MatchRepository matchRepository,
                         BroadcastPositonHandling broadcastPositonHandling
            ) {
        this.tacticsAdjustmentService = tacticsAdjustmentService;
        this.matchRepository = matchRepository;
        this.broadcastPositonHandling = broadcastPositonHandling;
    }

    @SneakyThrows
    public DemoMatchRuntime simulateMatch(Match match, Crowd crowd, Referee referee, Tactics homeTactics, Tactics awayTactics, List<Player> homePlayers, List<Player> awayPlayers, ScheduledExecutorService scheduler) {
        CountDownLatch latch = new CountDownLatch(92);
        MatchContext context = new MatchContext(match, crowd, referee, homeTactics, awayTactics);
        context.setPossessionTeam(match.getHomeTeam());
        Formation homeFormation = homeTactics.getFormation();
        Formation awayFormation = awayTactics.getFormation();
        DemoMatchRuntime rt = runtimes.get(match.getId());
        MatchStartEvent startEvent = new MatchStartEvent();
        startEvent.setMinute(1);
        startEvent.setMatch(match);
        startEvent.apply();
        MatchEventDTO startDto = matchEventMapper.toDto(startEvent);
        if (startDto != null) {
            scheduler.schedule(() -> {
                broadcastPositonHandling.broadcastCurrentEvent(match.getId(), startDto);
                rt.runtimeEvents.add(startEvent);
            }, 500, TimeUnit.MILLISECONDS);
        }
        MatchEventFactory eventFactory = new MatchEventFactory();
        for (int minute = 1; minute <= 92; minute++) {
            final int currentMinute = minute;
            scheduler.schedule(() -> {
                try {
                    context.setCurrentMinute(currentMinute);
                    updateFatigue(context);
                    updatePossession(context, homePlayers, awayPlayers, homeFormation, awayFormation);
                    tacticsAdjustmentService.adjustTactics(context);

                    if (currentMinute < 91) {
                        MatchEvent event = eventFactory.createRandomEvent(context, homePlayers, awayPlayers, homeFormation, awayFormation);
                        if (event != null)
                        {
                            event.setMinute(currentMinute);
                            event.apply();
                            rt.runtimeEvents.add(event);
                            log.info("[{}'] Event: {}", currentMinute, event.getDescription());
                            MatchEventDTO dto = matchEventMapper.toDto(event);
                            if (dto != null) {
                                broadcastPositonHandling.broadcastCurrentEvent(match.getId(), dto);
                            }
                            if (event instanceof GoalEvent goal) {
                                goal.setMatch(match);
                                rt.runtimeGoals.add(goal);
                                if (goal.getTeam().equals(match.getHomeTeam())) {
                                    rt.homeGoals++;
                                } else {
                                    rt.awayGoals++;
                                }
                                goal.setScoreAfterGoal(rt.homeGoals + ":" + rt.awayGoals);
                                goal.getScorer().setTotalGoals(goal.getScorer().getTotalGoals() + 1);
                                goal.getAssistant().setTotalAssists(goal.getAssistant().getTotalAssists() + 1);
                                goal.apply();
                            }
                            if (event instanceof PenaltyEvent pen ) {
                                if (pen.isScored()) {

                                    GoalEvent goal = new GoalEvent();
                                    goal.setMatch(match);
                                    goal.setTeam(pen.getTeam());
                                    goal.setScorer(pen.getTaker());
                                    goal.setMinute(currentMinute);
                                    goal.setScored(true);

                                    if (goal.getTeam().equals(match.getHomeTeam())) {
                                        rt.homeGoals++;
                                    } else {
                                        rt.awayGoals++;
                                    }
                                    goal.setScoreAfterGoal(rt.homeGoals + ":" + rt.awayGoals);
                                    goal.getScorer().setTotalGoals(goal.getScorer().getTotalGoals() + 1);
                                    goal.getAssistant().setTotalAssists(goal.getAssistant().getTotalAssists() + 1);
                                    goal.apply();
                                    rt.runtimeGoals.add(goal);
                                    rt.runtimeEvents.add(goal);
                                    log.info("[{}'] Event: {}", currentMinute, goal.getDescription());
                                    MatchEventDTO goalDto = matchEventMapper.toDto(goal);
                                    broadcastPositonHandling.broadcastCurrentEvent(match.getId(), goalDto);
                                }

                            }
                            if (event instanceof InjuryEvent) performSubstitution(match, context, isHomeTeam(event) ? homePlayers : awayPlayers, isHomeTeam(event));

                        }

                        if (currentMinute == 65) {
                            performSubstitution(match, context, homePlayers, true);
                            performSubstitution(match, context, awayPlayers, false);
                        }
                    }
                    else if (currentMinute == 91)
                    {   MatchEndedEvent endEvent = new MatchEndedEvent();
                        endEvent.setMinute(91);
                        endEvent.setMatch(match);
                        endEvent.apply();
                        MatchEventDTO endDto = matchEventMapper.toDto(endEvent);
                        if (endDto != null) {
                            scheduler.schedule(() -> broadcastPositonHandling.broadcastCurrentEvent(match.getId(), endDto), 30000, TimeUnit.MILLISECONDS);
                            rt.runtimeEvents.add(endEvent   );

                        }
                        log.info("[91'] Event: {}", endEvent.getDescription());}
                    else
                    {
                        match.setPlayed(true);
                    }
                }
                catch (Exception e) {
                    System.out.println(e.getMessage());
                }
                finally {latch.countDown();}
            }, 3000 , TimeUnit.MILLISECONDS);}
        latch.await();
        return rt;
    }

    public Match loadAndValidateMatch(long matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));
    }
    public boolean startSimulationOnlyIfNotRunning(long matchId) {
        if (!runningMatches.add(matchId)) {
            log.info("Match {} već se simulira!", matchId);
            return false;
        }
        return true;
    }
    public ScheduledExecutorService createAndRegisterScheduler(long matchId) {
        ScheduledExecutorService old = schedulers.get(matchId);
        //if (old != null) old.shutdownNow();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.put(matchId, scheduler);
        return scheduler;
    }
    public DemoMatchRuntime initializeRuntimeAndPositions(long matchId) {
        DemoMatchRuntime runtime = new DemoMatchRuntime();
        runtimes.put(matchId, runtime);
        Random r = new Random();
        // Home players (1–11)
        for (int i = 1; i <= 11; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "HOME", 10 + r.nextDouble() * 35, 10 + r.nextDouble() * 80));
        }
        // Away players (12–22)
        for (int i = 12; i <= 22; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "AWAY", 65 + r.nextDouble() * 30, 10 + r.nextDouble() * 80));
        }
        runtime.ball = new BallPositionDTO(50, 50);
        runtime.currentCarrier = runtime.players.getFirst();
        return runtime;
    }
    public void prepareMatchEntities(Match match, DemoMatchRuntime rt) {
        rt.home = match.getHomeLineup().getStartingPlayers();
        rt.away = match.getAwayLineup().getStartingPlayers();

        if (rt.home.size() != 11 ||rt.away.size() != 11) {
            throw new RuntimeException("Svaki tim mora imati tačno 11 igrača u postavi.");
        }
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

    private void performSubstitution(Match match, MatchContext context, List<Player> teamPlayers, boolean isHomeTeam) {
        if (teamPlayers.size() < 12) return;

        Player out = teamPlayers.get(random.nextInt(11));
        Player in = teamPlayers.get(11 + random.nextInt(teamPlayers.size() - 11));

        SubstitutionEvent sub = new SubstitutionEvent();
        sub.setMatch(match);
        sub.setMinute(context.getCurrentMinute());
        sub.setPlayerOut(out);
        sub.setPlayerIn(in);
        sub.apply();

        log.info("[{}'] Substitution: {} out, {} in", context.getCurrentMinute(), out.getName(), in.getName());

        MatchEventDTO subDto = matchEventMapper.toDto(sub);
        if (subDto != null) {
            try {
                broadcastPositonHandling.broadcastCurrentEvent(match.getId(),subDto);
                Thread.sleep(3500);
            } catch (Exception e) {
                log.error("Greška pri broadcastu zamene", e);
            }
        }

        teamPlayers.remove(out);
        teamPlayers.add(in);
    }
    private void updateFatigue(MatchContext context) {
        context.setFatigueFactor(Math.max(0.7, context.getFatigueFactor() - 0.002));
        //log.info("Minute: {}, Fatigue Factor: {}", context.getCurrentMinute(), context.getFatigueFactor());
    }
    private void updatePossession(MatchContext context, List<Player> homePlayers, List<Player> awayPlayers, Formation homeFormation, Formation awayFormation) {
        double homeStrength = TeamStrengthCalculator.calculateTeamStrength(homePlayers, homeFormation, context.getHomeTactics(), true);
        double awayStrength = TeamStrengthCalculator.calculateTeamStrength(awayPlayers, awayFormation, context.getAwayTactics(), false);
        double total = homeStrength + awayStrength;
        if (random.nextDouble() < homeStrength / total) {
            context.setPossessionTeam(context.getMatch().getHomeTeam());
        } else {
            context.setPossessionTeam(context.getMatch().getAwayTeam());
        }
        //log.info("Minute: {}, Possession: {}", context.getCurrentMinute(), context.getPossessionTeam().getName());
    }
    private boolean isHomeTeam(MatchEvent event) {
        if (event instanceof GoalEvent goal) return goal.getTeam().equals(goal.getMatch().getHomeTeam());
        if (event instanceof SubstitutionEvent sub) return sub.getPlayerOut().getTeam().equals(sub.getMatch().getHomeTeam());
        return false;
    }

public void shutdownScheduler(long matchId) {
    ScheduledExecutorService scheduler = schedulers.remove(matchId);
    if (scheduler != null) {
        scheduler.shutdownNow();
    }
}
    }