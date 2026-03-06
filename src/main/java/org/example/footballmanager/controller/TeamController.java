package org.example.footballmanager.controller;

import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/teams")

public class TeamController {

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;

    public TeamController(TeamRepository teamRepository, PlayerRepository playerRepository, MatchRepository matchRepository, LineupRepository lineupRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
    }

    @GetMapping
    public List<Team> getAllTeams() {
        return teamRepository.findAll();
    }

    @PostMapping("/create")
    public Team createTeam(@RequestBody Team team) {
        return teamRepository.save(team);
    }

    // Lista igrača
    @GetMapping("/{teamId}/players")
    public ResponseEntity<List<PlayerDTO>> getPlayers(@PathVariable Long teamId) {
        List<PlayerDTO> players = playerRepository.findByTeamId(teamId)
                .stream()
                .map(PlayerDTO::from)
                .toList();
        return ResponseEntity.ok(players);
    }

    // Detalji jednog igrača
    @GetMapping("/{teamId}/players/{playerId}")
    public ResponseEntity<PlayerDTO> getPlayer(@PathVariable Long teamId, @PathVariable Long playerId) {
        return playerRepository.findById(playerId)
                .filter(p -> p.getTeam().getId().equals(teamId))
                .map(PlayerDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{teamId}/matches")
    public ResponseEntity<List<MatchDTO>> getMatches(@PathVariable Long teamId) {
        List<MatchDTO> matches = matchRepository.findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(teamId, teamId)
                .stream()
                .map(MatchDTO::from)
                .toList();
        return ResponseEntity.ok(matches);
    }

    @GetMapping("/{teamId}/lineup-template")
    public ResponseEntity<Map<String, Object>> getLineupTemplate(@PathVariable Long teamId) {
        Lineup template = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElse(null);
        if (template == null) {
            return ResponseEntity.ok(Map.of(
                    "formation", "4-4-2",
                    "starterIds", List.of(),
                    "benchIds", List.of()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "formation", template.getFormation() == null ? "4-4-2" : template.getFormation(),
                "starterIds", template.getStartingPlayers() == null ? List.of() : template.getStartingPlayers().stream().map(Player::getId).toList(),
                "benchIds", template.getSubstitutes() == null ? List.of() : template.getSubstitutes().stream().map(Player::getId).toList()
        ));
    }

    @PutMapping("/{teamId}/lineup-template")
    public ResponseEntity<Map<String, Object>> saveLineupTemplate(@PathVariable Long teamId,
                                                                  @RequestBody Map<String, Object> payload) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        String formation = Objects.toString(payload.getOrDefault("formation", "4-4-2"), "4-4-2");
        List<Long> starterIds = parseIdList(payload.getOrDefault("starterIds", List.of()), 11);
        List<Long> benchIds = parseIdList(payload.getOrDefault("benchIds", List.of()), 7);

        List<Player> teamPlayers = playerRepository.findByTeamId(teamId);
        Map<Long, Player> byId = teamPlayers.stream()
                .filter(p -> !p.isInjured())
                .collect(java.util.stream.Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        List<Player> starters = starterIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        if (starters.size() < 11) {
            List<Player> finalStarters = starters;
            List<Player> fallback = byId.values().stream()
                    .filter(p -> finalStarters.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                    .sorted((a, b) -> Integer.compare(b.getRating(), a.getRating()))
                    .limit(11 - starters.size())
                    .toList();
            starters = java.util.stream.Stream.concat(starters.stream(), fallback.stream()).toList();
        }

        List<Player> finalStarters1 = starters;
        List<Player> bench = benchIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(p -> finalStarters1.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                .limit(7)
                .toList();
        if (bench.size() < 7) {
            List<Player> finalStarters2 = starters;
            List<Player> finalBench = bench;
            List<Player> fallbackBench = byId.values().stream()
                    .filter(p -> finalStarters2.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                    .filter(p -> finalBench.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                    .sorted((a, b) -> Integer.compare(b.getRating(), a.getRating()))
                    .limit(7 - bench.size())
                    .toList();
            bench = java.util.stream.Stream.concat(bench.stream(), fallbackBench.stream()).toList();
        }

        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setMatch(null);
        lineup.setFormation(formation);
        lineup.setStartingPlayers(starters);
        lineup.setSubstitutes(bench);
        lineup = lineupRepository.save(lineup);

        return ResponseEntity.ok(Map.of(
                "id", lineup.getId(),
                "formation", lineup.getFormation(),
                "starterIds", lineup.getStartingPlayers().stream().map(Player::getId).toList(),
                "benchIds", lineup.getSubstitutes().stream().map(Player::getId).toList()
        ));
    }

    private List<Long> parseIdList(Object raw, int limit) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(v -> {
                    if (v instanceof Number n) return n.longValue();
                    try {
                        return Long.parseLong(String.valueOf(v));
                    } catch (Exception ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .limit(limit)
                .toList();
    }
}
