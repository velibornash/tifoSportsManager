package org.example.americanfootballmanager.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfLeagueLeadersDTO {
    private List<AfPlayerDTO> topPassingYards;
    private List<AfPlayerDTO> topRushingYards;
    private List<AfPlayerDTO> topReceivingYards;
    private List<AfPlayerDTO> topTackles;
    private List<AfPlayerDTO> topInterceptions;
    private List<AfPlayerDTO> topSacks;
}
