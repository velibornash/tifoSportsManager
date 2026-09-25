package org.example.footballmanager.newLogic.engine_v1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchRuntime;
import org.example.footballmanager.newLogic.model.Player;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Positional Defense Engine
 * 
 * Rukuje pozicionom obranom:
 * - Pronalaženje igrača koji pokrivaju određenu zonu
 * - Postavljanje defensive line-a
 * - Reagovanje na napade
 * 
 * Za sada - placeholder, može se proširiti
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PositionalDefense {

    /**
     * Pronađi najbližeg defendera igraču sa loptom
     */
    public Player findClosestDefender(Player attacker, MatchRuntime rt, String teamInPossession) {
        String defendingTeam = teamInPossession.equals("HOME") ? "AWAY" : "HOME";
        List<Player> defenders = defendingTeam.equals("HOME") ? rt.homePlayers : rt.awayPlayers;
        
        // Za sada, vrati slučajnog defendera
        // U budućnosti, koristi distancu od attacker-a
        return defenders.isEmpty() ? null : defenders.get((int)(Math.random() * defenders.size()));
    }

    /**
     * Pronađi sve igrače u određenoj zone
     */
    public List<Player> getPlayersInZone(double zoneX, double zoneY, double radius, 
                                        MatchRuntime rt, String team) {
        List<Player> teamPlayers = team.equals("HOME") ? rt.homePlayers : rt.awayPlayers;
        
        // Za sada, vrati sve igrače
        // U budućnosti, filtriraj po distanci
        return teamPlayers;
    }
}
