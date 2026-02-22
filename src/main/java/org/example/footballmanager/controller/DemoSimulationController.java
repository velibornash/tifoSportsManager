package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.DemoSimulationService;
import org.example.footballmanager.service.DemoSimulationServiceNew;
import org.example.footballmanager.service.MatchService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.util.PlayerFactory;
import org.example.footballmanager.util.TeamFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

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
    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final Random random = new Random(System.currentTimeMillis());
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

// 1. Dohvati Superligu (pretpostavljamo da je ID 1)
        Competition superLiga = competitionRepository.findById(1L)
                .orElseThrow(() -> new RuntimeException("Superliga nije pronađena"));

        // 2. Dohvati tekuću sezonu (hardkodovano 2025 za sada)
        Season currentSeason = seasonRepository.findBySeasonYear(2025)
                .orElseThrow(() -> new RuntimeException("Sezona 2025 nije pronađena"));

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(superLiga, 2025)
                .orElseThrow(() -> new RuntimeException("SeasonCompetition nije pronađen"));

        // 3. Dohvati sve timove iz Superlige
        List<CompetitionEntry> leagueEntries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> allTeamsInLeague = leagueEntries.stream()
                .map(CompetitionEntry::getTeam)
                .filter(t -> t != null)
                .toList();

        if (allTeamsInLeague.size() < 2) {
            throw new RuntimeException("Nema dovoljno timova u Superligi za demo meč");
        }

        // 4. Home tim = Omladinac (fiksno)
        Team homeTeam = allTeamsInLeague.stream()
                .filter(t -> "OFK Omladinac".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Omladinac nije u Superligi"));

        // 5. Away tim = random iz lige, različit od home
        List<Team> possibleAway = allTeamsInLeague.stream()
                .filter(t -> !t.getId().equals(homeTeam.getId()))
                .toList();

        if (possibleAway.isEmpty()) {
            throw new RuntimeException("Nema protivnika za Omladinac u Superligi");
        }
        Team awayTeam = possibleAway.get(random.nextInt(possibleAway.size()));

        log.info("Demo meč: {} vs {} (random iz Superlige)", homeTeam.getName(), awayTeam.getName());

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

        simulateRestOfMatchday(superLiga, currentSeason, homeTeam, awayTeam);
        return match.getId();
    }

    private void simulateRestOfMatchday(Competition league, Season season, Team alreadyPlayedHome, Team alreadyPlayedAway) {
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow();
        // 1. GameClock (ostaje isto)
        GameClock clock = gameClockRepository.findById(1L).orElseGet(() -> {
            GameClock newClock = new GameClock();
            newClock.setId(1L);
            return newClock;
        });
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> teams = entries.stream().map(CompetitionEntry::getTeam).toList();

        // Izuzmi već odigrani par
        List<Team> remainingTeams = teams.stream()
                .filter(t -> !t.getId().equals(alreadyPlayedHome.getId()) && !t.getId().equals(alreadyPlayedAway.getId()))
                .collect(Collectors.toList());

        // Nasumično spari preostalih 8 timova u 4 para
        Collections.shuffle(remainingTeams);
        for (int i = 0; i < remainingTeams.size(); i += 2) {
            if (i + 1 >= remainingTeams.size()) break; // neparan broj timova (malo verovatno)

            Team home = remainingTeams.get(i);
            Team away = remainingTeams.get(i + 1);

            // Generiši random rezultat (0-5 golova po timu)
            int homeGoals = random.nextInt(6);
            int awayGoals = random.nextInt(6);

            // Kreiraj i snimi meč
            Match simulatedMatch = new Match();
            simulatedMatch.setHomeTeam(home);
            simulatedMatch.setAwayTeam(away);
            simulatedMatch.setHomeGoals(homeGoals);
            simulatedMatch.setAwayGoals(awayGoals);
            simulatedMatch.setMatchDate(clock.getCurrentDate()); // isti dan
            matchRepository.save(simulatedMatch);

            // Ažuriraj tabelu (CompetitionEntry)
            CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home).orElseThrow();
            homeEntry.setPoints(homeEntry.getPoints() + (homeGoals > awayGoals ? 3 : homeGoals == awayGoals ? 1 : 0));
            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);

            CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                    .stream()
                    .findFirst()  // uzmi prvi ako ih ima više (posle čišćenja baze neće biti)
                    .orElseThrow(() -> new RuntimeException("Tim " + away.getName() + " nije u ligi"));            awayEntry.setPoints(awayEntry.getPoints() + (awayGoals > homeGoals ? 3 : awayGoals == homeGoals ? 1 : 0));
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

            competitionEntryRepository.save(homeEntry);
            competitionEntryRepository.save(awayEntry);

            // Na kraju metode, posle svih ažuriranja
            List<CompetitionEntry> updatedEntries = competitionEntryRepository.findBySeasonCompetition(sc);
            updatedEntries.sort(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                    .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                    .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()));

            for (int pos = 0; pos < updatedEntries.size(); pos++) {
                CompetitionEntry entry = updatedEntries.get(pos);
                entry.setPosition(pos + 1);
                competitionEntryRepository.save(entry);
            }

            log.info("Simuliran meč: {} {}:{} {} (ažurirana tabela)",
                    home.getName(), homeGoals, awayGoals, away.getName());
        }
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
