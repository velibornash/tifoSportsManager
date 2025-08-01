package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Data
@Entity
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String country;
    private double budget;
    private double reputation; // 0-100
    private String stadium; // ime stadiona

    @ManyToOne
    private League league;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Player> players;

    @OneToMany(cascade = CascadeType.ALL)
    private List<Junior> juniors;
}