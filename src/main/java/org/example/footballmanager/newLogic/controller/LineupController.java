package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
        Sort sort = direction.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        return lineupRepository.findAll(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), sort))
                .getContent();
    }

    @GetMapping("/{id}")
    public Lineup getById(@PathVariable Long id) {
        return lineupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Lineup not found"));
    }

    @PostMapping
    public Lineup createLineup(@RequestBody Lineup lineup) {
        if (lineup.getStartingPlayers() == null || lineup.getStartingPlayers().size() != 11) {
            throw new RuntimeException("Mora biti tačno 11 startnih igrača!");
        }
        return lineupRepository.save(lineup);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        lineupRepository.deleteById(id);
    }
}
