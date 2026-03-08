package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxEventStreamDTO {
    private Long matchId;
    private Integer minute;
    private String timeStatus; // "HT", "FT", "Live"
    private List<ZoxMatchEventDTO> events;
    private Integer homeGoals;
    private Integer awayGoals;
    private Double homeFormationX;
    private Double homeFormationY;
    private Double awayFormationX;
    private Double awayFormationY;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ZoxMatchEventDTO {
        private Integer minute;
        private String second; // Additional time precision
        private String type; // "GOAL", "YELLOW_CARD", "RED_CARD", "SUBSTITUTION", "CORNER", "FREE_KICK", "PASS", "SHOT"
        private String teamName;
        private String playerName;
        private String playerAssistName; // For goals and assists
        private String description; // "Messi scores!", "Ronaldo gets yellow card", etc
        private String eventIcon; // "⚽", "🟨", "🟥", "🔄", etc
    }
}
