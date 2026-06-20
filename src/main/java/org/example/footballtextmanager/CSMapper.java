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
        return CSPlayer.builder()
                .id(CPlayer.getId())
                .name(CPlayer.getName())
                .position(CPlayer.getCSPosition() != null ? CPlayer.getCSPosition().name() : "MID")
                .age(CPlayer.getAge())
                .rating(CPlayer.getRating())
                .form(CPlayer.getForm())
                .fatigue(s != null ? s.getFatigue() : 0)
                .talent(CPlayer.getTalent())
                .stamina(s != null ? s.getStamina() : 50)
                .goalkeeper(s != null ? s.getGoalkeeper() : 10)
                .defending(s != null ? s.getDefender() : 50)
                .pace(s != null ? s.getPace() : 50)
                .technique(s != null ? s.getTechnique() : 50)
                .playmaker(s != null ? s.getPlaymaker() : 50)
                .passing(s != null ? s.getPassing() : 50)
                .shooting(s != null ? s.getStriker() : 50)
                .goals(0)
                .assists(0)
                .value(CPlayer.getPlayerValue())
                .earnings(CPlayer.getEarnings())
                .height(CPlayer.getHeight())
                .weight(CPlayer.getWeight())
                .build();
    }

    public static List<CSPlayer> toCSPlayers(List<CPlayer> CPlayers) {
        return CPlayers.stream().map(CSMapper::toCSPlayer).toList();
    }

    public static CSTableEntry toCSTableEntry(CSCompetitionEntry entry) {
        return CSTableEntry.builder()
                .teamId(entry.getCTeam().getId())
                .teamName(entry.getCTeam().getName())
                .points(entry.getPoints() != null ? entry.getPoints() : 0)
                .wins(entry.getWins() != null ? entry.getWins() : 0)
                .draws(entry.getDraws() != null ? entry.getDraws() : 0)
                .losses(entry.getLosses() != null ? entry.getLosses() : 0)
                .goalsScored(entry.getGoalsScored() != null ? entry.getGoalsScored() : 0)
                .goalsConceded(entry.getGoalsConceded() != null ? entry.getGoalsConceded() : 0)
                .played(0)
                .build();
    }
}
