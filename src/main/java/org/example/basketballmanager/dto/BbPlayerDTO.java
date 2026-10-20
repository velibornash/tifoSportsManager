package org.example.basketballmanager.dto;

import lombok.*;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayerDTO {
    private Long id;
    private String name;
    private String position;
    private Integer height;
    private Integer weight;
    private Integer jerseyNumber;
    private Long teamId;
    private String teamName;
    private String teamShortName;
    private String teamColor;
    private Boolean injured;
    private Integer fatigue;
    private Map<String, Integer> skills;
    private BbPlayerStatsDTO stats;
    private Integer overall;
}
