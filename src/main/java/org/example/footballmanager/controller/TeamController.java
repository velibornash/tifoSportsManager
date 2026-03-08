package org.example.footballmanager.controller;

import org.example.footballmanager.dto.LeagueMilestonesDTO;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.LeagueMilestoneService;
import org.example.footballmanager.service.SeasonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/teams")

public class TeamController {

    private static final Set<String> ALLOWED_STYLES = Set.of(
            "BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"
    );

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final LeagueMilestoneService leagueMilestoneService;
    private final SeasonService seasonService;

    public TeamController(TeamRepository teamRepository,
                          PlayerRepository playerRepository,
                          MatchRepository matchRepository,
                          LineupRepository lineupRepository,
                          MatchPlayerStatsRepository matchPlayerStatsRepository,
                          LeagueMilestoneService leagueMilestoneService,
                          SeasonService seasonService) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.leagueMilestoneService = leagueMilestoneService;
        this.seasonService = seasonService;
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
        List<Player> teamPlayers = playerRepository.findByTeamId(teamId);
        Map<Long, List<MatchPlayerStats>> statsByPlayerId = teamPlayers.isEmpty()
                ? Map.of()
                : matchPlayerStatsRepository.findByPlayerIdIn(teamPlayers.stream().map(Player::getId).toList())
                .stream()
                .filter(stats -> stats.getPlayer() != null)
                .collect(Collectors.groupingBy(stats -> stats.getPlayer().getId()));

        List<PlayerDTO> players = teamPlayers
                .stream()
                .map(player -> toPlayerDto(player, statsByPlayerId.get(player.getId())))
                .toList();
        return ResponseEntity.ok(players);
    }

    // Detalji jednog igrača
    @GetMapping("/{teamId}/players/{playerId}")
    public ResponseEntity<PlayerDTO> getPlayer(@PathVariable Long teamId, @PathVariable Long playerId) {
        return playerRepository.findById(playerId)
                .filter(p -> p.getTeam().getId().equals(teamId))
                .map(player -> toPlayerDto(player, matchPlayerStatsRepository.findByPlayerId(player.getId())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private PlayerDTO toPlayerDto(Player player, List<MatchPlayerStats> stats) {
        List<MatchPlayerStats> safeStats = stats == null ? List.of() : stats;
        double averageRating10 = safeStats.stream()
                .mapToInt(MatchPlayerStats::getRating)
                .average()
                .orElse(0.0) / 10.0;
        Double roundedAverageRating10 = safeStats.isEmpty()
                ? null
                : Math.round(averageRating10 * 10.0) / 10.0;
        return PlayerDTO.from(player, safeStats.size(), roundedAverageRating10);
    }

    @GetMapping("/{teamId}/matches")
    public ResponseEntity<List<MatchDTO>> getMatches(@PathVariable Long teamId) {
        List<MatchDTO> matches = matchRepository.findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(teamId, teamId)
                .stream()
                .map(MatchDTO::from)
                .toList();
        return ResponseEntity.ok(matches);
    }

    @GetMapping("/{teamId}/milestones")
    public ResponseEntity<LeagueMilestonesDTO> getTeamMilestones(@PathVariable Long teamId,
                                                                 @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        return ResponseEntity.ok(leagueMilestoneService.buildTeamMilestones(team, activeSeasonYear));
    }

    @GetMapping("/{teamId}/lineup-template")
    public ResponseEntity<Map<String, Object>> getLineupTemplate(@PathVariable Long teamId) {
        Lineup template = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElse(null);
        if (template == null) {
            return ResponseEntity.ok(Map.of(
                    "saved", false,
                    "formation", "4-4-2",
                    "style", "BALANCED",
                    "starterIds", List.of(),
                    "benchIds", List.of()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "saved", true,
                "formation", template.getFormation() == null ? "4-4-2" : template.getFormation(),
                "style", normalizeStyle(template.getStyle()),
                "starterIds", template.getOrderedStarterIds(),
                "benchIds", template.getOrderedBenchIds()
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
        String style = normalizeStyle(payload.get("style"));
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

        Lineup lineup = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElseGet(Lineup::new);
        lineup.setTeam(team);
        lineup.setMatch(null);
        lineup.setFormation(formation);
        lineup.setStyle(style);
        lineup.setStartingPlayers(starters);
        lineup.setSubstitutes(bench);
        lineup.setStarterOrderFromIds(starters.stream().map(Player::getId).toList());
        lineup.setBenchOrderFromIds(bench.stream().map(Player::getId).toList());
        lineup = lineupRepository.save(lineup);

        return ResponseEntity.ok(Map.of(
                "id", lineup.getId(),
                "saved", true,
                "formation", lineup.getFormation(),
                "style", normalizeStyle(lineup.getStyle()),
                "starterIds", lineup.getOrderedStarterIds(),
                "benchIds", lineup.getOrderedBenchIds()
        ));
    }

    private String normalizeStyle(Object rawStyle) {
        String style = rawStyle == null ? "BALANCED" : String.valueOf(rawStyle).trim().toUpperCase(Locale.ROOT);
        return ALLOWED_STYLES.contains(style) ? style : "BALANCED";
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
