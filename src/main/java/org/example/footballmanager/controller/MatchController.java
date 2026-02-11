package org.example.footballmanager.controller;

import org.example.footballmanager.dto.GoalEventDTO;
import org.example.footballmanager.dto.MatchDetailsDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.MatchService;
import org.example.footballmanager.util.PlayerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchService matchService;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final TeamRepository teamRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;

    @Autowired
    public MatchController(MatchService matchService,
                           MatchRepository matchRepository,
                           LineupRepository lineupRepository,
                           MatchPlayerStatsRepository matchPlayerStatsRepository,
                           TeamRepository teamRepository) {
        this.matchService = matchService;
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.teamRepository = teamRepository;
    }

    @PostMapping("/start-simulation")
    public void simulateMatch() {
        Team homeTeam = new Team(); homeTeam.setName("Omladinac");
        Team awayTeam = new Team(); awayTeam.setName("Sloga");
        teamRepository.save(homeTeam); teamRepository.save(awayTeam);

        List<Player> homePlayers = PlayerFactory.createOmladinacPlayers(homeTeam);
        List<Player> awayPlayers = PlayerFactory.createRandomTeamPlayers("Sloga", awayTeam);

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
        match.setMatchDate(LocalDateTime.now());
        matchRepository.save(match);

        Match played = matchService.playMatch(match.getId());
        System.out.println(matchService.generateMatchReport(played));
    }

    @PostMapping("/play")
    public Match playMatch(@RequestParam Long homeTeamId,
                           @RequestParam Long awayTeamId,
                           @RequestParam String homeFormation,
                           @RequestParam String awayFormation) {
        return matchService.simulateMatch(homeTeamId, awayTeamId, homeFormation, awayFormation);
    }

    @PostMapping("/{matchId}/assign-lineups")
    public Match assignLineups(@PathVariable Long matchId,
                               @RequestParam Long homeLineupId,
                               @RequestParam Long awayLineupId) {
        Match match = matchRepository.findById(matchId).orElseThrow(() -> new RuntimeException("Meč nije pronađen"));
        Lineup home = lineupRepository.findById(homeLineupId).orElseThrow(() -> new RuntimeException("Home postava nije pronađena"));
        Lineup away = lineupRepository.findById(awayLineupId).orElseThrow(() -> new RuntimeException("Away postava nije pronađena"));

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
                            .map(p -> PlayerDTO.from(p, match, matchPlayerStatsRepository.findByMatchAndPlayer(match, p)))
                            .toList();
                    List<PlayerDTO> away = match.getAwayLineup().getStartingPlayers().stream()
                            .map(p -> PlayerDTO.from(p, match, matchPlayerStatsRepository.findByMatchAndPlayer(match, p)))
                            .toList();
                    List<GoalEventDTO> goals = match.getGoals().stream()
                            .map(g -> new GoalEventDTO(
                                    g.getScorer().getName(),
                                    g.getAssistant() != null ? g.getAssistant().getName() : null,
                                    g.getMinute(),
                                    g.getScorer().getTeam().getName(),
                                    true))
                            .toList();
                    return ResponseEntity.ok(new MatchDetailsDTO(
                            match.getHomeTeam().getName(),
                            match.getAwayTeam().getName(),
                            match.getHomeGoals(),
                            match.getAwayGoals(),
                            home, away, goals
                    ));
                }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<String> getMatchSummary(@PathVariable Long id) {
        return matchRepository.findById(id)
                .map(match -> ResponseEntity.ok(matchService.generateMatchReport(match)))
                .orElse(ResponseEntity.notFound().build());
    }
}
