package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.repository.MatchEventRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Realistični Event Generator
 * 
 * Kreira sve event-e koji se dešavaju tokom meča:
 * - PassEvent (novi)
 * - InterceptionEvent (novi)
 * - DuelEvent (novi)
 * - GoalEvent (postojeći)
 * - ShotEvent (postojeći)
 * - CornerEvent, ThrowInEvent, itd.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealisticEventGenerator {

    private final MatchEventRepository matchEventRepository;

    /**
     * Kreiraj MatchStartEvent
     */
    public void createMatchStartEvent(MatchRuntime rt, Match match) {
        MatchStartEvent event = new MatchStartEvent();
        event.setMatch(match);
        event.setMinute(0);
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
        event.setMatch(match);
        event.setMinute(90);
        
        rt.runtimeEvents.add(event);
        log.debug("Match ended: {} {} - {} {}", 
                match.getHomeTeam().getName(), rt.homeGoals,
                rt.awayGoals, match.getAwayTeam().getName());
    }

    /**
     * Kreiraj GoalEvent
     */
    public void createGoalEvent(MatchRuntime rt, Match match, int minute, 
                                Player scorer, Player assistant) {
        GoalEvent event = new GoalEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setScorer(scorer);
        event.setAssistant(assistant);
        event.setScored(true);
        
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
    }

    /**
     * Kreiraj PassEvent (NOVI)
     */
    public void createPassEvent(MatchRuntime rt, Match match, int minute,
                               Player passer, Player receiver) {
        PassEvent event = new PassEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setPasser(passer);
        event.setReceiver(receiver);
        event.setTeam(rt.homePlayers.contains(passer) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Pass: {} → {}", passer.getName(), receiver.getName());
    }

    /**
     * Kreiraj InterceptionEvent (NOVI)
     */
    public void createInterceptionEvent(MatchRuntime rt, Match match, int minute,
                                       Player passer, Player receiver) {
        InterceptionEvent event = new InterceptionEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setInterceptor(receiver);
        event.setOriginalPasser(passer);
        event.setTeam(rt.homePlayers.contains(receiver) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Interception: {} preseca pas od {}", receiver.getName(), passer.getName());
    }

    /**
     * Kreiraj DuelEvent (NOVI)
     */
    public void createDuelEvent(MatchRuntime rt, Match match, int minute,
                               Player player1, Player player2, DuelResolver.DuelResult result) {
        DuelEvent event = new DuelEvent();
        event.setMatch(match);
        event.setMinute(minute);
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
        event.setMatch(match);
        event.setMinute(minute);
        event.setPlayer(player);
        event.setDangerous(dangerous);
        event.setTeam(rt.homePlayers.contains(player) ? match.getHomeTeam() : match.getAwayTeam());

        rt.runtimeEvents.add(event);
        log.debug("Chance: {} dangerous={}", player.getName(), dangerous);
    }

    /**
     * Kreiraj ShotOnTargetEvent
     */
    public void createShotSavedEvent(MatchRuntime rt, Match match, int minute,
                                     Player shooter, Player goalkeeper) {
        ShotOnTargetEvent event = new ShotOnTargetEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setShooter(shooter);
        event.setTeam(rt.homePlayers.contains(shooter) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Shot saved: {} → {}", shooter.getName(), goalkeeper.getName());
    }

    /**
     * Kreiraj ShotOffTargetEvent
     */
    public void createShotMissedEvent(MatchRuntime rt, Match match, int minute,
                                      Player shooter) {
        ShotOffTargetEvent event = new ShotOffTargetEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setShooter(shooter);
        event.setTeam(rt.homePlayers.contains(shooter) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Shot missed: {}", shooter.getName());
    }

    /**
     * Kreiraj DribbleEvent (NOVI)
     */
    public void createDribbleEvent(MatchRuntime rt, Match match, int minute,
                                   Player dribbler) {
        DribbleEvent event = new DribbleEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setDribbler(dribbler);
        event.setTeam(rt.homePlayers.contains(dribbler) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Dribble: {}", dribbler.getName());
    }

    /**
     * Kreiraj CornerEvent
     */
    public void createCornerEvent(MatchRuntime rt, Match match, int minute) {
        CornerEvent event = new CornerEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setTeam(rt.lastTouchTeam.equals("HOME") ? match.getHomeTeam() : match.getAwayTeam());
        // Pronađi random igrača koji će biti krenuo korner
        List<Player> team = rt.lastTouchTeam.equals("HOME") ? rt.homePlayers : rt.awayPlayers;
        if (!team.isEmpty()) {
            event.setPlayer(team.get((int)(Math.random() * team.size())));
        }
        
        rt.runtimeEvents.add(event);
        log.debug("Corner kick");
    }

    /**
     * Kreiraj ThrowInEvent
     */
    public void createThrowInEvent(MatchRuntime rt, Match match, int minute) {
        ThrowInEvent event = new ThrowInEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setTeam(rt.lastTouchTeam.equals("HOME") ? match.getHomeTeam() : match.getAwayTeam());
        // Pronađi random igrača koji će biti izvršio throw-in
        List<Player> team = rt.lastTouchTeam.equals("HOME") ? rt.homePlayers : rt.awayPlayers;
        if (!team.isEmpty()) {
            event.setTaker(team.get((int)(Math.random() * team.size())));
        }
        
        rt.runtimeEvents.add(event);
        log.debug("Throw-in");
    }

    /**
     * Kreiraj GoalKickEvent
     */
    public void createGoalKickEvent(MatchRuntime rt, Match match, int minute) {
        GoalKickEvent event = new GoalKickEvent();
        event.setMatch(match);
        event.setMinute(minute);
        event.setTeam(rt.lastTouchTeam.equals("HOME") ? match.getHomeTeam() : match.getAwayTeam());
        // Postavi golmana koji izvršava kick
        String team = rt.lastTouchTeam;
        Player goalkeeper = team.equals("HOME") 
                ? rt.homePlayers.stream().filter(p -> p.getPosition() == Position.GK).findFirst().orElse(null)
                : rt.awayPlayers.stream().filter(p -> p.getPosition() == Position.GK).findFirst().orElse(null);
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
        event.setMatch(match);
        event.setMinute(minute);
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
        event.setMatch(match);
        event.setMinute(minute);
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
        event.setMatch(match);
        event.setMinute(minute);
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
        event.setMatch(match);
        event.setMinute(minute);
        event.setPlayerOut(playerOut);
        event.setPlayerIn(playerIn);
        event.setTeam(rt.homePlayers.contains(playerOut) ? match.getHomeTeam() : match.getAwayTeam());
        
        rt.runtimeEvents.add(event);
        log.debug("Substitution: {} ← {}", playerIn.getName(), playerOut.getName());
    }
}
