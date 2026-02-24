package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@AllArgsConstructor
@Getter
@Setter
public class PlayerPositionDTO {
    private int id;
    private String team; // HOME / AWAY
    private double x;    // 0 - 100
    private double y;    // 0 - 100
    private int offsideTicksRemaining = 0;
    private int retreatTicksRemaining = 0;
}
