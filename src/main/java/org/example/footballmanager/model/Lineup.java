package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Entity
public class Lineup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Team team;

    @ManyToOne
    @JoinColumn(name = "match_id")
    private Match match;

    // === PROMENA: @ManyToMany umesto @OneToMany ===
    @ManyToMany
    @JoinTable(
            name = "lineup_starting_players",
            joinColumns = @JoinColumn(name = "lineup_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id")
    )
    private List<Player> startingPlayers = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "lineup_substitutes",
            joinColumns = @JoinColumn(name = "lineup_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id")
    )
    private List<Player> substitutes = new ArrayList<>();

    private String formation; // npr: "4-4-2", "3-5-2"
}