package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.example.footballmanager.newLogic.util.SortWhitelist;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The world's players, readable by any manager and writable by an administrator.
 *
 * <p>Reads are open on purpose — a league table has to be readable — so the line this controller draws is the
 * one the game draws everywhere else: <b>a manager sees a player, not a player's secrets</b>. Talent is the
 * paid-for one, and {@code PlusFeatureService} is what decides it.
 */
@RestController
@RequestMapping("/players")
public class PlayerController {

    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final PlusFeatureService plusFeatures;

    public PlayerController(PlayerRepository playerRepository, TeamRepository teamRepository,
                            PlusFeatureService plusFeatures) {
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.plusFeatures = plusFeatures;
    }

    @GetMapping
    public List<PlayerDTO> getAllPlayers(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "100") int size,
                                         @RequestParam(defaultValue = "id") String sortBy,
                                         @RequestParam(defaultValue = "asc") String direction,
                                         @AuthenticationPrincipal User user) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                Set.of("id", "name", "rating", "age", "playerValue", "position", "nationality"));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 250)), sort);
        Long viewerTeamId = plusFeatures.viewerTeamId(user);
        return playerRepository.findAll(pageable).getContent()
                .stream()
                .map(player -> PlayerDTO.from(player, 0, null,
                        plusFeatures.talentOrNull(player, user, viewerTeamId)))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public PlayerDTO getPlayer(@PathVariable Long id, @AuthenticationPrincipal User user) {
        Optional<Player> player = playerRepository.findById(id);
        Long viewerTeamId = plusFeatures.viewerTeamId(user);
        return player.map(found -> PlayerDTO.from(found, 0, null,
                        plusFeatures.talentOrNull(found, user, viewerTeamId)))
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
     *
     * <p><b>It also answered 500 on every call, and had done so since the DTO was introduced.</b> The method
     * built a {@code Player} with a name, an age, a position and a club — and no {@code Skills} — and then
     * called {@code PlayerDTO.from}, whose first skill line is {@code player.getSkills().getFatigue()}.
     * Unconditional dereference, so the route could only ever answer 500. Every seeded player has skills
     * because the seeder gives them skills; a player minted through the admin form does not, which is why
     * nothing had ever noticed.
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
        // T-REST-12: derive nationality from team's country
        if (team.getCountry() != null && team.getCountry().getIsoCode() != null) {
            player.setNationality(team.getCountry().getIsoCode());
        }
        // Not optional. PlayerDTO.from reads eleven fields off Skills with no null check, so a player saved
        // without one is a row that turns every later read of him into a 500.
        player.setSkills(new Skills());
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

    /**
     * A page of players, for any manager.
     *
     * <p><b>This returned the raw {@code Player} entity</b>, for every player in the world, to any logged-in
     * manager. A raw entity carries {@code talent}, {@code earnings}, the whole injury record,
     * {@code personality} and the {@code skills} object. {@code talent} is the number this codebase built
     * {@code PlusFeatureService} to withhold — a scouting subscription pays for exactly that — so this route
     * handed to every manager, for free, the one thing the game sells.
     *
     * <p>It went unnoticed because <b>it has no frontend caller</b>: {@code grep} over {@code static/js} finds
     * no request to it. The sibling on {@code /countries/teams/{teamId}/players} was closed for precisely this
     * reason and given a test; this one was missed because nothing was looking at it.
     *
     * <p>The page shape is kept, because the SPA's paging helpers are written against a Spring {@code Page}.
     * What changed is the element type: {@code PlayerDTO}, through the same {@code PlusFeatureService} gate
     * {@link #getAllPlayers} uses, so there is one rule in the game for what a viewer may see about a player.
     */
    @GetMapping("/paged")
    public Page<PlayerDTO> getPlayersPaged(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size,
                                           @RequestParam(defaultValue = "playerValue") String sortBy,
                                           @RequestParam(defaultValue = "desc") String direction,
                                           @AuthenticationPrincipal User user) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                Set.of("id", "name", "rating", "age", "playerValue", "position", "nationality"));
        Pageable pageable = PageRequest.of(page, size, sort);
        Long viewerTeamId = plusFeatures.viewerTeamId(user);
        return playerRepository.findAll(pageable).map(player -> PlayerDTO.from(
                player, 0, null, plusFeatures.talentOrNull(player, user, viewerTeamId)));
    }
}