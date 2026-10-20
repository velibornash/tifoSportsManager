package org.example.footballmanager.newLogic.dto.transfer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TeamTransferOverviewDTO {
    private Long teamId;
    private String teamName;
    private Double budget;
    private int listedCount;
    private List<TransferDTO> listedPlayers = new ArrayList<>();
    private int incomingOfferCount;
    private List<TransferDTO> incomingOffers = new ArrayList<>();
}
