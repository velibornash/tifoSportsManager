package org.example.footballmanager.newLogic.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.LeagueMilestonesDTO;
import org.example.footballmanager.newLogic.dto.TopAssistDTO;
import org.example.footballmanager.newLogic.dto.TopScorerDTO;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.event.GoalEvent;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.LeagueMilestoneService;
import org.example.footballmanager.newLogic.service.SeasonService;
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
    private final TeamRepository teamRepository;
    private final SeasonService seasonService;
    private final LeagueMilestoneService leagueMilestoneService;

    @Autowired
    public StatsController(CompetitionRepository competitionRepository,
                           SeasonCompetitionRepository seasonCompetitionRepository,
                           CompetitionEntryRepository competitionEntryRepository,
                           GoalEventRepository goalEventRepository,
                           TeamRepository teamRepository,
                           SeasonService seasonService,
                           LeagueMilestoneService leagueMilestoneService) {
        this.competitionRepository = competitionRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.goalEventRepository = goalEventRepository;
        this.teamRepository = teamRepository;
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

    @GetMapping("/teams/{teamId}/milestones")
    public ResponseEntity<LeagueMilestonesDTO> getTeamMilestones(@PathVariable Long teamId,
                                                                 @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        return ResponseEntity.ok(leagueMilestoneService.buildTeamMilestones(team, activeSeasonYear));
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

        List<GoalEvent> seasonGoals = goalEventRepository
                .findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(leagueId, activeSeasonYear).stream()
                .filter(g -> g.scorerName() != null
                        && teamIds.contains(g.scorerId()))
                .toList();

        Map<String, Integer> goalsByName = new HashMap<>();
        Map<Long, Integer> goalsById = new HashMap<>();
        for (GoalEvent g : seasonGoals) {
            goalsByName.merge(g.scorerName(), 1, Integer::sum);
            goalsById.merge(g.scorerId(), 1, Integer::sum);
        }

        List<TopScorerDTO> result = goalsByName.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> new TopScorerDTO(
                        e.getKey(),
                        e.getValue(),
                        "Team"
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

        List<GoalEvent> assistGoals = goalEventRepository
                .findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(leagueId, activeSeasonYear).stream()
                .filter(g -> g.assistantName() != null
                        && g.assistantId() != null
                        && teamIds.contains(g.assistantId()))
                .toList();

        Map<String, Integer> assistsByName = new HashMap<>();
        Map<Long, Integer> assistsById = new HashMap<>();
        for (GoalEvent g : assistGoals) {
            assistsByName.merge(g.assistantName(), 1, Integer::sum);
            assistsById.merge(g.assistantId(), 1, Integer::sum);
        }

        List<TopAssistDTO> result = assistsByName.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> new TopAssistDTO(
                        e.getKey(),
                        e.getValue(),
                        "Team"
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
