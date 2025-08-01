package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class MatchPlayerStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Match match;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Player player;

    private int goals;
    private int assists;
    private int yellowCards;
    private int redCards;
    private int minutesPlayed;
    private int rating;
}