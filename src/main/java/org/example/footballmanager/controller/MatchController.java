package org.example.footballmanager.controller;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.DemoMatchRuntime;
import org.example.footballmanager.service.DemoSimulationServiceNew;
import org.example.footballmanager.service.MatchDetailService;
import org.example.footballmanager.service.MatchService;
import org.example.footballmanager.simulator.DemoMatchEngine;
import org.example.footballmanager.simulator.MatchStatisticHandling;
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
    private final MatchDetailService  matchDetailService;
    @Autowired
    public MatchController(MatchService matchService, MatchRepository matchRepository, LineupRepository lineupRepository,
                           TeamRepository teamRepository, PlayerRepository playerRepository,
                           MatchDetailService matchDetailService) {
        this.matchService = matchService;
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.matchDetailService = matchDetailService;
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

        Team awayTeam = teamRepository.findByName("Sremac")
                .orElseThrow(() -> new RuntimeException("Tim 'Sremac' ne postoji u bazi!"));

// 2. Dohvati igrače iz baze (ne kreiraš nove!)
        PlayerFactory playerFactory = new PlayerFactory(playerRepository);
        List<Player> homePlayers = playerFactory.createOmladinacPlayers(homeTeam);
        homePlayers = homePlayers.stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        List<Player> awayPlayers = playerFactory.createRandomTeamPlayers("Sremac", awayTeam);
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

    @GetMapping("/{matchId}")
    public ResponseEntity<MatchDTO> getMatch(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(MatchDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    @GetMapping("/{matchId}/detail")
    public ResponseEntity<List<MatchEventFlatDTO>> getMatchDetail(@PathVariable Long matchId) {
        try {
            List<MatchEventFlatDTO> events = matchDetailService.getMatchEventsFlat(matchId);
            return ResponseEntity.ok(events);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}