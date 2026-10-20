package org.example.footballmanager.newLogic.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.LeagueMilestonesDTO;
import org.example.footballmanager.newLogic.dto.TopAssistDTO;
import org.example.footballmanager.newLogic.dto.TopScorerDTO;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.Player;
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
    private final PlayerRepository playerRepository;
    private final SeasonService seasonService;
    private final LeagueMilestoneService leagueMilestoneService;

    @Autowired
    public StatsController(CompetitionRepository competitionRepository,
                           SeasonCompetitionRepository seasonCompetitionRepository,
                           CompetitionEntryRepository competitionEntryRepository,
                           GoalEventRepository goalEventRepository,
                           TeamRepository teamRepository,
                           PlayerRepository playerRepository,
                           SeasonService seasonService,
                           LeagueMilestoneService leagueMilestoneService) {
        this.competitionRepository = competitionRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.goalEventRepository = goalEventRepository;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
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
                .filter(g -> g.scorerName() != null)
                .toList();

        // **A scorer is a player; teamIds are clubs.** This used to read
        // `teamIds.contains(g.scorerId())`, which compares two unrelated id spaces and so can never
        // legitimately match - the league top scorers were empty for ever even with the goals fetched
        // correctly. Player 2409 belongs to team 1, and no team 2409 exists.
        //
        // Players are resolved once, in one read, rather than a query per goal.
        Map<Long, Player> scorers = playersById(seasonGoals.stream().map(GoalEvent::scorerId).toList());

        Map<Long, Integer> goalsByPlayer = new HashMap<>();
        for (GoalEvent g : seasonGoals) {
            Player scorer = scorers.get(g.scorerId());
            if (playsFor(scorer, teamIds)) {
                goalsByPlayer.merge(g.scorerId(), 1, Integer::sum);
            }
        }

        List<TopScorerDTO> result = goalsByPlayer.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> new TopScorerDTO(
                        scorers.get(e.getKey()).getName(),
                        e.getValue(),
                        // The real club, not the literal "Team" this used to send on every row.
                        scorers.get(e.getKey()).getTeam().getName()))
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
                .filter(g -> g.assistantName() != null && g.assistantId() != null)
                .toList();

        // Same player-id-versus-team-id mistake as the scorers above, and the same correction.
        Map<Long, Player> assistants = playersById(assistGoals.stream().map(GoalEvent::assistantId).toList());

        Map<Long, Integer> assistsByPlayer = new HashMap<>();
        for (GoalEvent g : assistGoals) {
            if (playsFor(assistants.get(g.assistantId()), teamIds)) {
                assistsByPlayer.merge(g.assistantId(), 1, Integer::sum);
            }
        }

        List<TopAssistDTO> result = assistsByPlayer.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> new TopAssistDTO(
                        assistants.get(e.getKey()).getName(),
                        e.getValue(),
                        assistants.get(e.getKey()).getTeam().getName()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /** One bulk read of the players behind a set of goals, instead of a query per goal. */
    private Map<Long, Player> playersById(List<Long> playerIds) {
        List<Long> wanted = playerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, Player> byId = new HashMap<>();
        for (Player player : playerRepository.findAllById(wanted)) {
            byId.put(player.getId(), player);
        }
        return byId;
    }

    /** Does this player count for this league's tables? A goal by a foreigner does not. */
    private boolean playsFor(Player player, List<Long> teamIds) {
        return player != null && player.getTeam() != null && teamIds.contains(player.getTeam().getId());
    }
}
