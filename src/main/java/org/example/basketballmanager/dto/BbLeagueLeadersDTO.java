package org.example.basketballmanager.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbLeagueLeadersDTO {
    private List<BbPlayerDTO> topScorers;
    private List<BbPlayerDTO> topRebounders;
    private List<BbPlayerDTO> topAssists;
}
