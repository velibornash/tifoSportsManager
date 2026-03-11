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
    private String matchDate;

    public static MatchDTO from(Match match) {
        // Formatiraj datum ovde (možeš promeniti format po želji)
        String formattedDate = match.getMatchDate() != null
                ? match.getMatchDate().toString().substring(0, 16).replace("T", " ")  // npr. "2026-02-20 12:00"
                : "N/A";

        return new MatchDTO(
                match.getId(),
                match.getHomeTeam() != null ? match.getHomeTeam().getName() : "TBD",
                match.getAwayTeam() != null ? match.getAwayTeam().getName() : "TBD",
                match.getHomeGoals(),
                match.getAwayGoals(),
                formattedDate
        );
    }

}
