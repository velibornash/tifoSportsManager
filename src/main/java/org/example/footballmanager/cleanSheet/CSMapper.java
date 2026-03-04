package org.example.footballmanager.cleanSheet;

import org.example.footballmanager.cleanSheet.model.CSPlayer;
import org.example.footballmanager.cleanSheet.model.CSTableEntry;
import org.example.footballmanager.cleanSheet.model.CSTeam;
import org.example.footballmanager.model.*;

import java.util.List;

/**
 * Mapira JPA entitete u CS POJO-e.
 * Koristi se SAMO jednom — pri pokretanju igre (startNewGame).
 */
public final class CSMapper {

    private CSMapper() {}

    public static CSTeam toCSTeam(Team team) {
        return CSTeam.builder()
                .id(team.getId())
                .name(team.getName())
                .budget(team.getBudget() != null ? team.getBudget() : 500_000)
                .reputation(team.getReputation() != null ? team.getReputation() : 50)
                .stadiumName(team.getStadium() != null ? team.getStadium().getName() : "Unknown")
                .stadiumCapacity(team.getStadium() != null && team.getStadium().getCapacity() != null
                        ? team.getStadium().getCapacity() : 5000)
                .formation("4-4-2")
                .build();
    }

    public static CSPlayer toCSPlayer(Player player) {
        Skills s = player.getSkills();
        return CSPlayer.builder()
                .id(player.getId())
                .name(player.getName())
                .position(player.getPosition() != null ? player.getPosition().name() : "MID")
                .age(player.getAge())
                .rating(player.getRating())
                .form(player.getForm())
                .fatigue(s != null ? s.getFatigue() : 0)
                .talent(player.getTalent())
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
                .value(player.getPlayerValue())
                .earnings(player.getEarnings())
                .height(player.getHeight())
                .weight(player.getWeight())
                .build();
    }

    public static List<CSPlayer> toCSPlayers(List<Player> players) {
        return players.stream().map(CSMapper::toCSPlayer).toList();
    }

    public static CSTableEntry toCSTableEntry(CompetitionEntry entry) {
        return CSTableEntry.builder()
                .teamId(entry.getTeam().getId())
                .teamName(entry.getTeam().getName())
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
