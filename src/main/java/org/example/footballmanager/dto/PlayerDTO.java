package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PlayerDTO {
    private Long id;
    private String name;
    private String position;
    private double form;
    private int rating;
    private int totalGoals;
    private int totalAssists;
    private int goalsInMatch;
    private int assistsInMatch;

    public static PlayerDTO from(Player player, Match match, MatchPlayerStats stats) {
        return new PlayerDTO(
                player.getId(),
                player.getName(),
                player.getPosition(),
                player.getForm(),
                stats.getRating(),
                player.getTotalGoals(),
                player.getTotalAssists(),
                stats.getGoals(),
                stats.getAssists()
        );
    }
}