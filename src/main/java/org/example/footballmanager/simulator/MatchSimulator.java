package org.example.footballmanager.simulator;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.util.MatchEventWebSocketHandler;
import org.example.footballmanager.util.TacticsAdjustmentService;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Random;

@Slf4j
    @Component
public class MatchSimulator {

    private final MatchEventFactory eventFactory = new MatchEventFactory();
    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final MatchEventWebSocketHandler webSocketHandler;
    private final Random random = new Random();

    public MatchSimulator(TacticsAdjustmentService tacticsAdjustmentService, MatchEventWebSocketHandler webSocketHandler) {
        this.tacticsAdjustmentService = tacticsAdjustmentService;
        this.webSocketHandler = webSocketHandler;
    }

    @SneakyThrows
    public void simulateMatch(Match match, Crowd crowd, Referee referee,
                              Tactics homeTactics, Tactics awayTactics,
                              List<Player> homePlayers, List<Player> awayPlayers) {

        MatchContext context = new MatchContext(match, crowd, referee, homeTactics, awayTactics);
        context.setPossessionTeam(match.getHomeTeam()); // start possession

        Formation homeFormation = homeTactics.getFormation();
        Formation awayFormation = awayTactics.getFormation();
        Thread.sleep(1500);
        // čekaj da bar jedan WebSocket klijent bude povezan
        int waitCounter = 0;
        while (webSocketHandler.getSessionCount() == 0 && waitCounter < 10) {
            Thread.sleep(1000);
            waitCounter++;
        }

        for (int minute = 1; minute <= 90; minute++) {
            context.setCurrentMinute(minute);

            // fatigue i possession
            updateFatigue(context);
            updatePossession(context, homePlayers, awayPlayers, homeFormation, awayFormation);

            // taktike
            tacticsAdjustmentService.adjustTactics(context);

            // event probability
            if (random.nextDouble() < eventProbability(context, match)) {
                MatchEvent event = eventFactory.createRandomEvent(context, homePlayers, awayPlayers, homeFormation, awayFormation);
                if (event != null) {
                    event.setMinute(minute);
                    event.apply();
                    log.info("[{}'] Event: {}", minute, event.getDescription());
                    try {

                        webSocketHandler.broadcastEvent(event);
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        log.error("WebSocket broadcast failed", e);
                    }
                    if (event instanceof PenaltyEvent)
                        if (((PenaltyEvent) event).isScored()) {
                            GoalEvent goal = new GoalEvent();
                            goal.setMatch(match);
                            goal.setTeam(((PenaltyEvent) event).getTeam());
                            goal.setScorer(((PenaltyEvent) event).getTaker());
                            goal.setMinute(minute);
                            goal.isScored();
                            // Izračunaj rezultat odmah
                            long homeGoals = match.getGoals().stream()
                                    .filter(g -> g.getTeam().equals(match.getHomeTeam()))
                                    .count() + (goal.getTeam().equals(match.getHomeTeam()) ? 1 : 0);

                            long awayGoals = match.getGoals().stream()
                                    .filter(g -> g.getTeam().equals(match.getAwayTeam()))
                                    .count() + (goal.getTeam().equals(match.getAwayTeam()) ? 1 : 0);

                            goal.setScoreAfterGoal(String.format("%d:%d", homeGoals, awayGoals));
                            log.info("[{}'] Event: {}", minute, goal.getDescription());
                            try {

                                webSocketHandler.broadcastEvent(goal);
                                Thread.sleep(2000);
                            } catch (Exception e) {
                                log.error("WebSocket broadcast failed", e);
                            }
                            match.getGoals().add(goal);
                            match.getAllMatchEvents().add(goal);

                        }
                    // substitution za injury
                    if (event instanceof InjuryEvent)
                        performSubstitution(match, context, isHomeTeam(event) ? homePlayers : awayPlayers, isHomeTeam(event));
                }
            }

            // random substitutions
            if (minute == 60 || minute == 75) {
                performSubstitution(match, context, homePlayers, true);
                performSubstitution(match, context, awayPlayers, false);
            }
        }

        // kraj meča
        MatchEndedEvent endEvent = new MatchEndedEvent();
        endEvent.setMinute(90);
        endEvent.setMatch(match);
        endEvent.apply();
        webSocketHandler.broadcastEvent(endEvent);

        match.setPlayed(true);
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
        try {
            webSocketHandler.broadcastEvent(sub);
        } catch (Exception e) {
            log.error("Error broadcasting substitution", e);
        }

        teamPlayers.remove(out);
        teamPlayers.add(in);
    }

    private void updateFatigue(MatchContext context) {
        context.setFatigueFactor(Math.max(0.7, context.getFatigueFactor() - 0.002));
        log.info("Minute: {}, Fatigue Factor: {}", context.getCurrentMinute(), context.getFatigueFactor());
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
        log.info("Minute: {}, Possession: {}", context.getCurrentMinute(), context.getPossessionTeam().getName());
    }

    private boolean isHomeTeam(MatchEvent event) {
        if (event instanceof GoalEvent goal) {
            return goal.getTeam().equals(goal.getMatch().getHomeTeam());
        } else if (event instanceof SubstitutionEvent sub) {
            // recimo da je playerOut tim domaćin
            return sub.getPlayerOut().getTeam().equals(sub.getMatch().getHomeTeam());
        }
        // za ostale evente (kartoni, povrede) možeš vratiti false ili po potrebi
        return false;
    }

    private double eventProbability(MatchContext context, Match match) {
        double base = 0.1;
        double strengthFactor = 0.2; // simplifikacija
        return Math.min(0.3, base + strengthFactor * context.getFatigueFactor());
    }
}
