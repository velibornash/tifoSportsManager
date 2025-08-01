package org.example.footballmanager.controller;

import org.example.footballmanager.dto.GoalEventDTO;
import org.example.footballmanager.dto.MatchDetailsDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.MatchService;
import org.example.footballmanager.util.PlayerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchService matchService;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final TeamRepository teamRepository;
    @Autowired
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;

    public MatchController(MatchService matchService,
                           MatchRepository matchRepository,
                           LineupRepository lineupRepository,
                           MatchPlayerStatsRepository matchPlayerStatsRepository, TeamRepository teamRepository) {
        this.matchService = matchService;
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.teamRepository = teamRepository;
    }

    @PostMapping("/start-simulation")
    @ResponseStatus(HttpStatus.OK)
    public void simulateMatch()
    {
        Team homeTeam = new Team();
        homeTeam.setName("Omladinac");

        Team awayTeam = new Team();
        awayTeam.setName("Sloga");

        List<Player> homePlayers = new ArrayList<>();
        List<Player> awayPlayers = new ArrayList<>();

        homePlayers = PlayerFactory.createOmladinacPlayers(homeTeam);
        teamRepository.save(homeTeam);
        awayPlayers = PlayerFactory.createRandomTeamPlayers("Sloga", awayTeam);
        teamRepository.save(awayTeam);

        Lineup homeLineup = new Lineup();
        homeLineup.setTeam(homeTeam);
        homeLineup.setStartingPlayers(homePlayers.subList(0, 11));
        homeLineup.setSubstitutes(homePlayers.subList(11, 15));
        homeLineup.setFormation("4-4-2");
        lineupRepository.save(homeLineup);
        Lineup awayLineup = new Lineup();
        awayLineup.setTeam(awayTeam);
        awayLineup.setStartingPlayers(awayPlayers.subList(0, 11));
        awayLineup.setSubstitutes(awayPlayers.subList(11, 15));
        awayLineup.setFormation("4-2-3-1");
        lineupRepository.save(awayLineup);
        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        match.setHomeFormation(Formation.F_442.name());
        match.setAwayFormation(Formation.F_433.name());
        match.setHomeTactics(Tactics.defaultBalanced());
        match.setAwayTactics(Tactics.defaultBalanced());
        match.setMatchDate(LocalDateTime.now());
        matchRepository.save(match);

        Match played = matchService.playMatch(match.getId());
        matchService.printMatchDetails(played);

    }

    @PostMapping("/play")
    public Match playMatch(@RequestParam Long homeTeamId,
                           @RequestParam Long awayTeamId,
                           @RequestParam String homeFormation,
                           @RequestParam String awayFormation) {
        return matchService.simulateMatch(homeTeamId, awayTeamId, homeFormation, awayFormation);
    }

    @PostMapping("/{matchId}/assign-lineups")
    public Match assignLineupsToMatch(
            @PathVariable Long matchId,
            @RequestParam Long homeLineupId,
            @RequestParam Long awayLineupId
    )
    {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Meč nije pronađen"));

        Lineup home = lineupRepository.findById(homeLineupId)
                .orElseThrow(() -> new RuntimeException("Home postava nije pronađena"));

        Lineup away = lineupRepository.findById(awayLineupId)
                .orElseThrow(() -> new RuntimeException("Away postava nije pronađena"));

        if (home.getStartingPlayers().size() != 11 || away.getStartingPlayers().size() != 11) {
            throw new RuntimeException("Obe postave moraju imati tačno 11 igrača!");
        }

        match.setHomeLineup(home);
        match.setAwayLineup(away);
        return matchRepository.save(match);
    }

    @PostMapping("/{matchId}/play")
    public Match simulateMatch(@PathVariable Long matchId) {
        return matchService.playMatch(matchId);
    }

    @GetMapping("/{id}/details")
    public ResponseEntity<MatchDetailsDTO> getMatchDetails(@PathVariable Long id) {
        return matchRepository.findById(id)
                .map(match -> {
                    List<PlayerDTO> home = match.getHomeLineup().getStartingPlayers().stream()
                            .map(p -> {
                                MatchPlayerStats stats = matchPlayerStatsRepository.findByMatchAndPlayer(match, p);
                                return PlayerDTO.from(p, match, stats);
                            })
                            .toList();
                    List<PlayerDTO> away = match.getAwayLineup().getStartingPlayers().stream()
                            .map(p -> {
                                MatchPlayerStats stats = matchPlayerStatsRepository.findByMatchAndPlayer(match, p);
                                return PlayerDTO.from(p, match, stats);
                            })
                            .toList();
                    List<GoalEventDTO> goalDTOs = match.getGoals().stream()
                            .map(g -> new GoalEventDTO(
                                    g.getScorer().getName(),
                                    g.getAssistant() != null ? g.getAssistant().getName() : null,
                                    g.getMinute(),
                                    g.getTeam().getName()
                            )).toList();
                    return ResponseEntity.ok(new MatchDetailsDTO(
                            match.getHomeTeam().getName(),
                            match.getAwayTeam().getName(),
                            match.getHomeGoals(),
                            match.getAwayGoals(),
                            home, away, goalDTOs
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    @GetMapping("/{id}/summary")
    public ResponseEntity<String> getMatchSummary(@PathVariable Long id) {
        return matchRepository.findById(id)
                .map(match -> ResponseEntity.ok(matchService.generateMatchReport(match)))
                .orElse(ResponseEntity.notFound().build());
    }
}