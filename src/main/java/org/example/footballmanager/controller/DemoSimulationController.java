package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.DemoCombinedSimulationService;
import org.example.footballmanager.service.MatchService;
import org.example.footballmanager.util.PlayerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DemoSimulationController {
    private final MatchService matchService;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final DemoCombinedSimulationService demoService;

    @Autowired
    public DemoSimulationController(MatchService matchService,
                                    MatchRepository matchRepository,
                                    LineupRepository lineupRepository,
                                    MatchPlayerStatsRepository matchPlayerStatsRepository,
                                    TeamRepository teamRepository, PlayerRepository playerRepository, DemoCombinedSimulationService demoService) {
        this.matchService = matchService;
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.demoService = demoService;
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

    // JEDINI ENDPOINT - kada se pozove, startuje i animaciju i evente u pozadini
    @SneakyThrows
    @GetMapping("/start-demo")
    public ResponseEntity<Map<String, String>> startDemo() {
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
        Thread.sleep(2000);
        demoService.setMatchId(matchId);
        demoService.startDemoSimulation(matchId);
        return ResponseEntity.ok(Map.of(
                "status", "prepared",
                "message", "Simulacija pokrenuta – podaci bi trebalo da stižu",
                "position_socket", "/demo-position-updates",
                "event_socket", "/demo-match-events"
        ));
    }
}