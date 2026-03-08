package org.example.footballmanager.dto.transfer;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TransferDTO {
    private Long id;
    private Long playerId;
    private String playerName;
    private String position;
    private Integer age;
    private Integer rating;
    private Double playerValue;
    private Long sellerTeamId;
    private String sellerTeamName;
    private Long buyerTeamId;
    private String buyerTeamName;
    private Double askingPrice;
    private Double agreedPrice;
    private String status;
    private LocalDateTime listedAt;
    private LocalDateTime completedAt;
    private List<String> interestedTeams = new ArrayList<>();
    private boolean ownedByViewer;
    private boolean buyableByViewer;
    private boolean removalAllowed;
}