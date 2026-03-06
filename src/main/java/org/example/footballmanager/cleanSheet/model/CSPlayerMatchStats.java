package org.example.footballmanager.cleanSheet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSPlayerMatchStats {
    private Long playerId;
    private String playerName;
    private String position;
    private double rating;  // 1.0 - 10.0 match rating
    private int goals;
    private int assists;
    private int minutesPlayed;
}
