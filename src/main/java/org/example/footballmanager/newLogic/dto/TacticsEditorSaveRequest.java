package org.example.footballmanager.newLogic.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TacticsEditorSaveRequest {
    private String formation;
    private String style;
    private List<Long> starterIds = new ArrayList<>();
    private List<Long> benchIds = new ArrayList<>();
    private List<TacticsRuleDTO> movementRules = new ArrayList<>();
    private TacticsSetPieceDTO setPieceAssignments = new TacticsSetPieceDTO();
}