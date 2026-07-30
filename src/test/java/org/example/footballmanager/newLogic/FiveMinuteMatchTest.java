package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.MatchSimulator;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FiveMinuteMatchTest {

    @Test
    public void testFullMatchWithEventLog() {
        Team homeTeam = createTeam("HOME", "Red Team");
        Team awayTeam = createTeam("AWAY", "Blue Team");
        
        Match match = new Match();
        match.setId(1L);
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeFormation("4-3-3");
        match.setAwayFormation("4-3-3");
        
        MatchSimulator simulator = new MatchSimulator();
        MatchResult result = simulator.simulate(match);
        
        System.out.println("=== MATCH EVENT LOG ===");
        System.out.println("Home: " + homeTeam.name() + " vs Away: " + awayTeam.name());
        System.out.println("Score: " + result.homeGoals() + " - " + result.awayGoals());
        System.out.println();
        
        List<MatchEvent> events = result.events();
        System.out.println("Total events: " + events.size());
        System.out.println();
        
        int count = 0;
        for (MatchEvent event : events) {
            if (count < 100) {
                System.out.println(formatEvent(event));
            }
            count++;
        }
        
        if (count > 100) {
            System.out.println("... and " + (count - 100) + " more events");
        }
        
        System.out.println();
        System.out.println("=== MATCH STATISTICS ===");
        printStatistics(result);
        
        assertNotNull(result);
        assertTrue(events.size() > 0, "Match should have events");
    }
    
    private Team createTeam(String side, String name) {
        Team team = new Team();
        team.setId(side.equals("HOME") ? 1L : 2L);
        team.setName(name);
        
        List<Player> players = List.of(
            createPlayer(side, 1, "GK " + side, Position.GK, 15, 12, 14, 8, 10, 8, 12, 5),
            createPlayer(side, 2, "LB " + side, Position.DEF, 8, 14, 16, 13, 11, 10, 12, 5),
            createPlayer(side, 3, "CB " + side, Position.DEF, 6, 15, 17, 10, 10, 8, 11, 6),
            createPlayer(side, 4, "CB " + side, Position.DEF, 7, 14, 16, 11, 10, 9, 12, 5),
            createPlayer(side, 5, "RB " + side, Position.DEF, 8, 13, 15, 14, 11, 10, 13, 5),
            createPlayer(side, 6, "CM " + side, Position.MID, 7, 15, 12, 12, 15, 16, 15, 8),
            createPlayer(side, 7, "CM " + side, Position.MID, 8, 14, 11, 13, 14, 15, 14, 9),
            createPlayer(side, 8, "CM " + side, Position.MID, 7, 13, 12, 12, 16, 17, 15, 10),
            createPlayer(side, 9, "LW " + side, Position.WNG, 6, 12, 8, 17, 16, 12, 14, 14),
            createPlayer(side, 10, "RW " + side, Position.WNG, 6, 12, 8, 16, 15, 13, 14, 15),
            createPlayer(side, 11, "ST " + side, Position.ATT, 7, 13, 6, 14, 14, 11, 13, 18)
        );
        
        team.selectLineup(players, List.of());
        
        return team;
    }
    
    private Player createPlayer(String side, int num, String name, Position pos, 
            int goalkeeping, int stamina, int defending, int pace, 
            int technique, int playmaking, int passing, int shooting) {
        Player player = new Player();
        player.setId(side.equals("HOME") ? num : num + 100L);
        player.setName(name);
        player.setPosition(pos);
        
        Skills skills = new Skills();
        skills.setSkill(SkillName.GOALKEEPER, goalkeeping);
        skills.setSkill(SkillName.STAMINA, stamina);
        skills.setSkill(SkillName.DEFENDER, defending);
        skills.setSkill(SkillName.PACE, pace);
        skills.setSkill(SkillName.TECHNIQUE, technique);
        skills.setSkill(SkillName.PLAYMAKER, playmaking);
        skills.setSkill(SkillName.PASSING, passing);
        skills.setSkill(SkillName.STRIKER, shooting);
        player.setSkills(skills);
        
        return player;
    }
    
    private String formatEvent(MatchEvent event) {
        return String.format("[%d' tick:%d] %s: %s", 
            event.minute(), 
            event.tick(),
            event.type(),
            getEventDescription(event));
    }
    
    private String getEventDescription(MatchEvent event) {
        return switch (event) {
            case MatchStartEvent e -> "Match started: " + e.homeTeamName() + " vs " + e.awayTeamName();
            case MatchEndEvent e -> "Match ended";
            case GoalEvent e -> "GOAL! " + e.scorerName() + " (" + e.teamSide() + ") xG=" + String.format("%.2f", e.xG());
            case ShotEvent e -> e.shooterName() + " shoots (xG=" + String.format("%.2f", e.xG()) + ") " + (e.isGoal() ? "GOAL!" : e.onTarget() ? "on target" : "off target");
            case PassEvent e -> e.passerName() + " passes to " + e.receiverName() + (e.completed() ? " (completed)" : " (incomplete)");
            case DuelEvent e -> e.player1Name() + " vs " + e.player2Name() + " (" + e.duelType() + ") - " + (e.attackerWon() ? "attacker wins" : "defender wins");
            case FoulEvent e -> "Foul by " + e.takerName() + " on " + e.victimName();
            case CardEvent e -> e.playerName() + " receives " + (e.cardType() == CardEvent.CardType.RED ? "RED CARD" : "YELLOW CARD");
            case OffsideEvent e -> e.playerName() + " is offside";
            case SetPieceEvent e -> e.setPieceType() + " for " + e.teamSide();
            case PenaltyEvent e -> "PENALTY for " + e.teamSide() + " - " + e.takerName();
            case InjuryEvent e -> e.playerName() + " is injured";
            case SubstitutionEvent e -> e.playerInName() + " replaces " + e.playerOutName();
            default -> event.toString();
        };
    }
    
    private void printStatistics(MatchResult result) {
        System.out.println("Home Goals: " + result.homeGoals());
        System.out.println("Away Goals: " + result.awayGoals());
        System.out.println("Total Events: " + result.events().size());
    }
}
