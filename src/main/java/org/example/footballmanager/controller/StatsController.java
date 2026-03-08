package org.example.footballmanager.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.LeagueMilestonesDTO;
import org.example.footballmanager.dto.TopAssistDTO;
import org.example.footballmanager.dto.TopScorerDTO;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionEntry;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.SeasonCompetition;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.LeagueMilestoneService;
import org.example.footballmanager.service.SeasonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final CompetitionRepository competitionRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final GoalEventRepository goalEventRepository;
    private final SeasonService seasonService;
    private final LeagueMilestoneService leagueMilestoneService;

    @Autowired
    public StatsController(CompetitionRepository competitionRepository,
                           SeasonCompetitionRepository seasonCompetitionRepository,
                           CompetitionEntryRepository competitionEntryRepository,
                           GoalEventRepository goalEventRepository,
                           SeasonService seasonService,
                           LeagueMilestoneService leagueMilestoneService) {
        this.competitionRepository = competitionRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.goalEventRepository = goalEventRepository;
        this.seasonService = seasonService;
        this.leagueMilestoneService = leagueMilestoneService;
    }

    @GetMapping("/leagues/{leagueId}/milestones")
    public ResponseEntity<LeagueMilestonesDTO> getLeagueMilestones(@PathVariable Long leagueId,
                                                                   @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        return ResponseEntity.ok(leagueMilestoneService.buildLeagueMilestones(league, activeSeasonYear));
    }

    @GetMapping("/leagues/{leagueId}/topscorers")
    public ResponseEntity<List<TopScorerDTO>> getTopScorers(@PathVariable Long leagueId,
                                                            @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));

        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League season not found"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Long> teamIds = entries.stream().map(e -> e.getTeam().getId()).toList();

        List<GoalEvent> seasonGoals = goalEventRepository.findAll().stream()
                .filter(GoalEvent::isScored)
                .filter(g -> g.getMatch() != null
                        && g.getMatch().getCompetition() != null
                        && Objects.equals(g.getMatch().getCompetition().getId(), leagueId)
                        && Objects.equals(g.getMatch().getSeasonYear(), activeSeasonYear)
                        && g.getScorer() != null
                        && g.getScorer().getTeam() != null
                        && teamIds.contains(g.getScorer().getTeam().getId()))
                .toList();

        Map<Player, Integer> goalsByPlayer = new HashMap<>();
        for (GoalEvent g : seasonGoals) {
            goalsByPlayer.merge(g.getScorer(), 1, Integer::sum);
        }

        List<TopScorerDTO> result = goalsByPlayer.entrySet().stream()
                .sorted(Map.Entry.<Player, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> new TopScorerDTO(
                        e.getKey().getName(),
                        e.getValue(),
                        e.getKey().getTeam() != null ? e.getKey().getTeam().getName() : "No Team"
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/leagues/{leagueId}/topassists")
    public ResponseEntity<List<TopAssistDTO>> getTopAssists(@PathVariable Long leagueId,
                                                            @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));

        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League season not found"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Long> teamIds = entries.stream().map(e -> e.getTeam().getId()).toList();

        List<GoalEvent> seasonGoals = goalEventRepository.findAll().stream()
                .filter(GoalEvent::isScored)
                .filter(g -> g.getMatch() != null
                        && g.getMatch().getCompetition() != null
                        && Objects.equals(g.getMatch().getCompetition().getId(), leagueId)
                        && Objects.equals(g.getMatch().getSeasonYear(), activeSeasonYear)
                        && g.getAssistant() != null
                        && g.getAssistant().getTeam() != null
                        && teamIds.contains(g.getAssistant().getTeam().getId()))
                .toList();

        Map<Player, Integer> assistsByPlayer = new HashMap<>();
        for (GoalEvent g : seasonGoals) {
            assistsByPlayer.merge(g.getAssistant(), 1, Integer::sum);
        }

        List<TopAssistDTO> result = assistsByPlayer.entrySet().stream()
                .sorted(Map.Entry.<Player, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> new TopAssistDTO(
                        e.getKey().getName(),
                        e.getValue(),
                        e.getKey().getTeam() != null ? e.getKey().getTeam().getName() : "No Team"
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
