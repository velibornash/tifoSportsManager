package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TopScorerDTO {
    private String playerName;
    private int goals;
    private String teamName;
}