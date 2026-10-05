package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.springframework.data.domain.PageRequest;
import org.example.footballmanager.newLogic.util.SortWhitelist;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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

    public LineupController(LineupRepository lineupRepository) {
        this.lineupRepository = lineupRepository;
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
    public Map<String, Object> getById(@PathVariable Long id) {
        return view(lineupRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Lineup " + id + " not found")));
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