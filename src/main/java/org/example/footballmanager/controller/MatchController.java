package org.example.footballmanager.controller;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchService matchService;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;

    @Autowired
    public MatchController(MatchService matchService,
                           MatchRepository matchRepository,
                           LineupRepository lineupRepository,
                           MatchPlayerStatsRepository matchPlayerStatsRepository,
                           TeamRepository teamRepository, PlayerRepository playerRepository) {
        this.matchService = matchService;
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
    }

    private Lineup createLineupForMatch(Team team, List<Player> players, String formationName) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setFormation(formationName);

        // KLJUČNO: ponovo učitaj igrače po ID-ovima da budu managed
        List<Player> managedStarting = players.subList(0, 11).stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))  // ili findById ako želiš pun objekat
                .toList();

        List<Player> managedSubs = players.subList(11, Math.min(15, players.size())).stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        lineup.setStartingPlayers(managedStarting);
        lineup.setSubstitutes(managedSubs);
        lineup.setFormation(formationName);
        // Ne setuj match ovde – setuje se kasnije u Match entitetu
        return lineupRepository.save(lineup);
    }

    @SneakyThrows
    @PostMapping("/start-simulation")
    public ResponseEntity<Map<String, Object>> startSimulation() {
        Thread.sleep(800);

// 1. Dohvati postojeće timove iz baze (po imenu)
        Team homeTeam = teamRepository.findByName("Omladinac")
                .orElseThrow(() -> new RuntimeException("Tim 'Omladinac' ne postoji u bazi!"));

        Team awayTeam = teamRepository.findByName("Sloga")
                .orElseThrow(() -> new RuntimeException("Tim 'Sloga' ne postoji u bazi!"));

// 2. Dohvati igrače iz baze (ne kreiraš nove!)
        PlayerFactory playerFactory = new PlayerFactory(playerRepository);
        List<Player> homePlayers = playerFactory.createOmladinacPlayers(homeTeam);
        homePlayers = homePlayers.stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        List<Player> awayPlayers = playerFactory.createRandomTeamPlayers("Sloga", awayTeam);
        awayPlayers = awayPlayers.stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();
// 3. Kreiraj postave (ako ih nema, možeš ih kreirati jednom ili ovde proveriti)
        Lineup homeLineup = createLineupForMatch(homeTeam, homePlayers, "4-4-2");
        Lineup awayLineup = createLineupForMatch(awayTeam, awayPlayers, "4-2-3-1");

        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        match.setMatchDate(LocalDateTime.now());
        matchRepository.save(match);

        Long matchId = match.getId();

        // Pokretanje simulacije asinhrono (transakcija se završila, match je u bazi)
        matchService.playMatch(matchId)
                .thenAccept(played -> {
                    log.info("Simulacija završena za meč {}", matchId);
                    System.out.println(matchService.generateMatchReport(played));
                })
                .exceptionally(throwable -> {
                    log.error("Greška u simulaciji meča {}", matchId, throwable);
                    return null;
                });

        return ResponseEntity.ok(Map.of(
                "matchId", matchId,
                "status", "started"
        ));
    }

    // Ostale metode ostaju nepromenjene
    @PostMapping("/play")
    public Match playMatch(@RequestParam Long homeTeamId, @RequestParam Long awayTeamId,
                           @RequestParam String homeFormation, @RequestParam String awayFormation) {
        return matchService.simulateMatch(homeTeamId, awayTeamId, homeFormation, awayFormation);
    }

    @PostMapping("/{matchId}/assign-lineups")
    public Match assignLineups(@PathVariable Long matchId,
                               @RequestParam Long homeLineupId,
                               @RequestParam Long awayLineupId) {
        Match match = matchRepository.findById(matchId).orElseThrow();
        Lineup home = lineupRepository.findById(homeLineupId).orElseThrow();
        Lineup away = lineupRepository.findById(awayLineupId).orElseThrow();

        match.setHomeLineup(home);
        match.setAwayLineup(away);
        return matchRepository.save(match);
    }

    @PostMapping("/{matchId}/play")
    public CompletableFuture<Match> simulateMatch(@PathVariable Long matchId) {
        return matchService.playMatch(matchId);
    }

/*    @GetMapping("/{id}/details")
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
    }*/

    @GetMapping("/{id}/summary")
    public ResponseEntity<String> getMatchSummary(@PathVariable Long id) {
        return matchRepository.findById(id)
                .map(match -> ResponseEntity.ok(matchService.generateMatchReport(match)))
                .orElse(ResponseEntity.notFound().build());
    }
}