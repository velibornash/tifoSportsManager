package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSTeam {
    private Long id;
    private String name;
    private double budget;
    private double reputation;
    private String stadiumName;
    private int stadiumCapacity;
    private String formation;
}
