package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.springframework.data.domain.PageRequest;
import org.example.footballmanager.newLogic.util.SortWhitelist;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.Optional;

@RestController
@RequestMapping("/lineups")
public class LineupController {

    private final LineupRepository lineupRepository;

    public LineupController(LineupRepository lineupRepository) {
        this.lineupRepository = lineupRepository;
    }

    @GetMapping
    public List<Lineup> getAll(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "100") int size,
                               @RequestParam(defaultValue = "id") String sortBy,
                               @RequestParam(defaultValue = "desc") String direction) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                Set.of("id", "name", "formation"));
        return lineupRepository.findAll(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), sort))
                .getContent();
    }

    @GetMapping("/{id}")
    public Lineup getById(@PathVariable Long id) {
        return lineupRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Lineup " + id + " not found"));
    }

    @PostMapping
    public Lineup createLineup(@RequestBody Lineup lineup) {
        if (lineup.getStartingPlayers() == null || lineup.getStartingPlayers().size() != 11) {
            // 400, not a raw RuntimeException. A raw one fell into the catch-all and arrived as a 500,
            // so a manager who sent the wrong number of players was told the server had broken - and the
            // message went through the error path rather than being a deliberate refusal.
            throw new IllegalArgumentException(
                    "A lineup must have exactly 11 starting players, got "
                            + lineup.getStartingPlayers().size() + ".");
        }
        return lineupRepository.save(lineup);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        lineupRepository.deleteById(id);
    }
}
