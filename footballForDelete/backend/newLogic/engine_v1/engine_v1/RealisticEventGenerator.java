package org.example.footballmanager.newLogic.engine_v1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.engine.DuelResolver;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.repository.MatchEventRepository;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RealisticEventGenerator {

    private final MatchEventRepository matchEventRepository;

    private String resolveTeamSide(Match match, Player player, MatchRuntime rt) {
        if (rt.homePlayers.contains(player)) return "HOME";
        if (rt.awayPlayers.contains(player)) return "AWAY";
        return "HOME";
    }

    private String resolveTeamSide(Match match, String teamSide) {
        return teamSide != null ? teamSide : "HOME";
    }

    public void createMatchStartEvent(MatchRuntime rt, Match match) {
        MatchStartEvent event = new MatchStartEvent(0, 0,
                match.getHomeTeam().getName(), match.getAwayTeam().getName());
        rt.runtimeEvents.add(event);
        log.debug("Match started: {} vs {}", match.getHomeTeam().getName(), match.getAwayTeam().getName());
    }

    public void createMatchEndedEvent(MatchRuntime rt, Match match) {
        MatchEndEvent event = new MatchEndEvent(90, 0, rt.homeGoals, rt.awayGoals);
        rt.runtimeEvents.add(event);
        log.debug("Match ended: {} {} - {} {}",
                match.getHomeTeam().getName(), rt.homeGoals,
                rt.awayGoals, match.getAwayTeam().getName());
    }

    public GoalEvent createGoalEvent(MatchRuntime rt, Match match, int minute,
                                Player scorer, Player assistant, double xG) {
        String teamSide = resolveTeamSide(match, scorer, rt);
        long assistantId = assistant != null ? assistant.getId() : 0;
        String assistantName = assistant != null ? assistant.getName() : null;

        GoalEvent event = new GoalEvent(minute, rt.tick,
                scorer.getId(), scorer.getName(),
                assistant != null ? assistant.getId() : null, assistantName,
                teamSide, xG, rt.homeGoals, rt.awayGoals);

        rt.runtimeEvents.add(event);
        rt.runtimeGoals.add(event);
        log.debug("GOAL! {} scored in minute {}", scorer.getName(), minute);
        return event;
    }

    public void createPassEvent(MatchRuntime rt, Match match, int minute,
                               Player passer, Player receiver) {
        String teamSide = resolveTeamSide(match, passer, rt);
        PassEvent event = PassEvent.completed(minute, rt.tick,
                passer.getId(), passer.getName(),
                receiver.getId(), receiver.getName(), teamSide);
        rt.runtimeEvents.add(event);
        log.debug("Pass: {} -> {}", passer.getName(), receiver.getName());
    }

    public void createInterceptionEvent(MatchRuntime rt, Match match, int minute,
                                       Player passer, Player interceptor) {
        String interceptorSide = resolveTeamSide(match, interceptor, rt);
        PassInterceptedEvent event = new PassInterceptedEvent(minute, rt.tick,
                0,
                passer != null ? passer.getId() : 0, passer != null ? passer.getName() : "Unknown",
                interceptor.getId(), interceptor.getName(), interceptorSide,
                "Interception by " + interceptor.getName(),
                50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Interception: {} preseca pas od {}", interceptor.getName(), passer != null ? passer.getName() : "unknown");
    }

    public void createDuelEvent(MatchRuntime rt, Match match, int minute,
                               Player player1, Player player2, DuelResolver.DuelResult result) {
        String teamSide = resolveTeamSide(match, result.attackerWins() ? player1 : player2, rt);
        DuelEvent event = new DuelEvent(minute, rt.tick,
                player1.getId(), player1.getName(),
                player2.getId(), player2.getName(),
                teamSide, result.attackerWins(),
                "DUEL");
        rt.runtimeEvents.add(event);
        log.debug("Duel: {} vs {} - Won by {}", player1.getName(), player2.getName(),
                result.attackerWins() ? player1.getName() : player2.getName());
    }

    public void createChanceEvent(MatchRuntime rt, Match match, int minute, Player player, boolean dangerous) {
        String teamSide = resolveTeamSide(match, player, rt);
        PossessionStartEvent event = new PossessionStartEvent(minute, rt.tick, 0,
                teamSide, (dangerous ? "Dangerous chance" : "Chance") + " for " + player.getName(),
                50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Chance: {} dangerous={}", player.getName(), dangerous);
    }

    public void createOffsideEvent(MatchRuntime rt, Match match, int minute, Player player) {
        if (player == null) return;
        String teamSide = resolveTeamSide(match, player, rt);
        OffsideEvent event = new OffsideEvent(minute, rt.tick, player.getId(), player.getName(), teamSide);
        rt.runtimeEvents.add(event);
        log.debug("Offside: {}", player.getName());
    }

    public void createShotSavedEvent(MatchRuntime rt, Match match, int minute,
                                     Player shooter, Player goalkeeper, double xG) {
        String teamSide = resolveTeamSide(match, shooter, rt);
        ShotSavedEvent event = new ShotSavedEvent(minute, rt.tick, 0,
                shooter.getId(), shooter.getName(), teamSide,
                goalkeeper != null ? goalkeeper.getId() : 0,
                goalkeeper != null ? goalkeeper.getName() : "Unknown",
                xG, "Shot saved by " + (goalkeeper != null ? goalkeeper.getName() : "goalkeeper"),
                50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Shot saved: {} -> {}", shooter.getName(), goalkeeper != null ? goalkeeper.getName() : "unknown");
    }

    public void createShotMissedEvent(MatchRuntime rt, Match match, int minute,
                                      Player shooter, double xG) {
        String teamSide = resolveTeamSide(match, shooter, rt);
        ShotMissedEvent event = new ShotMissedEvent(minute, rt.tick, 0,
                shooter.getId(), shooter.getName(), teamSide,
                xG, "Shot missed by " + shooter.getName(),
                50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Shot missed: {}", shooter.getName());
    }

    public void createDribbleEvent(MatchRuntime rt, Match match, int minute,
                                   Player dribbler) {
        String teamSide = resolveTeamSide(match, dribbler, rt);
        DribbleEvent event = new DribbleEvent(minute, rt.tick, 0,
                dribbler.getId(), dribbler.getName(), teamSide,
                0, "", "Dribble by " + dribbler.getName(),
                50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Dribble: {}", dribbler.getName());
    }

    public void createCornerEvent(MatchRuntime rt, Match match, int minute) {
        createCornerEvent(rt, match, minute, rt.lastTouchTeam, null);
    }

    public void createCornerEvent(MatchRuntime rt, Match match, int minute, String restartTeam, Player taker) {
        String side = resolveTeamSide(match, restartTeam);
        if (taker == null) {
            java.util.List<Player> team = resolvePlayers(rt, restartTeam);
            if (!team.isEmpty()) {
                taker = team.get((int) (Math.random() * team.size()));
            }
        }
        long takerId = taker != null ? taker.getId() : 0;
        String takerName = taker != null ? taker.getName() : "Unknown";
        SetPieceEvent event = new SetPieceEvent(minute, rt.tick, side,
                takerId, takerName, SetPieceEvent.SetPieceType.CORNER, 50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Corner kick");
    }

    public void createThrowInEvent(MatchRuntime rt, Match match, int minute) {
        createThrowInEvent(rt, match, minute, rt.lastTouchTeam, null);
    }

    public void createThrowInEvent(MatchRuntime rt, Match match, int minute, String restartTeam, Player taker) {
        String side = resolveTeamSide(match, restartTeam);
        if (taker == null) {
            java.util.List<Player> team = resolvePlayers(rt, restartTeam);
            if (!team.isEmpty()) {
                taker = team.get((int) (Math.random() * team.size()));
            }
        }
        long takerId = taker != null ? taker.getId() : 0;
        String takerName = taker != null ? taker.getName() : "Unknown";
        SetPieceEvent event = new SetPieceEvent(minute, rt.tick, side,
                takerId, takerName, SetPieceEvent.SetPieceType.THROW_IN, 50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Throw-in");
    }

    public void createGoalKickEvent(MatchRuntime rt, Match match, int minute) {
        createGoalKickEvent(rt, match, minute, rt.lastTouchTeam, null);
    }

    public void createGoalKickEvent(MatchRuntime rt, Match match, int minute, String restartTeam, Player goalkeeper) {
        String side = resolveTeamSide(match, restartTeam);
        if (goalkeeper == null) {
            java.util.List<Player> team = resolvePlayers(rt, restartTeam);
            goalkeeper = team.stream().filter(p -> p.getPosition() == Position.GK).findFirst().orElse(null);
        }
        long takerId = goalkeeper != null ? goalkeeper.getId() : 0;
        String takerName = goalkeeper != null ? goalkeeper.getName() : "Unknown";
        SetPieceEvent event = new SetPieceEvent(minute, rt.tick, side,
                takerId, takerName, SetPieceEvent.SetPieceType.GOAL_KICK, 50.0, 50.0);
        rt.runtimeEvents.add(event);
        log.debug("Goal kick");
    }

    public void createYellowCardEvent(MatchRuntime rt, Match match, int minute,
                                      Player player) {
        String teamSide = resolveTeamSide(match, player, rt);
        CardEvent event = new CardEvent(minute, rt.tick,
                player.getId(), player.getName(), teamSide, CardEvent.CardType.YELLOW);
        rt.runtimeEvents.add(event);
        log.debug("Yellow card: {}", player.getName());
    }

    public void createRedCardEvent(MatchRuntime rt, Match match, int minute,
                                   Player player) {
        String teamSide = resolveTeamSide(match, player, rt);
        CardEvent event = new CardEvent(minute, rt.tick,
                player.getId(), player.getName(), teamSide, CardEvent.CardType.RED);
        rt.runtimeEvents.add(event);
        log.debug("Red card: {}", player.getName());
    }

    public void createInjuryEvent(MatchRuntime rt, Match match, int minute,
                                  Player player) {
        String teamSide = resolveTeamSide(match, player, rt);
        InjuryEvent event = new InjuryEvent(minute, rt.tick,
                player.getId(), player.getName(), teamSide);
        rt.runtimeEvents.add(event);
        log.debug("Injury: {}", player.getName());
    }

    public void createSubstitutionEvent(MatchRuntime rt, Match match, int minute,
                                        Player playerOut, Player playerIn) {
        String teamSide = resolveTeamSide(match, playerOut, rt);
        SubstitutionEvent event = new SubstitutionEvent(minute, rt.tick,
                playerOut.getId(), playerOut.getName(),
                playerIn.getId(), playerIn.getName(), teamSide);
        rt.runtimeEvents.add(event);
        log.debug("Substitution: {} <- {}", playerIn.getName(), playerOut.getName());
    }

    private java.util.List<Player> resolvePlayers(MatchRuntime rt, String teamSide) {
        return "AWAY".equals(teamSide) ? rt.awayPlayers : rt.homePlayers;
    }
}
