package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

@Data
@NoArgsConstructor
@AllArgsConstructor
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
    private List<Player> players = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<Junior> juniors = new ArrayList<>();

    // =========================
    // Helper methods
    // =========================

    /**
     * Dodaje igrača u tim i postavlja reference
     */
    public void addPlayer(Player player) {
        players.add(player);
        player.setTeam(this);
    }

    /**
     * Uklanja igrača iz tima
     */
    public void removePlayer(Player player) {
        players.remove(player);
        player.setTeam(null);
    }

    /**
     * Prosečna ocena svih igrača u timu
     */
    public double getAverageRating() {
        OptionalDouble avg = players.stream()
                .mapToInt(Player::getRating)
                .average();
        return avg.orElse(0.0);
    }

    /**
     * Prosečan skill za određenu poziciju
     */
    public double getAverageSkill(Position position) {
        OptionalDouble avg = players.stream()
                .filter(p -> p.getPosition() == position)
                .mapToDouble(p -> p.getSkills().getRatingScore(position))
                .average();
        return avg.orElse(0.0);
    }

    /**
     * Broj dostupnih igrača za utakmicu
     */
    public long getAvailablePlayers() {
        return players.stream()
                .filter(p -> p.getForm() > 3.0 && p.getSkills().getFatigue() < 8)
                .count();
    }

    /**
     * Provera da li je tim spreman za utakmicu
     */
    public boolean isMatchReady() {
        return getAvailablePlayers() >= 11;
    }
}
