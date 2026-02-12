package org.example.footballmanager.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

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
    private String stadium;

    @ManyToOne
    private League league;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference   // ← Ovo je forward deo (Team → Players)
    private List<Player> players = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    @JsonIgnore   // Juniors nisu potrebni u JSON-u eventova
    private List<Junior> juniors = new ArrayList<>();

    // Helper methods ostaju isti
    public void addPlayer(Player player) {
        players.add(player);
        player.setTeam(this);
    }

    public void removePlayer(Player player) {
        players.remove(player);
        player.setTeam(null);
    }

    public double getAverageRating() {
        OptionalDouble avg = players.stream()
                .mapToInt(Player::getRating)
                .average();
        return avg.orElse(0.0);
    }

    public double getAverageSkill(Position position) {
        OptionalDouble avg = players.stream()
                .filter(p -> p.getPosition() == position)
                .mapToDouble(p -> p.getSkills().getRatingScore(position))
                .average();
        return avg.orElse(0.0);
    }

    public long getAvailablePlayers() {
        return players.stream()
                .filter(p -> p.getForm() > 3.0 && p.getSkills().getFatigue() < 8)
                .count();
    }

    public boolean isMatchReady() {
        return getAvailablePlayers() >= 11;
    }
}