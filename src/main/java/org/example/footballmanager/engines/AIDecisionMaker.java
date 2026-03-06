package org.example.footballmanager.engines;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Decision Maker za igrače sa loptom
 * 
 * Donosi inteligentne odluke:
 * - PASS: Pronađi best open teammate-a
 * - SHOT: Ako je blizu gola i ima prostora
 * - DRIBBLE: Ako je mali prostor za pas
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AIDecisionMaker {

    private final Random random = new Random();

    /**
     * Odluka koju akciju će igrač sa loptom izvršiti
     */
    public Decision makeDecision(Player player, MatchRuntime rt, Match match, int minute) {
        // Pronađi sve dostupne igrače za pas
        List<Player> teamPlayers = getTeammates(player, rt);
        
        // Proceni bliskost gola
        double goalDistance = estimateGoalDistance(player, rt, getTeam(player, rt));
        
        // Proceni pritisak beka
        List<Player> nearbyDefenders = getNearbyDefenders(player, rt, getTeam(player, rt));
        double defensivePressure = Math.min(1.0, nearbyDefenders.size() / 4.0); // Blazi pritisak, da napad ne odustaje prerano
        
        // Koeficijenti za svaku odluku
        double passScore = calculatePassScore(player, teamPlayers, defensivePressure);
        double shotScore = calculateShotScore(player, goalDistance, defensivePressure, rt, getTeam(player, rt));
        double dribbleScore = calculateDribbleScore(player, defensivePressure);

        boolean quickShotRole = player.getPosition() == Position.ATT
                || player.getPosition() == Position.WNG
                || player.getPosition() == Position.MID;
        if (quickShotRole && goalDistance <= 20 && defensivePressure <= 0.65) {
            return new Decision(ActionType.SHOT, null);
        }
        if (player.getPosition() == Position.ATT && goalDistance <= 26 && defensivePressure <= 0.45) {
            return new Decision(ActionType.SHOT, null);
        }
        
        // Normalizuj skore
        double totalScore = passScore + shotScore + dribbleScore;
        if (totalScore == 0) totalScore = 1; // Izbegni deljenje nulom
        
        passScore /= totalScore;
        shotScore /= totalScore;
        dribbleScore /= totalScore;
        
        log.debug("Decision for {}: PASS={:.2f}, SHOT={:.2f}, DRIBBLE={:.2f}",
                player.getName(), passScore, shotScore, dribbleScore);
        
        // Izberi akciju na osnovu skore-a
        ActionType action;
        Player targetPlayer = null;
        
        if (shotScore > passScore && shotScore > dribbleScore && shotScore > 0.24 && goalDistance < 38) {
            action = ActionType.SHOT;
        } else if (dribbleScore > passScore && dribbleScore > shotScore && dribbleScore > 0.36) {
            action = ActionType.DRIBBLE;
        } else {
            action = ActionType.PASS;
            // Pronađi best teammate za pas
            targetPlayer = selectBestPassReceiver(player, teamPlayers, rt, getTeam(player, rt));
        }
        
        return new Decision(action, targetPlayer);
    }

    /**
     * Izračunaj score za pass akciju
     */
    private double calculatePassScore(Player player, List<Player> teammates, double defensivePressure) {
        if (teammates.isEmpty()) {
            return 0.0; // Nema dostupnih igrača za pas
        }
        
        // Osnovna vrednost
        double baseScore = 1.0;
        
        // Boostuj ako je malo pritiska
        if (defensivePressure < 0.3) {
            baseScore += 0.5; // Malo pritiska, lako pas
        }
        
        // Skill igrača
        baseScore += (player.getSkills().getPassing() / 20.0);
        
        return baseScore;
    }

    /**
     * Izračunaj score za shot akciju
     */
    private double calculateShotScore(Player player, double goalDistance, double defensivePressure, 
                                     MatchRuntime rt, String team) {
        if (player.getPosition() == Position.GK) {
            return 0.0;
        }

        // SHOT je dobar samo ako:
        // 1. Igrač je blizu gola (< 30 metara)
        // 2. Ima malo pritiska
        // 3. Igrač je striker ili attacker
        
        double baseScore = 0.0;
        
        // Distanca od gola
        if (goalDistance < 8) {
            baseScore = 1.0;
        } else if (goalDistance < 16) {
            baseScore = 0.86;
        } else if (goalDistance < 22) {
            baseScore = 0.58;
        } else if (goalDistance < 28) {
            baseScore = 0.28;
        } else if (goalDistance < 34) {
            baseScore = 0.12;
        } else {
            return 0.0;
        }
        
        // Pozicija igrača
        if (player.getPosition() == Position.ATT) {
            baseScore += 0.28;
        } else if (player.getPosition() == Position.MID) {
            baseScore += 0.10;
        } else if (player.getPosition() == Position.WNG) {
            baseScore += 0.14;
        } else if (player.getPosition() == Position.DEF) {
            baseScore -= 0.34;
        }
        
        // Skill igrača
        baseScore += (player.getSkills().getStriker() / 30.0);
        
        // Ako je mali pritisak, više šansi za šut
        if (defensivePressure < 0.2) {
            baseScore *= 1.28;
        } else if (defensivePressure > 0.7) {
            baseScore *= 0.72;
        }
        
        return Math.max(0, baseScore);
    }

    /**
     * Izračunaj score za dribble akciju
     */
    private double calculateDribbleScore(Player player, double defensivePressure) {
        // Dribble je dobar ako:
        // 1. Ima malo mesta za pas (visok pritisak)
        // 2. Igrač ima dobru tehniku
        
        double baseScore = 0.0;
        
        // Ako je veliki pritisak, dribel je opcija
        if (defensivePressure > 0.5) {
            baseScore = defensivePressure * 0.95; // Proporcionalno pritisk
        }
        
        // Skill igrača
        baseScore += (player.getSkills().getTechnique() / 20.0);
        baseScore += (player.getSkills().getPace() / 30.0); // Manja uticaj
        
        return baseScore;
    }

    /**
     * Pronađi best teammate za pas
     */
    private Player selectBestPassReceiver(Player passer, List<Player> teammates, 
                                          MatchRuntime rt, String team) {
        if (teammates.isEmpty()) {
            return null;
        }
        
        // Sortiraj timeje po scores
        List<PlayerScore> scores = teammates.stream()
                .map(t -> new PlayerScore(
                        t,
                        calculatePassTargetScore(passer, t, rt, team)
                ))
                .sorted(Comparator.comparingDouble(PlayerScore::getScore).reversed())
                .toList();
        
        return scores.isEmpty() ? null : scores.get(0).player;
    }

    /**
     * Izračunaj score za igrača kao cil pasa
     */
    private double calculatePassTargetScore(Player passer, Player receiver, 
                                           MatchRuntime rt, String team) {
        double score = 0.0;
        
        // Receiver bi trebao biti blizu i u dobroj poziciji
        // Striker > Midfielder > Defender
        if (receiver.getPosition() == Position.ATT) {
            score += 1.0;
        } else if (receiver.getPosition() == Position.MID) {
            score += 0.7;
        } else if (receiver.getPosition() == Position.WNG) {
            score += 0.6;
        } else if (receiver.getPosition() == Position.DEF) {
            score += 0.2;
        }
        
        // Skill receiver-a za primanje lopte
        score += (receiver.getSkills().getPassing() / 30.0); // Reception ability
        
        return Math.max(0, score);
    }

    /**
     * Proceni distancu od gola
     */
    private double estimateGoalDistance(Player player, MatchRuntime rt, String team) {
        PlayerPositionDTO position = getPlayerPosition(player, rt);
        if (position == null) {
            return 30.0;
        }

        double goalX = "HOME".equals(team) ? 100.0 : 0.0;
        double goalY = 50.0;
        double dx = goalX - position.getX();
        double dy = goalY - position.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Pronađi sve dostupne igrače za pas (timeje osim GK)
     */
    private List<Player> getTeammates(Player player, MatchRuntime rt) {
        String team = getTeam(player, rt);
        List<Player> teammates = team.equals("HOME") ? rt.homePlayers : rt.awayPlayers;
        
        return teammates.stream()
                .filter(p -> !p.equals(player))
                .filter(p -> p.getPosition() != Position.GK) // Bez golmana
                .toList();
    }

    /**
     * Pronađi sve beke blizu igrača sa loptom
     */
    private List<Player> getNearbyDefenders(Player player, MatchRuntime rt, String team) {
        String oppositeTeam = team.equals("HOME") ? "AWAY" : "HOME";
        List<Player> defenders = oppositeTeam.equals("HOME") ? rt.homePlayers : rt.awayPlayers;

        PlayerPositionDTO playerPos = getPlayerPosition(player, rt);
        if (playerPos == null) {
            return List.of();
        }

        return defenders.stream()
                .filter(defender -> defender.getPosition() != Position.GK)
                .map(defender -> Map.entry(defender, getPlayerPosition(defender, rt)))
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> distance(playerPos, entry.getValue()) <= 12.0)
                .sorted(Comparator.comparingDouble(entry -> distance(playerPos, entry.getValue())))
                .map(Map.Entry::getKey)
                .limit(3)
                .toList();
    }

    private PlayerPositionDTO getPlayerPosition(Player player, MatchRuntime rt) {
        return rt.players.stream()
                .filter(pos -> pos.getId() == player.getId())
                .findFirst()
                .orElse(null);
    }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Pronađi tim kojem pripada igrač
     */
    private String getTeam(Player player, MatchRuntime rt) {
        if (rt.homePlayers.contains(player)) {
            return "HOME";
        } else if (rt.awayPlayers.contains(player)) {
            return "AWAY";
        }
        return "UNKNOWN";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════════

    @Data
    public static class Decision {
        private final ActionType action;
        private final Player targetPlayer;
    }

    public enum ActionType {
        PASS,     // Pas do timeja
        SHOT,     // Šut na gol
        DRIBBLE   // Dribel/Driving
    }

    @Data
    private static class PlayerScore {
        private final Player player;
        private final double score;
    }
}


