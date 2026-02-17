package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.footballmanager.model.Match;
import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchDTO {
    private Long id;
    private String homeTeam;
    private String awayTeam;
    private int homeGoals;
    private int awayGoals;
    private LocalDateTime matchDate;

    public static MatchDTO from(Match match) {
        return new MatchDTO(
                match.getId(),
                match.getHomeTeam().getName(),
                match.getAwayTeam().getName(),
                match.getHomeGoals(),
                match.getAwayGoals(),
                match.getMatchDate()
        );
    }

}
