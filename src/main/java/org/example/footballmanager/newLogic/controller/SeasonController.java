package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Season;
import org.example.footballmanager.newLogic.repository.SeasonRepository;
import org.springframework.data.domain.Sort;
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
        return seasonRepository.findAll(Sort.by(Sort.Direction.DESC, "seasonYear"));
    }

    @PostMapping("/create")
    public Season createSeason(@RequestBody Season season) {
        return seasonRepository.save(season);
    }
}
