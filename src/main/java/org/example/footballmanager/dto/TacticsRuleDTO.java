package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacticsRuleDTO {
    private String slotKey;
    private String ballStateKey;
    private String possessionContext;
    private String targetCellKey;
}