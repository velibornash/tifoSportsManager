package org.example.footballtextmanager.model;

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
    
    @Builder.Default
    private int passesAttempted = 0;
    @Builder.Default
    private int passesCompleted = 0;
    @Builder.Default
    private int tackles = 0;
    @Builder.Default
    private int interceptions = 0;
    @Builder.Default
    private int duelsWon = 0;
    @Builder.Default
    private int duelsLost = 0;
    @Builder.Default
    private int aerialDuelsWon = 0;
    @Builder.Default
    private int keyPasses = 0;
    @Builder.Default
    private int dribblesCompleted = 0;
    @Builder.Default
    private int dribblesLost = 0;
    @Builder.Default
    private double distanceCovered = 0.0; // km
    @Builder.Default
    private int saves = 0;
    @Builder.Default
    private boolean cleanSheet = false;
    @Builder.Default
    private int goalsConceded = 0;
}
