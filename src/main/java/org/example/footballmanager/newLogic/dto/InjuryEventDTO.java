package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class InjuryEventDTO extends MatchEventDTO {
    private String playerName;
    private String teamName;
}