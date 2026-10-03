package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.dto.LineupSaveRequestDTO;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.springframework.data.domain.PageRequest;
import org.example.footballmanager.newLogic.util.SortWhitelist;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;

/**
 * Squad sheets. Readable by any manager, writable only by the club that owns the row.
 *
 * <p><b>This controller carried no authorization at all</b>, and it is the clearest example on the board of
 * why P0-1 exists. Four mappings, not one guard: any logged-in manager could {@code POST} a lineup onto any
 * club in the world and {@code DELETE} any lineup he could name an id for. With 14,880 clubs that is every
 * club in every country, and a squad sheet decides who is fit to play.
 *
 * <p><b>Reads are public to any manager on purpose.</b> Who is in a rival's eleven is a league-table fact, and
 * the game's own line — drawn in {@code CountryTeamPlayersDisclosureTest} — is at <i>secrets</i> (talent,
 * earnings, injuries), not at visibility. Gating the reads would be a lockout, not a permission.
 *
 * <p><b>Both reads used to answer 500, and that was not a fixture.</b> Both returned the raw {@code Lineup}
 * entity, and {@code Team.country} is {@code FetchType.LAZY} — so Jackson walked
 * {@code lineup.team.country.hibernateLazyInitializer} and threw
 * {@code HttpMessageConversionException} on every call that had a lineup to return. The rows are written by
 * the match engine, so in any real world this endpoint answered nothing but 500. {@link #view(Lineup)} is the
 * fix: a flat map, which is also what stopped the endpoint handing out the whole club object graph.
 *
 * <p><b>Neither write has a frontend caller</b> — {@code TeamController}'s {@code lineup-template} is what the
 * game actually uses — so this surface is guarded rather than deleted, at the owner's decision. Its deletion
 * is recorded on the board as an open question rather than taken here.
 */
@RestController
@RequestMapping("/lineups")
public class LineupController {

    private final LineupRepository lineupRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final PlusFeatureService plusFeatures;

    public LineupController(LineupRepository lineupRepository,
                            TeamRepository teamRepository,
                            PlayerRepository playerRepository,
                            PlusFeatureService plusFeatures) {
        this.lineupRepository = lineupRepository;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.plusFeatures = plusFeatures;
    }

    @GetMapping
    public List<Map<String, Object>> getAll(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "100") int size,
                                            @RequestParam(defaultValue = "id") String sortBy,
                                            @RequestParam(defaultValue = "desc") String direction) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                // "name" is deliberately absent: Lineup has no name column, so whitelisting it let a
                // caller ask for a sort on a property that does not exist. The whitelist is only worth
                // having if every entry in it is real.
                Set.of("id", "formation"));
        return lineupRepository.findAll(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), sort))
                .getContent()
                .stream()
                .map(this::view)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        return lineupRepository.findById(id)
                .map(lineup -> ResponseEntity.ok(view(lineup)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Lineup " + id + " not found"));
    }

    /**
     * Files a squad sheet for a club.
     *
     * <p><b>Three defects, in the order they bite.</b> It took a raw {@code Lineup}, which Jackson cannot
     * deserialise at all (see {@code LineupSaveRequestDTO}), so the route had never accepted a body. It had no
     * guard, so any manager could file a sheet for any club. And it saved whatever it was handed, so a body
     * carrying an {@code id} would overwrite an existing row through {@code save()}.
     *
     * <p>Authorization runs <b>before</b> validation, deliberately: a caller with no claim on the club learns
     * nothing about what was wrong with his body, and a refused manager is refused for the reason he was
     * actually refused rather than for an incidental complaint about his eleven.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createLineup(@RequestBody LineupSaveRequestDTO request,
                                                            @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, request == null ? null : request.getTeamId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "You can only file a squad sheet for your own club."));
        }

        List<Long> starterIds = request.getStarterIds() == null ? List.of() : request.getStarterIds();
        List<Long> benchIds = request.getBenchIds() == null ? List.of() : request.getBenchIds();
        if (starterIds.size() != 11) {
            // 400, not a raw RuntimeException. A raw one fell into the catch-all and arrived as a 500,
            // so a manager who sent the wrong number of players was told the server had broken - and the
            // message went through the error path rather than being a deliberate refusal.
            throw new IllegalArgumentException(
                    "A lineup must have exactly 11 starting players, got " + starterIds.size() + ".");
        }

        Team team = teamRepository.findById(request.getTeamId())
                .orElseThrow(() -> new IllegalArgumentException("No such club: " + request.getTeamId()));
        Map<Long, Player> byId = new HashMap<>();
        for (Player player : playerRepository.findAllById(starterIds)) {
            byId.put(player.getId(), player);
        }
        List<Player> starters = starterIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        if (starters.size() != 11) {
            // A distinct answer from "eleven was sent": the request named eleven players and at least one of
            // them does not exist. Saying "you sent the wrong number" here would be true and useless.
            throw new IllegalArgumentException(
                    "A lineup names 11 starting players, but only " + starters.size()
                            + " of them exist. The club's own squad is the pool they come from.");
        }
        List<Player> bench = benchIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(p -> starters.stream().noneMatch(s -> Objects.equals(s.getId(), p.getId())))
                .toList();

        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setFormation(request.getFormation() == null ? "4-4-2" : request.getFormation());
        lineup.setStyle(request.getStyle() == null ? "BALANCED" : request.getStyle());
        lineup.setStartingPlayers(new ArrayList<>(starters));
        lineup.setSubstitutes(new ArrayList<>(bench));
        lineup.setStarterOrderFromIds(new ArrayList<>(starterIds));
        lineup.setBenchOrderFromIds(new ArrayList<>(benchIds));

        return ResponseEntity.ok(view(lineupRepository.save(lineup)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User principal) {
        Lineup lineup = lineupRepository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Lineup " + id + " not found"));

        Long clubId = lineup.getTeam() == null ? null : lineup.getTeam().getId();
        if (!mayManage(principal, clubId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        lineupRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * A club's own manager, or an administrator.
     *
     * <p>Fails closed: an unknown caller, a caller with no club, and a caller naming a club that does not
     * exist are all "not yours". An ownership check that defaults to yes is not a check.
     */
    private boolean mayManage(User principal, Long clubId) {
        if (principal == null || clubId == null) {
            return false;
        }
        boolean administrator = principal.getRole() != null
                && principal.getRole().name().equals("OWNER");
        return administrator || plusFeatures.isOwnTeam(principal, clubId);
    }

    /**
     * The flat shape the reads answer with.
     *
     * <p>Not the entity. {@code Lineup} reaches {@code Team}, which reaches {@code Country}, which is a lazy
     * proxy — and serialising it threw rather than answering. A map also stops the endpoint publishing the
     * club's whole object graph to whoever asked, which is the same rule the player endpoints were closed
     * under.
     */
    private Map<String, Object> view(Lineup lineup) {
        return Map.of(
                "id", lineup.getId(),
                "formation", lineup.getFormation() == null ? "" : lineup.getFormation(),
                "style", lineup.getStyle() == null ? "" : lineup.getStyle(),
                "teamId", lineup.getTeam() == null || lineup.getTeam().getId() == null
                        ? -1L : lineup.getTeam().getId(),
                "starterIds", lineup.getOrderedStarterIds(),
                "benchIds", lineup.getOrderedBenchIds()
        );
    }
}