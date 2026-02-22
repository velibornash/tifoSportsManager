package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DemoSimulationController {

    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final TeamFactory teamFactory;
    private final PlayerFactory playerFactory;
    private final PlayerRepository playerRepository;
    private final DemoSimulationService demoService;
    private final DemoSimulationServiceNew demoSimulationService;
    private final GameClockRepository gameClockRepository;
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
    // Pomoćna metoda za fazu (prilagođeno tvojoj JS logici)
    private SeasonPhase determinePhase(int month) {
        if (month >= 7 && month <= 8) return SeasonPhase.PRE_SEASON;
        else if (month >= 9 || month <= 5) return SeasonPhase.SEASON_IN_PROGRESS;
        else return SeasonPhase.OFF_SEASON;
    }
    private long createMatchAndReturnId() {
        // 1. GameClock (ostaje isto)
        GameClock clock = gameClockRepository.findById(1L).orElseGet(() -> {
            GameClock newClock = new GameClock();
            newClock.setId(1L);
            return newClock;
        });

        ZoneId zone = ZoneId.of("Europe/Belgrade");
        LocalDateTime currentCET = LocalDateTime.now(zone);
        clock.setCurrentDate(currentCET);
        clock.setCurrentSeason(currentCET.getMonthValue() >= 7 ? currentCET.getYear() : currentCET.getYear() - 1);
        gameClockRepository.save(clock);

        // 2. Dohvati ili kreiraj timove – koristi factory koji već radi find-or-create
        Team homeTeam = teamFactory.findOrCreate("OFK Omladinac");
        Team awayTeam = teamFactory.findOrCreate("Sremac Berkasovo");  // koristi puno ime ako postoji u seed-u

        // Sigurnosno: proveri da li su timovi zaista u bazi posle factory-ja
        if (homeTeam.getId() == null || awayTeam.getId() == null) {
            log.error("Tim nije sačuvan u bazi nakon findOrCreate!");
            throw new RuntimeException("Greška pri kreiranju timova za demo meč");
        }

        log.info("Koristim timove za demo: {} (id={}) vs {} (id={})",
                homeTeam.getName(), homeTeam.getId(), awayTeam.getName(), awayTeam.getId());

        // 3. Dohvati igrače – uzmi iz baze (ne iz runtime liste)
        List<Player> homePlayers = playerRepository.findByTeam(homeTeam);
        List<Player> awayPlayers = playerRepository.findByTeam(awayTeam);

        if (homePlayers.isEmpty() || awayPlayers.isEmpty()) {
            log.warn("Nema igrača za tim – možda seed nije popunio igrače?");
            // Opcionalno: pozovi factory da popuni igrače
            if (homePlayers.isEmpty()) {
                playerFactory.createOmladinacPlayers(homeTeam);
                homePlayers = playerRepository.findByTeam(homeTeam);
            }
            if (awayPlayers.isEmpty()) {
                playerFactory.createRandomTeamPlayers(awayTeam.getName(), awayTeam);
                awayPlayers = playerRepository.findByTeam(awayTeam);
            }
        }

        // 4. Kreiraj lineup-e
        Lineup homeLineup = createLineupForMatch(homeTeam, homePlayers, "4-4-2");
        Lineup awayLineup = createLineupForMatch(awayTeam, awayPlayers, "4-2-3-1");

        // 5. Kreiraj i snimi meč
        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        match.setMatchDate(clock.getCurrentDate());

        match = matchRepository.save(match);  // ovo mora da vrati entitet sa generisanim ID-om

        log.info("Kreiran demo meč ID: {}, Home: {}, Away: {}",
                match.getId(), match.getHomeTeam().getName(), match.getAwayTeam().getName());

        return match.getId();
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
