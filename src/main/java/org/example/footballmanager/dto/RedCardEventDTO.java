package org.example.footballmanager.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RedCardEventDTO extends MatchEventDTO {
    private String playerName;
    private String teamName;
}