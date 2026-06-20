package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@Data
@Entity(name = "Lineup")
public class Lineup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Team team;

    @ManyToOne
    @JoinColumn(name = "match_id")
    private Match match;

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

    @Column(name = "starter_order", length = 512)
    private String starterOrder;

    @Column(name = "bench_order", length = 512)
    private String benchOrder;

    private String formation;

    private String style;

    public List<Long> getOrderedStarterIds() {
        return parseOrder(starterOrder, startingPlayers);
    }

    public List<Long> getOrderedBenchIds() {
        return parseOrder(benchOrder, substitutes);
    }

    public List<Player> getOrderedStartingPlayers() {
        return sortPlayersByOrder(startingPlayers, getOrderedStarterIds());
    }

    public List<Player> getOrderedSubstitutePlayers() {
        return sortPlayersByOrder(substitutes, getOrderedBenchIds());
    }

    public void setStarterOrderFromIds(List<Long> ids) {
        this.starterOrder = encodeOrder(ids);
    }

    public void setBenchOrderFromIds(List<Long> ids) {
        this.benchOrder = encodeOrder(ids);
    }

    private List<Long> parseOrder(String rawOrder, List<Player> fallbackPlayers) {
        if (rawOrder != null && !rawOrder.isBlank()) {
            return Arrays.stream(rawOrder.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(value -> {
                        try {
                            return Long.parseLong(value);
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        if (fallbackPlayers == null) {
            return List.of();
        }
        return fallbackPlayers.stream()
                .filter(Objects::nonNull)
                .map(Player::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Player> sortPlayersByOrder(List<Player> players, List<Long> orderedIds) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<Long, Player> byId = new LinkedHashMap<>();
        List<Player> unordered = new ArrayList<>();
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            if (player.getId() == null) {
                unordered.add(player);
                continue;
            }
            byId.putIfAbsent(player.getId(), player);
        }

        List<Player> ordered = new ArrayList<>();
        for (Long id : orderedIds) {
            Player player = byId.remove(id);
            if (player != null) {
                ordered.add(player);
            }
        }
        ordered.addAll(byId.values());
        ordered.addAll(unordered);
        return ordered;
    }

    private String encodeOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
    }
}