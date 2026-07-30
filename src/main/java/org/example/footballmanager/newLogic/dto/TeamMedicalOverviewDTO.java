package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamMedicalOverviewDTO {
    private Long teamId;
    private String teamName;
    private int totalPlayers;
    private int availableCount;
    private int injuredCount;
    private int criticalInjuryCount;
    private int rehabCount;
    private int averageConditionPercent;
    private List<PlayerDTO> recoveryQueue;
}