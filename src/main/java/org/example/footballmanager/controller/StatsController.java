package org.example.footballmanager.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.TopAssistDTO;
import org.example.footballmanager.dto.TopScorerDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final CompetitionRepository  competitionRepository;
    private final PlayerRepository playerRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    @Autowired
    public StatsController(CompetitionRepository competitionRepository, PlayerRepository playerRepository, SeasonRepository seasonRepository, SeasonCompetitionRepository seasonCompetitionRepository, CompetitionEntryRepository competitionEntryRepository) {
        this.competitionRepository = competitionRepository;
        this.playerRepository = playerRepository;
        this.seasonRepository = seasonRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
    }

    // Top 10 strelaca (po totalGoals DESC)
    @GetMapping("/leagues/{leagueId}/topscorers")
    public ResponseEntity<List<TopScorerDTO>> getTopScorers(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));

        Season currentSeason = seasonRepository.findBySeasonYear(2025)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, 2025)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona lige nije pronađena"));

        // Dohvati sve timove u ligi
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Long> teamIds = entries.stream()
                .map(e -> e.getTeam().getId())
                .toList();

        // Dohvati top 10 igrača iz tih timova, sortirano po totalGoals DESC
        List<Player> topScorers = playerRepository.findByTeamIdIn(teamIds)
                .stream()
                .sorted(Comparator.comparingInt(Player::getTotalGoals).reversed())
                .limit(10)
                .toList();

        List<TopScorerDTO> result = topScorers.stream()
                .map(p -> new TopScorerDTO(
                        p.getName(),
                        p.getTotalGoals(),
                        p.getTeam() != null ? p.getTeam().getName() : "Bez kluba"
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // Top 10 asistenata (po totalAssists DESC)
    @GetMapping("/leagues/{leagueId}/topassists")
    public ResponseEntity<List<TopAssistDTO>> getTopAssists(@PathVariable Long leagueId) {
        // Ista logika kao gore, samo po totalAssists
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));

        Season currentSeason = seasonRepository.findBySeasonYear(2025)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, 2025)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona lige nije pronađena"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Long> teamIds = entries.stream()
                .map(e -> e.getTeam().getId())
                .toList();

        List<Player> topAssistants = playerRepository.findByTeamIdIn(teamIds)
                .stream()
                .sorted(Comparator.comparingInt(Player::getTotalAssists).reversed())
                .limit(10)
                .toList();

        List<TopAssistDTO> result = topAssistants.stream()
                .map(p -> new TopAssistDTO(
                        p.getName(),
                        p.getTotalAssists(),
                        p.getTeam() != null ? p.getTeam().getName() : "Bez kluba"
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
