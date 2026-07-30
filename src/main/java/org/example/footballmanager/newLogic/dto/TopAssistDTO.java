package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TopAssistDTO {
    private String playerName;
    private int assists;
    private String teamName;
}