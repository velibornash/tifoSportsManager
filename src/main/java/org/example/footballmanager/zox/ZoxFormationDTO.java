package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxFormationDTO {
    private String formation; // "4-3-3", "3-5-2", etc
    private ZoxPlayerPositionDTO[] positions; // Array of players in positions
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ZoxPlayerPositionDTO {
        private Long playerId;
        private String playerName;
        private String position; // "GK", "CB", "LB", "RB", "DM", "CM", "AM", "LM", "RM", "ST", "CF"
        private Double x; // 0-100 (field width)
        private Double y; // 0-100 (field height)
        private Integer rating; // 0-10
        private Integer number; // Squad number
    }
}
