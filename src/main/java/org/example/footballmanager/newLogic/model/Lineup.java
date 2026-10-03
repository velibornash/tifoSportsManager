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
        return sortPlayersByOrder(currentPlayers(startingPlayers), getOrderedStarterIds());
    }

    public List<Player> getOrderedSubstitutePlayers() {
        return sortPlayersByOrder(currentPlayers(substitutes), getOrderedBenchIds());
    }

    /**
     * Drops players who no longer belong to this lineup's club.
     *
     * <p>The join table keeps naming a player after he has left, because nothing cleans it up when he
     * goes. {@code PlayerContractService.expireContracts} has been setting {@code team = null} on
     * expiry for some time, so a saved XI could already start a player who no longer played for that
     * club — the bug predates retirement, and retirement would have made it routine.
     *
     * <p><b>A lineup with no club is left alone.</b> It cannot judge membership, because there is no
     * membership to judge: {@code RealSquadFactoryTest} and
     * {@code RealSquadSimulationSmokeTest} build eleven synthetic players with no club at all to
     * exercise the shape mapping, and dropping them would silently turn those tests into "the squad is
     * null". Every lineup the product reads has a team.
     *
     * <p>Filtering here rather than at each call site is deliberate: {@code SimMatchService},
     * {@code RealSquadFactory} and {@code ScheduleInsightService} all read this collection. With a row
     * dropped the list can fall below eleven, which every caller already handles by building from the
     * club's real squad instead.
     */
    private List<Player> currentPlayers(List<Player> players) {
        if (players == null || players.isEmpty() || team == null || team.getId() == null) {
            return players == null ? List.of() : players;
        }
        Long clubId = team.getId();
        return players.stream()
                .filter(p -> p != null && p.getTeam() != null && clubId.equals(p.getTeam().getId()))
                .toList();
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