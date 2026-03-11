package org.example.footballmanager.dto;

import lombok.Data;

@Data
public class TacticsSetPieceDTO {
    private String penaltyTakerSlot;
    private String freeKickLeftTakerSlot;
    private String freeKickRightTakerSlot;
    private String cornerLeftTakerSlot;
    private String cornerRightTakerSlot;
}