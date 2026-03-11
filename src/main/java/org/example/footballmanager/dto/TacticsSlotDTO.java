package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacticsSlotDTO {
    private String slotKey;
    private String label;
    private String role;
    private String line;
    private int order;
    private String anchorCellKey;
}