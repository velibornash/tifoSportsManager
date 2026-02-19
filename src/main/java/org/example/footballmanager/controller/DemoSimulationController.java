package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.DemoSimulationService;
import org.example.footballmanager.service.DemoSimulationServiceNew;
import org.example.footballmanager.service.MatchService;
import org.example.footballmanager.util.PlayerFactory;
import org.example.footballmanager.util.TeamFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DemoSimulationController {

    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final TeamFactory teamFactory;
    private final PlayerRepository playerRepository;
    private final DemoSimulationService demoService;
    private final DemoSimulationServiceNew demoSimulationService;

    /**
     * Kreira lineup za dati tim sa listom igrača i formacijom.
     * KLJUČNO: ponovo učitaj igrače iz baze da budu managed entity.
     */
    private Lineup createLineupForMatch(Team team, List<Player> players, String formationName) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setFormation(formationName);

        // Početna postava (11 igrača)
        List<Player> managedStarting = players.subList(0, Math.min(11, players.size()))
                .stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        // Rezervni igrači (do 4)
        List<Player> managedSubs = players.size() > 11 ? players.subList(11, Math.min(15, players.size()))
                .stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList() : List.of();

        lineup.setStartingPlayers(managedStarting);
        lineup.setSubstitutes(managedSubs);
        // match se ne setuje ovde – postavlja se kasnije u Match entitetu
        return lineupRepository.save(lineup);
    }

    private long createMatchAndReturnId()
    {
        // 1. Dohvati postojeće timove ili napravi
        Team homeTeam = teamFactory.findOrCreate("Omladinac");
        Team awayTeam = teamFactory.findOrCreate("Sremac");


        // 2. Dohvati igrače preko PlayerFactory
        PlayerFactory playerFactory = new PlayerFactory(playerRepository);
        List<Player> homePlayers = playerFactory.createOmladinacPlayers(homeTeam)
                .stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        List<Player> awayPlayers = playerFactory.createRandomTeamPlayers("Sremac", awayTeam)
                .stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        // 3. Kreiraj lineup
        Lineup homeLineup = createLineupForMatch(homeTeam, homePlayers, "4-4-2");
        Lineup awayLineup = createLineupForMatch(awayTeam, awayPlayers, "4-2-3-1");

        // 4. Kreiraj match
        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        match.setMatchDate(LocalDateTime.now());
        matchRepository.save(match);

        Long matchId = match.getId();
        System.out.println("Match ID: " + matchId);
        return matchId;
    }
    /**
     * Endpoint koji startuje demo simulaciju: kreira timove, lineup, match i pokreće WS evente.
     */
    @SneakyThrows
    @GetMapping("/start-demo-old")
    public ResponseEntity<Map<String, String>> startDemo() {
        Thread.sleep(800); // mali delay da frontend dobije signal

        Long matchId = createMatchAndReturnId();
        System.out.println("Match ID: " + matchId);
        Thread.sleep(2000); // da se match sačuva pre starta simulacije
                demoService.startDemoSimulation(matchId)
                .thenAccept(played -> {
                    log.info("Simulacija završena za meč {}", matchId);

                })
                .exceptionally(throwable -> {
                    log.error("Greška u simulaciji meča {}", matchId, throwable);
                    return null;
                });

        return ResponseEntity.ok(Map.of(
                "status", "prepared",
                "message", "Simulacija pokrenuta – podaci bi trebalo da stižu",
                "position_socket", "/demo-position-updates",
                "event_socket", "/demo-match-events",
                "matchId", matchId.toString()
        ));
    }

    @SneakyThrows
    @GetMapping("/start-demo")
    public ResponseEntity<Map<String, String>> startDemoNew() {
        Thread.sleep(800); // mali delay da frontend dobije signal

        Long matchId = createMatchAndReturnId();
        System.out.println("Match ID: " + matchId);
        demoSimulationService.startDemoSimulation(matchId)
                        .thenAccept(played -> {
            log.info("Simulacija završena za meč {}", matchId);

        })
                .exceptionally(throwable -> {
                    log.error("Greška u simulaciji meča {}", matchId, throwable);
                    return null;
                });

        return ResponseEntity.ok(Map.of(
                "status", "prepared",
                "message", "Simulacija pokrenuta – podaci bi trebalo da stižu",
                "position_socket", "/demo-position-updates",
                "event_socket", "/demo-match-events",
                "matchId", matchId.toString()
        ));
    }
}
