package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SubstitutionEventDTO extends MatchEventDTO {
    private String playerOutName;
    private String playerInName;
    private String teamName;
}