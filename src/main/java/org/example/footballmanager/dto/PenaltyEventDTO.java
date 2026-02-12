package org.example.footballmanager.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PenaltyEventDTO extends MatchEventDTO {
    private String takerName;
    private String teamName;
    private boolean scored;
    private String scoreAfterGoal;   // ako je postignut gol
}