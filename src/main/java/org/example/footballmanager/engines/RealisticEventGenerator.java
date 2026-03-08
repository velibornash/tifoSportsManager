package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.repository.MatchEventRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Realistic Event Generator
 *
 * Creates match events for the realistic flow:
 * - PassEvent (novi)
 * - InterceptionEvent (novi)
 * - DuelEvent (novi)
 * - GoalEvent
 * - ShotEvent
 * - CornerEvent, ThrowInEvent, itd.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealisticEventGenerator {

    private final MatchEventRepository matchEventRepository;

    private void setupEvent(MatchEvent event, Match match, int minute, MatchRuntime rt) {
        event.setMatch(match);
        event.setMinute(minute);
        event.setTick(rt.tick);
    }

    private Team resolveTeam(Match match, String teamSide) {
        return "AWAY".equals(teamSide) ? match.getAwayTeam() : match.getHomeTeam();
    }

    private List<Player> resolvePlayers(MatchRuntime rt, String teamSide) {
        return "AWAY".equals(teamSide) ? rt.awayPlayers : rt.homePlayers;
    }

    /**
     * Kreiraj MatchStartEvent
     */
    public void createMatchStartEvent(MatchRuntime rt, Match match) {
        MatchStartEvent event = new MatchStartEvent();
        setupEvent(event, match, 0, rt);
        event.setHomeTeamName(match.getHomeTeam().getName());
        event.setAwayTeamName(match.getAwayTeam().getName());
        
        rt.runtimeEvents.add(event);
        log.debug("Match started: {} vs {}", match.getHomeTeam().getName(), match.getAwayTeam().getName());
    }

    /**
     * Kreiraj MatchEndedEvent
     */
    public void createMatchEndedEvent(MatchRuntime rt, Match match) {
        MatchEndedEvent event = new MatchEndedEvent();
        setupEvent(event, match, 90, rt);
        
        rt.runtimeEvents.add(event);
        log.debug("Match ended: {} {} - {} {}", 
                match.getHomeTeam().getName(), rt.homeGoals,
                rt.awayGoals, match.getAwayTeam().getName());
    }

    /**
     * Kreiraj GoalEvent
     */
    public GoalEvent createGoalEvent(MatchRuntime rt, Match match, int minute,
                                Player scorer, Player assistant, double xG) {
        GoalEvent event = new GoalEvent();
        setupEvent(event, match, minute, rt);
        event.setScorer(scorer);
        event.setAssistant(assistant);
        event.setScored(true);
        event.setXG(xG);
        
        // Postavi tim
        if (rt.homePlayers.contains(scorer)) {
            event.setTeam(match.getHomeTeam());
            event.setScoreAfterGoal(rt.homeGoals + " - " + rt.awayGoals);
        } else {
            event.setTeam(match.getAwayTeam());
            event.setScoreAfterGoal(rt.homeGoals + " - " + rt.awayGoals);
        }
        
        rt.runtimeEvents.add(event);
        rt.runtimeGoals.add(event);
        log.debug("GOAL! {} scored in minute {}", scorer.getName(), minute);
        return event;
    }

    /**
     * Kreiraj PassEvent (NOVI)
     */
    public void createPassEvent(MatchRuntime rt, Match match, int minute,
                               Player passer, Player receiver) {
        PassEvent event = new PassEvent();
        setupEvent(event, match, minute, rt);
        event.setPasser(passer);
        event.setReceiver(receiver);
        event.setTeam(rt.homePlayers.contains(passer) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Pass: {} -> {}", passer.getName(), receiver.getName());
    }

    /**
     * Kreiraj InterceptionEvent (NOVI)
     */
    public void createInterceptionEvent(MatchRuntime rt, Match match, int minute,
                                       Player passer, Player receiver) {
        InterceptionEvent event = new InterceptionEvent();
        setupEvent(event, match, minute, rt);
        event.setInterceptor(receiver);
        event.setOriginalPasser(passer);
        event.setTeam(rt.homePlayers.contains(receiver) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Interception: {} preseca pas od {}", receiver.getName(), passer != null ? passer.getName() : "unknown");
    }

    /**
     * Kreiraj DuelEvent (NOVI)
     */
    public void createDuelEvent(MatchRuntime rt, Match match, int minute,
                               Player player1, Player player2, DuelResolver.DuelResult result) {
        DuelEvent event = new DuelEvent();
        setupEvent(event, match, minute, rt);
        event.setPlayer1(player1);
        event.setPlayer2(player2);
        event.setWinner(result.isWon() ? player1.getName() : player2.getName());
        Player winningPlayer = result.isWon() ? player1 : player2;
        event.setTeam(rt.homePlayers.contains(winningPlayer) ? match.getHomeTeam() : match.getAwayTeam());

        rt.runtimeEvents.add(event);
        log.debug("Duel: {} vs {} - Winner: {}", player1.getName(), player2.getName(), event.getWinner());
    }

    public void createChanceEvent(MatchRuntime rt, Match match, int minute, Player player, boolean dangerous) {
        ChanceEvent event = new ChanceEvent();
        setupEvent(event, match, minute, rt);
        event.setPlayer(player);
        event.setDangerous(dangerous);
        event.setTeam(rt.homePlayers.contains(player) ? match.getHomeTeam() : match.getAwayTeam());

        rt.runtimeEvents.add(event);
        log.debug("Chance: {} dangerous={}", player.getName(), dangerous);
    }

    public void createOffsideEvent(MatchRuntime rt, Match match, int minute, Player player) {
        OffsideEvent event = new OffsideEvent();
        setupEvent(event, match, minute, rt);
        event.setPlayer(player);

        rt.runtimeEvents.add(event);
        log.debug("Offside: {}", player != null ? player.getName() : "unknown");
    }

    /**
     * Kreiraj ShotOnTargetEvent
     */
    public void createShotSavedEvent(MatchRuntime rt, Match match, int minute,
                                     Player shooter, Player goalkeeper, double xG) {
        ShotOnTargetEvent event = new ShotOnTargetEvent();
        setupEvent(event, match, minute, rt);
        event.setShooter(shooter);
        event.setTeam(rt.homePlayers.contains(shooter) ? match.getHomeTeam() : match.getAwayTeam());
        event.setXG(xG);
        
        rt.runtimeEvents.add(event);
        log.debug("Shot saved: {} -> {}", shooter.getName(), goalkeeper.getName());
    }

    /**
     * Kreiraj ShotOffTargetEvent
     */
    public void createShotMissedEvent(MatchRuntime rt, Match match, int minute,
                                      Player shooter, double xG) {
        ShotOffTargetEvent event = new ShotOffTargetEvent();
        setupEvent(event, match, minute, rt);
        event.setShooter(shooter);
        event.setTeam(rt.homePlayers.contains(shooter) ? match.getHomeTeam() : match.getAwayTeam());
        event.setXG(xG);
        
        rt.runtimeEvents.add(event);
        log.debug("Shot missed: {}", shooter.getName());
    }

    /**
     * Kreiraj DribbleEvent (NOVI)
     */
    public void createDribbleEvent(MatchRuntime rt, Match match, int minute,
                                   Player dribbler) {
        DribbleEvent event = new DribbleEvent();
        setupEvent(event, match, minute, rt);
        event.setDribbler(dribbler);
        event.setTeam(rt.homePlayers.contains(dribbler) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Dribble: {}", dribbler.getName());
    }

    /**
     * Kreiraj CornerEvent
     */
    public void createCornerEvent(MatchRuntime rt, Match match, int minute) {
        createCornerEvent(rt, match, minute, rt.lastTouchTeam, null);
    }

    public void createCornerEvent(MatchRuntime rt, Match match, int minute, String restartTeam, Player taker) {
        CornerEvent event = new CornerEvent();
        setupEvent(event, match, minute, rt);
        event.setTeam(resolveTeam(match, restartTeam));
        List<Player> team = resolvePlayers(rt, restartTeam);
        if (taker != null) {
            event.setPlayer(taker);
        } else if (!team.isEmpty()) {
            event.setPlayer(team.get((int) (Math.random() * team.size())));
        }
        
        rt.runtimeEvents.add(event);
        log.debug("Corner kick");
    }

    /**
     * Kreiraj ThrowInEvent
     */
    public void createThrowInEvent(MatchRuntime rt, Match match, int minute) {
        createThrowInEvent(rt, match, minute, rt.lastTouchTeam, null);
    }

    public void createThrowInEvent(MatchRuntime rt, Match match, int minute, String restartTeam, Player taker) {
        ThrowInEvent event = new ThrowInEvent();
        setupEvent(event, match, minute, rt);
        event.setTeam(resolveTeam(match, restartTeam));
        List<Player> team = resolvePlayers(rt, restartTeam);
        if (taker != null) {
            event.setTaker(taker);
        } else if (!team.isEmpty()) {
            event.setTaker(team.get((int) (Math.random() * team.size())));
        }
        
        rt.runtimeEvents.add(event);
        log.debug("Throw-in");
    }

    /**
     * Kreiraj GoalKickEvent
     */
    public void createGoalKickEvent(MatchRuntime rt, Match match, int minute) {
        createGoalKickEvent(rt, match, minute, rt.lastTouchTeam, null);
    }

    public void createGoalKickEvent(MatchRuntime rt, Match match, int minute, String restartTeam, Player goalkeeper) {
        GoalKickEvent event = new GoalKickEvent();
        setupEvent(event, match, minute, rt);
        event.setTeam(resolveTeam(match, restartTeam));
        if (goalkeeper == null) {
            List<Player> team = resolvePlayers(rt, restartTeam);
            goalkeeper = team.stream().filter(p -> p.getPosition() == Position.GK).findFirst().orElse(null);
        }
        event.setGoalkeeper(goalkeeper);
        
        rt.runtimeEvents.add(event);
        log.debug("Goal kick");
    }

    /**
     * Kreiraj YellowCardEvent
     */
    public void createYellowCardEvent(MatchRuntime rt, Match match, int minute,
                                      Player player) {
        YellowCardEvent event = new YellowCardEvent();
        setupEvent(event, match, minute, rt);
        event.setPlayer(player);
        event.setTeam(rt.homePlayers.contains(player) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Yellow card: {}", player.getName());
    }

    /**
     * Kreiraj RedCardEvent
     */
    public void createRedCardEvent(MatchRuntime rt, Match match, int minute,
                                   Player player) {
        RedCardEvent event = new RedCardEvent();
        setupEvent(event, match, minute, rt);
        event.setPlayer(player);
        event.setTeam(rt.homePlayers.contains(player) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Red card: {}", player.getName());
    }

    /**
     * Kreiraj InjuryEvent
     */
    public void createInjuryEvent(MatchRuntime rt, Match match, int minute,
                                  Player player) {
        InjuryEvent event = new InjuryEvent();
        setupEvent(event, match, minute, rt);
        event.setPlayer(player);
        
        rt.runtimeEvents.add(event);
        log.debug("Injury: {}", player.getName());
    }

    /**
     * Kreiraj SubstitutionEvent
     */
    public void createSubstitutionEvent(MatchRuntime rt, Match match, int minute,
                                        Player playerOut, Player playerIn) {
        SubstitutionEvent event = new SubstitutionEvent();
        setupEvent(event, match, minute, rt);
        event.setPlayerOut(playerOut);
        event.setPlayerIn(playerIn);
        event.setTeam(rt.homePlayers.contains(playerOut) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Substitution: {} <- {}", playerIn.getName(), playerOut.getName());
    }
}
