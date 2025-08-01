package org.example.footballmanager.controller;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Season;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/seasons")
public class SeasonController {

    private final SeasonRepository seasonRepository;

    public SeasonController(SeasonRepository seasonRepository) {
        this.seasonRepository = seasonRepository;
    }

    @GetMapping
    public List<Season> getAllSeasons() {
        return seasonRepository.findAll();
    }

    @PostMapping
    public Season createSeason(@RequestBody Season season) {
        return seasonRepository.save(season);
    }
}