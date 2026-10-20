package org.example.footballmanager.newLogic.dto.transfer;

import lombok.Data;

@Data
public class TransferActionRequest {
    private Long teamId;
    private Double price;
}