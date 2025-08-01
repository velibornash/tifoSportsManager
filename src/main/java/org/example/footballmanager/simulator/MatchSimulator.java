package org.example.footballmanager.simulator;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.SubstitutionEvent;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.util.MatchEventWebSocketHandler;
import org.example.footballmanager.util.TacticsAdjustmentService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchSimulator {

    private final MatchEventFactory eventFactory;
    private final MatchEventWebSocketHandler webSocketHandler;
    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final Random random = new Random();

    @SneakyThrows
    public void simulateMatch(Match match, List<Player> homePlayers, List<Player> awayPlayers) {
        MatchContext context = new MatchContext(match);
        context.setPossessionTeam(match.getHomeTeam()); // Početni posed domaćinu
        Thread.sleep(2000);
        for (int minute = 1; minute <= 90; minute++) {
            context.setCurrentMinute(minute);
            updateFatigue(context);
            updatePossession(context, homePlayers, awayPlayers);
            tacticsAdjustmentService.adjustTactics(context); // Prilagođavanje taktika

            if (random.nextDouble() < eventProbability(match, context)) {
                MatchEvent event = eventFactory.createRandomEvent(context, homePlayers, awayPlayers);
                if (event != null) {
                    log.info("[{}'] Event created: {}", minute, event.getDescription());
                    webSocketHandler.broadcastEvent(event);
                    Thread.sleep(4000);
                }
            }

            if (minute == 60 || minute == 75) {
                performSubstitution(match, context, homePlayers, true);
                performSubstitution(match, context, awayPlayers, false);
            }
        }
    }

    @SneakyThrows
    private void performSubstitution(Match match, MatchContext context, List<Player> teamPlayers, boolean isHomeTeam) {
        if (teamPlayers.size() < 12) return;

        Player out = selectPlayerForSubstitution(teamPlayers);
        Player in = selectReservePlayer(teamPlayers);

        SubstitutionEvent sub = new SubstitutionEvent();
        sub.setMatch(match);
        sub.setMinute(context.getCurrentMinute());
        sub.setPlayerOut(out);
        sub.setPlayerIn(in);
        sub.setKeyEvent(true);
        sub.setVisualize(true);
        sub.setImpact("MEDIUM");

        sub.apply(context);
        log.info("[{}'] Substitution: {} out, {} in", context.getCurrentMinute(), out.getName(), in.getName());
        try {
            webSocketHandler.broadcastEvent(sub);
            Thread.sleep(2000); // Pauza za zamenu
        } catch (IOException e) {
            log.error("Error broadcasting substitution event", e);
        }

        teamPlayers.remove(out);
        teamPlayers.add(in);
    }

    private Player selectPlayerForSubstitution(List<Player> teamPlayers) {
        return teamPlayers.stream()
                .filter(p -> !p.getPositionEnum().equals(Position.GK))
                .max((p1, p2) -> Double.compare(p1.getForm(), p2.getForm()))
                .orElse(teamPlayers.get(random.nextInt(teamPlayers.size())));
    }

    private Player selectReservePlayer(List<Player> teamPlayers) {
        int reserveCount = teamPlayers.size() - 11;
        if (reserveCount <= 0) return null;
        return teamPlayers.get(11 + random.nextInt(reserveCount));
    }

    private void updateFatigue(MatchContext context) {
        context.setFatigueFactor(Math.max(0.7, context.getFatigueFactor() - 0.002));
        log.info("Minute: {}, Fatigue Factor: {}", context.getCurrentMinute(), context.getFatigueFactor());
    }

    private void updatePossession(MatchContext context, List<Player> homePlayers, List<Player> awayPlayers) {
        double homePossessionStrength = TeamStrengthCalculator.calculateTeamStrength(
                homePlayers, Formation.fromString(context.getMatch().getHomeFormation()),
                context.getMatch().getHomeTactics(), true);
        double awayPossessionStrength = TeamStrengthCalculator.calculateTeamStrength(
                awayPlayers, Formation.fromString(context.getMatch().getAwayFormation()),
                context.getMatch().getAwayTactics(), false);

        double total = homePossessionStrength + awayPossessionStrength;
        if (random.nextDouble() < homePossessionStrength / total) {
            context.setPossessionTeam(context.getMatch().getHomeTeam());
            context.setBallPosition(getRandomBallPosition());
        } else {
            context.setPossessionTeam(context.getMatch().getAwayTeam());
            context.setBallPosition(getRandomBallPosition());
        }
        log.info("Minute: {}, Possession: {}, Ball Position: {}", context.getCurrentMinute(), context.getPossessionTeam().getName(), context.getBallPosition());
    }

    private String getRandomBallPosition() {
        String[] positions = {"left_wing", "center", "right_wing", "box"};
        return positions[random.nextInt(positions.length)];
    }

    private double eventProbability(Match match, MatchContext context) {
        double homeStrength = TeamStrengthCalculator.calculateTeamStrength(
                match.getHomeLineup().getStartingPlayers(),
                Formation.fromString(match.getHomeFormation()),
                match.getHomeTactics(), true);
        double awayStrength = TeamStrengthCalculator.calculateTeamStrength(
                match.getAwayLineup().getStartingPlayers(),
                Formation.fromString(match.getAwayFormation()),
                match.getAwayTactics(), false);

        double base = 0.1;
        double strengthFactor = (homeStrength + awayStrength) / 300.0;
        double fatigueFactor = context.getFatigueFactor();
        double probability = Math.min(0.3, base + strengthFactor * fatigueFactor);
        log.info("Minute: {}, Event Probability: {}, Home Strength: {}, Away Strength: {}, Fatigue: {}",
                context.getCurrentMinute(), probability, homeStrength, awayStrength, fatigueFactor);
        return probability;
    }
}