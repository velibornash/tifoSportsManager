package org.example.footballtextmanager;

import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSTableEntry;
import org.example.footballtextmanager.model.CSTeam;
import org.example.footballtextmanager.model.*;

import java.util.List;

/**
 * Mapira JPA entitete u CS POJO-e.
 * Koristi se SAMO jednom — pri pokretanju igre (startNewGame).
 */
public final class CSMapper {

    private CSMapper() {}

    public static CSTeam toCSTeam(CTeam CTeam) {
        return CSTeam.builder()
                .id(CTeam.getId())
                .name(CTeam.getName())
                .budget(CTeam.getBudget() != null ? CTeam.getBudget() : 500_000)
                .reputation(CTeam.getReputation() != null ? CTeam.getReputation() : 50)
                .stadiumName(CTeam.getCsStadium() != null ? CTeam.getCsStadium().getName() : "Unknown")
                .stadiumCapacity(CTeam.getCsStadium() != null && CTeam.getCsStadium().getCapacity() != null
                        ? CTeam.getCsStadium().getCapacity() : 5000)
                .formation("4-4-2")
                .build();
    }

    public static CSPlayer toCSPlayer(CPlayer CPlayer) {
        CSSkills s = CPlayer.getCSSkills();
        // Veštine žive na skali 1-20 (seeder upisuje 4-18).Fallback-i su ranije bili 50, što je
        // skala 0-100 — igrač bez CSSkills bi zato bio "bolji" od svakog stvarnog igrača, jer
        // getPositionalSkill za takvog igrača vraca 115 umesto ~30. 10 je srednja vrednost
        // stvarne skale, pa je igrač bez veština prosečan, ne supermoćan.
        final int noSkill = 10;
        CSPlayer player = CSPlayer.builder()
                .id(CPlayer.getId())
                .name(CPlayer.getName())
                .position(CPlayer.getCSPosition() != null ? CPlayer.getCSPosition().name() : "MID")
                .age(CPlayer.getAge())
                .form(CPlayer.getForm())
                .fatigue(s != null ? s.getFatigue() : 0)
                .talent(CPlayer.getTalent())
                .stamina(s != null ? s.getStamina() : noSkill)
                .goalkeeper(s != null ? s.getGoalkeeper() : noSkill)
                .defending(s != null ? s.getDefender() : noSkill)
                .pace(s != null ? s.getPace() : noSkill)
                .technique(s != null ? s.getTechnique() : noSkill)
                .playmaker(s != null ? s.getPlaymaker() : noSkill)
                .passing(s != null ? s.getPassing() : noSkill)
                .shooting(s != null ? s.getStriker() : noSkill)
                .goals(0)
                .assists(0)
                .value(CPlayer.getPlayerValue())
                .earnings(CPlayer.getEarnings())
                .height(CPlayer.getHeight())
                .weight(CPlayer.getWeight())
                .build();
        // Ne kopira se ocena iz baze nego se racuna iz mapiranih veština, da bi se ocena i
        // veštine kretale zajedno. Za seederom upisane igrace je rezultat prakticno identican.
        player.setRating(player.calculateRating());
        return player;
    }

    public static List<CSPlayer> toCSPlayers(List<CPlayer> CPlayers) {
        return CPlayers.stream().map(CSMapper::toCSPlayer).toList();
    }

    public static CSTableEntry toCSTableEntry(CSCompetitionEntry entry) {
        int goalsScored = entry.getGoalsScored() != null ? entry.getGoalsScored() : 0;
        int goalsConceded = entry.getGoalsConceded() != null ? entry.getGoalsConceded() : 0;
        return CSTableEntry.builder()
                .teamId(entry.getCTeam().getId())
                .teamName(entry.getCTeam().getName())
                .position(entry.getPosition() != null ? entry.getPosition() : 0)
                .points(entry.getPoints() != null ? entry.getPoints() : 0)
                .wins(entry.getWins() != null ? entry.getWins() : 0)
                .draws(entry.getDraws() != null ? entry.getDraws() : 0)
                .losses(entry.getLosses() != null ? entry.getLosses() : 0)
                .goalsScored(goalsScored)
                .goalsConceded(goalsConceded)
                .played(Math.max(0, (entry.getWins() != null ? entry.getWins() : 0)
                        + (entry.getDraws() != null ? entry.getDraws() : 0)
                        + (entry.getLosses() != null ? entry.getLosses() : 0)))
                .build();
    }
}
