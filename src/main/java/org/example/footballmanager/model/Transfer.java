package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    private Player player;

    private double askingPrice;
    private LocalDateTime listedAt;

    @ElementCollection
    private Set<String> interestedTeams = new HashSet<>();
}