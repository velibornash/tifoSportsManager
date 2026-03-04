package org.example.footballmanager.cleanSheet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSPlayer {
    private Long id;
    private String name;
    private String position; // GK, DEF, MID, ATT, WNG
    private int age;
    private int rating;
    private double form;       // 1.0 - 10.0
    private double fatigue;    // 0-10
    private double talent;     // 3.0 top, 9.0 los

    // Skills
    private int stamina;
    private int goalkeeper;
    private int defending;
    private int pace;
    private int technique;
    private int playmaker;
    private int passing;
    private int shooting;

    // Stats za sezonu
    private int goals;
    private int assists;

    private double value;
    private double earnings;
    private double height;
    private double weight;
}
