package org.example.footballmanager.cleanSheet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSTransferListing {
    private Long playerId;
    private String playerName;
    private String position;
    private int age;
    private int rating;
    private double marketValue;
    private double askingPrice;
    private Long sellerTeamId;
    private String sellerTeamName;
    private String listedAt;
    private Double bestOffer;
    private String bestOfferClub;
    @Builder.Default
    private List<String> interestedClubs = new ArrayList<>();
}
