package org.example.footballmanager.newLogic.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TacticsEditorDTO {
    private Long teamId;
    private String teamName;
    private boolean saved;
    private String formation;
    private String style;
    private List<Long> starterIds = new ArrayList<>();
    private List<Long> benchIds = new ArrayList<>();
    private List<TacticsSlotDTO> slotDefinitions = new ArrayList<>();
    private List<String> supportedBallStates = new ArrayList<>();
    private List<String> supportedTargetCells = new ArrayList<>();
    private List<TacticsRuleDTO> movementRules = new ArrayList<>();
    private TacticsSetPieceDTO setPieceAssignments = new TacticsSetPieceDTO();
    private Long version;
    private LocalDateTime savedAt;
}