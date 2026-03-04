package org.example.footballmanager.cleanSheet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSMatchEvent {
    private int minute;
    private CSEventType eventType;
    private String playerName;
    private String assistName;
    private String teamName;
    private String description;
    private String scoreAfterGoal;
    private boolean penaltyScored;
}
