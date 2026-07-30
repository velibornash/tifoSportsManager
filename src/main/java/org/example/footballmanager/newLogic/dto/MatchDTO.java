package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
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
    private boolean resultHidden;
    private boolean resultRevealed;
    private Long replayId;

    public static MatchDTO from(Match match) {
        return from(match, null);
    }

    public static MatchDTO from(Match match, Long viewerTeamId) {
        String formattedDate = match.getMatchDate() != null
                ? match.getMatchDate().toString().substring(0, 16).replace("T", " ")  // npr. "2026-02-20 12:00"
                : "N/A";

        boolean viewerIsHome = viewerTeamId != null
                && match.getHomeTeam() != null
                && viewerTeamId.equals(match.getHomeTeam().getId());
        boolean viewerIsAway = viewerTeamId != null
                && match.getAwayTeam() != null
                && viewerTeamId.equals(match.getAwayTeam().getId());

        boolean resultRevealed = true;
        if (viewerIsHome) {
            resultRevealed = match.isHomeResultRevealed();
        } else if (viewerIsAway) {
            resultRevealed = match.isAwayResultRevealed();
        }

        boolean resultHidden = match.isPlayed() && (viewerIsHome || viewerIsAway) && !resultRevealed;

        return new MatchDTO(
                match.getId(),
                match.getHomeTeam() != null ? match.getHomeTeam().getName() : "TBD",
                match.getAwayTeam() != null ? match.getAwayTeam().getName() : "TBD",
                match.getHomeGoals(),
                match.getAwayGoals(),
                formattedDate,
                resultHidden,
                resultRevealed,
                match.getReplayId()
        );
    }

}
