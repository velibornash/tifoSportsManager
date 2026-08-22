package org.example.americanfootballmanager.dto;

import lombok.*;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfPlayerDTO {
    private Long id;
    private String name;
    private String position;
    private Integer jerseyNumber;
    private Long teamId;
    private String teamName;
    private String teamShortName;
    private String teamColor;
    private Boolean injured;
    private Integer fatigue;
    private Map<String, Integer> skills;
    private AfPlayerStatsDTO stats;
    private Integer overall;

    public AfPlayerStatsDTO getStats() { return stats; }
}
