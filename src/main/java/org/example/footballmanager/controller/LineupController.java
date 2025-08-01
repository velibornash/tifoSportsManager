package org.example.footballmanager.controller;

import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.repository.LineupRepository;
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
    public List<Lineup> getAll() {
        return lineupRepository.findAll();
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