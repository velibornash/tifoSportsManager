package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity(name = "Transfer")
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team sellerTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team buyerTeam;

    @Enumerated(EnumType.STRING)
    private TransferStatus status;

    private double askingPrice;
    private Double agreedPrice;
    private LocalDateTime listedAt;
    private LocalDateTime completedAt;
}