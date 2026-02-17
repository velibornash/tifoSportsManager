package org.example.footballmanager.controller;

import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/teams")

public class TeamController {

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;

    public TeamController(TeamRepository teamRepository, PlayerRepository playerRepository, MatchRepository matchRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
    }

    @GetMapping
    public List<Team> getAllTeams() {
        return teamRepository.findAll();
    }

    @PostMapping
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
        List<MatchDTO> matches = matchRepository.findByHomeTeamIdOrAwayTeamId(teamId, teamId)
                .stream()
                .map(MatchDTO::from)
                .toList();
        return ResponseEntity.ok(matches);
    }
}