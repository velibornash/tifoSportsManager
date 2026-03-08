package org.example.footballmanager.dto.transfer;

import lombok.Data;

@Data
public class TransferActionRequest {
    private Long teamId;
    private Double price;
}