package org.example.footballmanager.util.players;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.PlayerRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Slf4j
public class SquadNumberAssigner {

    private final PlayerRepository playerRepository;

    public SquadNumberAssigner(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public void assignMissingNumbers(Team team) {
        if (team == null || team.getId() == null) {
            return;
        }

        List<Player> players = playerRepository.findByTeam(team).stream()
                .sorted(Comparator
                        .comparing((Player p) -> positionOrder(p.getPosition()))
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        boolean changed = false;
        Player goalkeeper = resolvePreferredGoalkeeper(players, team);
        if (goalkeeper != null && !Integer.valueOf(1).equals(goalkeeper.getSquadNumber())) {
            Player currentOne = players.stream()
                    .filter(player -> !player.equals(goalkeeper))
                    .filter(player -> Integer.valueOf(1).equals(player.getSquadNumber()))
                    .findFirst()
                    .orElse(null);
            goalkeeper.setSquadNumber(1);
            changed = true;
            if (currentOne != null) {
                currentOne.setSquadNumber(null);
            }
        }

        Set<Integer> used = new HashSet<>();
        players.stream()
                .map(Player::getSquadNumber)
                .filter(number -> number != null && number > 0)
                .forEach(used::add);

        for (Player player : players) {
            if (player.getSquadNumber() != null && player.getSquadNumber() > 0) {
                continue;
            }
            Integer preferred = preferredNumber(player.getPosition());
            int number = preferred != null && !used.contains(preferred)
                    ? preferred
                    : nextAvailableNumber(used);
            player.setSquadNumber(number);
            used.add(number);
            changed = true;
        }

        if (changed) {
            playerRepository.saveAll(players);
            log.info("Assigned missing squad numbers for team {}", team.getName());
        }
    }

    public Integer nextNumberForTeam(Team team, Position position) {
        if (team == null || team.getId() == null) {
            return preferredNumber(position);
        }

        Set<Integer> used = new HashSet<>();
        playerRepository.findByTeam(team).stream()
                .map(Player::getSquadNumber)
                .filter(number -> number != null && number > 0)
                .forEach(used::add);

        Integer preferred = preferredNumber(position);
        if (preferred != null && !used.contains(preferred)) {
            return preferred;
        }
        return nextAvailableNumber(used);
    }

    public String initialsFallback(Player player) {
        String name = player != null ? player.getName() : null;
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        return (String.valueOf(parts[0].charAt(0)) + parts[parts.length - 1].charAt(0)).toUpperCase(Locale.ROOT);
    }

    private Integer preferredNumber(Position position) {
        if (position == null) {
            return null;
        }
        return switch (position) {
            case GK -> 1;
            case DEF -> 4;
            case MID -> 8;
            case WNG -> 7;
            case ATT -> 9;
        };
    }

    private int nextAvailableNumber(Set<Integer> used) {
        for (int number = 1; number <= 99; number++) {
            if (!used.contains(number)) {
                return number;
            }
        }
        return 99;
    }

    private int positionOrder(Position position) {
        if (position == null) {
            return 99;
        }
        return switch (position) {
            case GK -> 0;
            case DEF -> 1;
            case MID -> 2;
            case WNG -> 3;
            case ATT -> 4;
        };
    }

    private Player resolvePreferredGoalkeeper(List<Player> players, Team team) {
        if ("OFK Omladinac".equalsIgnoreCase(team.getName())) {
            Player zvezdan = players.stream()
                    .filter(player -> player.getPosition() == Position.GK)
                    .filter(player -> "zvezdan vukomanovic".equalsIgnoreCase(player.getName()))
                    .findFirst()
                    .orElse(null);
            if (zvezdan != null) {
                return zvezdan;
            }
        }

        return players.stream()
                .filter(player -> player.getPosition() == Position.GK)
                .findFirst()
                .orElse(null);
    }
}
