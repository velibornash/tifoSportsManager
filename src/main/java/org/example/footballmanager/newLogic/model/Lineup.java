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

    /**
     * The position of each slot, index-aligned with {@link #starterOrder}.
     *
     * <p><b>Why this is not the player's position.</b> Sokker's squad page lets a manager stand a
     * midfielder at full-back or a striker in goal, and the owner asked for the same. The player keeps the
     * position he is; the slot carries the job. Reading the job off the man in it would make those two
     * impossible to express, and would make a saved XI mean something different from the one on screen.
     *
     * <p>Stored beside the order rather than in a join table, for the same reason the order is: it is a
     * property of <em>the slot</em>, and slots only exist as positions in that list.
     */
    @Column(name = "starter_positions", length = 512)
    private String starterPositions;

    /**
     * Each slot's position, in starter order, padded from the players' own where none was set.
     *
     * <p><b>Falling back is the point, not a convenience.</b> Every lineup saved before this column
     * existed has no positions at all, and a manager who changes his eleven without setting them must not
     * lose his side. An absent position is therefore the player's own, and a short list is padded, rather
     * than the lineup being rejected or truncated.
     *
     * @return one position per starter, the same length as the starters
     */
    public List<Position> getOrderedStarterPositions() {
        List<Player> starters = getOrderedStartingPlayers();
        List<String> stored = starterPositions == null || starterPositions.isBlank()
                ? List.of()
                : Arrays.stream(starterPositions.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

        List<Position> out = new ArrayList<>(starters.size());
        for (int i = 0; i < starters.size(); i++) {
            Position slot = i < stored.size() ? parsePosition(stored.get(i)) : null;
            Player player = starters.get(i);
            // A name that is not a position is skipped, not fatal: this column is a JSON string a manager
            // can hand-edit, and one bad word must not take an XI out of the game.
            out.add(slot != null ? slot
                    : (player == null ? Position.MID
                            : (player.getPosition() == null ? Position.MID : player.getPosition())));
        }
        return out;
    }

    private static Position parsePosition(String raw) {
        try {
            return Position.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Writes the slot positions from an ordered list; null or empty clears them. */
    public void setStarterPositionsFrom(List<? extends Object> positions) {
        if (positions == null || positions.isEmpty()) {
            this.starterPositions = null;
            return;
        }
        this.starterPositions = positions.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
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