package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxTopPerformerDTO {
    private Long playerId;
    private String playerName;
    private Long teamId;
    private String teamName;
    private String position;
    private Double rating10;
    private Integer goals;
    private Integer assists;
    private Integer minutesPlayed;
    private Integer saves;
    private Integer interceptions;
    private boolean cleanSheet;
    private String summary;
}
