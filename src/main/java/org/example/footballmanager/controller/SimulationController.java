package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.SimulationService;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SimulationController {

    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;
    private final SimulationService simulationService;
    private final MatchEngine matchEngine;
    private final MatchStatisticEngine matchStatisticEngine;

    @SneakyThrows
    @GetMapping("/start-demo")
    public ResponseEntity<Map<String, String>> startDemo() {
        //1. Napravi mec
        Match demoMatch = matchEngine.createMatch();

        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        Season currentSeason = seasonRepository.findBySeasonYear(2025).orElse(null);

        if (superLiga == null || currentSeason == null) {
            log.error("Ne mogu da pronađem ligu ili sezonu!");
            return ResponseEntity.badRequest().body(Map.of("error", "Liga ili sezona nije pronađena"));
        }

        Team omladinac = demoMatch.getHomeTeam();
        Team opponent = demoMatch.getAwayTeam();

        // 2. Prvo simuliraj 4 random meča (isključujući Omladinac par)
        matchEngine.simulateRestOfMatchDay(superLiga, currentSeason, omladinac, opponent);

        // 3. Zatim odigraj demo meč (Omladinac vs random)
        simulationService.startSimulation(demoMatch.getId())
                .thenAccept(played -> {
                    log.info("Demo simulacija završena za meč ID: {}", demoMatch.getId());

                    // 4. Ažuriraj tabelu za ceo dan (svih 5 mečeva)
                    matchStatisticEngine.updateLeagueTableForMatchDay(superLiga, currentSeason);
                })
                .exceptionally(throwable -> {
                    log.error("Greška u demo simulaciji meča {}", demoMatch.getId(), throwable);
                    return null;
                });

        return ResponseEntity.ok(Map.of(
                "status", "prepared",
                "message", "Simulacija pokrenuta – podaci bi trebalo da stižu",
                "position_socket", "/demo-position-updates",
                "event_socket", "/demo-match-events",
                "matchId", demoMatch.getId().toString()
        ));
    }
}