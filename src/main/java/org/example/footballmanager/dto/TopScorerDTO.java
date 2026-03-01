package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TopScorerDTO {
    private String playerName;
    private int goals;
    private String teamName;
}