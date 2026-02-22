package org.example.footballmanager.simulator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.service.DemoMatchRuntime;
import org.example.footballmanager.util.TacticsAdjustmentService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class DemoMatchEngine {

    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final MatchRepository matchRepository;
    private final Random random = new Random();
    private final Set<Long> runningMatches = ConcurrentHashMap.newKeySet();
    private final Map<Long, DemoMatchRuntime> runtimes = new ConcurrentHashMap<>();
    private final MatchPlaybackEngine matchPlaybackEngine;
    public Match loadAndValidateMatch(long matchId)
    {return matchRepository.findById(matchId).orElseThrow(() -> new RuntimeException("Match not found"));}
    public boolean startSimulationOnlyIfNotRunning(long matchId) {
        if (!runningMatches.add(matchId)) {
            log.info("Match {} već se simulira!", matchId);
            return false;
        }
        return true;
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
    public DemoMatchRuntime simulateFullMatch(Match match) {

        DemoMatchRuntime rt = new DemoMatchRuntime();
        rt=matchPlaybackEngine.initializeRuntimeAndPositions(rt);
        rt.homePlayers = new ArrayList<>(match.getHomeLineup().getStartingPlayers());
        rt.awayPlayers = new ArrayList<>(match.getAwayLineup().getStartingPlayers());

        rt.runtimeEvents = new ArrayList<>();
        rt.runtimeGoals = new ArrayList<>();
        rt.homeTactics = createHomeTactics(match);
        rt.awayTactics = createAwayTactics(match);
        MatchEventFactory factory = new MatchEventFactory();
        MatchContext context = new MatchContext(match, rt.crowd, rt.referee, rt.homeTactics, rt.awayTactics);
        // generišemo sve minute unapred
        for (int minute = 1; minute <= 90; minute++) {
            context.setCurrentMinute(minute);
                updateFatigue(context);
                updatePossession(context, rt.homePlayers, rt.awayPlayers, rt.homeTactics.getFormation(), rt.awayTactics.getFormation());
                tacticsAdjustmentService.adjustTactics(context);
                MatchEvent event = factory.createRandomEvent(context, rt.homePlayers, rt.awayPlayers, rt.homeTactics.getFormation(), rt.awayTactics.getFormation());

                if (event != null) {
                    event.setMinute(minute);
                    event.apply();
                    rt.runtimeEvents.add(event);

                    processSpecialEvents(event, rt, match);if (event instanceof org.example.footballmanager.model.event.GoalEvent goal) {rt.runtimeGoals.add(goal);}
                }

            // SNIMANJE POZICIJA – na kraju svake minute (ili češće ako želiš finiji replay)
            // Koristiš duboku kopiju da se ne menja kasnije
            rt.positionHistory.add(new DemoMatchRuntime.TickPositionSnapshot(minute * 10, rt.players)); // npr. tick = minute * 10
            rt.ballHistory.add(new BallPositionDTO(rt.ball.getX(), rt.ball.getY())); // kopija lopte
        }

        log.info("Engine završio generisanje meča. Eventa: {}", rt.runtimeEvents.size());
        return rt;
    }
    private void processSpecialEvents(MatchEvent event, DemoMatchRuntime rt, Match match) {

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
}