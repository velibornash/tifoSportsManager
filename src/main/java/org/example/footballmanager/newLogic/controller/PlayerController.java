package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.SortWhitelist;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/players")
public class PlayerController {

    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;

    public PlayerController(PlayerRepository playerRepository, TeamRepository teamRepository) {
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
    }

    @GetMapping
    public List<PlayerDTO> getAllPlayers(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "100") int size,
                                         @RequestParam(defaultValue = "id") String sortBy,
                                         @RequestParam(defaultValue = "asc") String direction) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                Set.of("id", "name", "rating", "age", "playerValue", "position", "nationality"));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 250)), sort);
        return playerRepository.findAll(pageable).getContent()
                .stream()
                .map(PlayerDTO::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public PlayerDTO getPlayer(@PathVariable Long id) {
        Optional<Player> player = playerRepository.findById(id);
        return player.map(PlayerDTO::from)
                .orElseThrow(() -> new RuntimeException("Player not found"));
    }

    /**
     * Creates a player.
     *
     * <p><b>This accepted a raw {@code Player} and saved it.</b> Three problems at once: no admin check, so any
     * logged-in manager could mint a player at any rating; no validation, so a body with {@code id} set would
     * overwrite an existing row through the save; and the raw entity went straight back, handing the caller
     * every column on {@code Player} including the ones no UI should see.
     *
     * <p>It now takes the same administrator gate as the rest of the privileged surface and returns the
     * created player's id rather than the entity.
     */
    @PostMapping("/create")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'DEV', 'ADMIN')")
    public ResponseEntity<PlayerDTO> createPlayer(@RequestBody PlayerDTO request,
                                                  @RequestParam Long teamId) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("A player needs a name.");
        }
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("No such team: " + teamId));
        Player player = new Player();
        player.setName(request.getName().trim());
        player.setAge(request.getAge());
        player.setPosition(parsePosition(request.getPosition()));
        player.setTeam(team);
        Player saved = playerRepository.save(player);
        // The DTO rather than the entity: a raw Player on the way out hands back every column, and a body
        // with an id set would otherwise overwrite an existing row through save().
        return ResponseEntity.ok(PlayerDTO.from(saved, 0, null, null));
    }

    /** A position that is not one of ours is a 400, not a row with a null position. */
    private static org.example.footballmanager.newLogic.model.Position parsePosition(String value) {
        if (value == null || value.isBlank()) {
            return org.example.footballmanager.newLogic.model.Position.MID;
        }
        try {
            return org.example.footballmanager.newLogic.model.Position.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException notAPosition) {
            throw new IllegalArgumentException("Unknown position '" + value + "'. Allowed: GK, DEF, MID, ATT, WNG.");
        }
    }

    @GetMapping("/paged")
    public Page<Player> getPlayersPaged(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, @RequestParam(defaultValue = "playerValue") String sortBy, @RequestParam(defaultValue = "desc") String direction) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                Set.of("id", "name", "rating", "age", "playerValue", "position", "nationality"));
        Pageable pageable = PageRequest.of(page, size, sort);
        return playerRepository.findAll(pageable);
    }
}
