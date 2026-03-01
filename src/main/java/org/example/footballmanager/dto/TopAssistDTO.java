package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TopAssistDTO {
    private String playerName;
    private int assists;
    private String teamName;
}