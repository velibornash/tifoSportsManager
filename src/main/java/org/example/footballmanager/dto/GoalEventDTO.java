package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GoalEventDTO {
    private String scorerName;
    private String assistantName;
    private int minute;
    private String teamName;
    boolean scored;
}