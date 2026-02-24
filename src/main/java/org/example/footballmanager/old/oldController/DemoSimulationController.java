package org.example.footballmanager.old.oldController;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.old.oldService.DemoSimulationService;
import org.example.footballmanager.old.oldService.DemoSimulationServiceNew;
import org.example.footballmanager.util.players.PlayerFactory;
import org.example.footballmanager.util.teams.TeamFactory;
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

    private Lineup createLineupForMatch(Team team, List<Player> players, String formationName) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setFormation(formationName);

        List<Player> managedStarting = players.subList(0, Math.min(11, players.size()))
                .stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList();

        List<Player> managedSubs = players.size() > 11 ? players.subList(11, Math.min(15, players.size()))
                .stream()
                .map(p -> playerRepository.getReferenceById(p.getId()))
                .toList() : List.of();

        lineup.setStartingPlayers(managedStarting);
        lineup.setSubstitutes(managedSubs);
        return lineupRepository.save(lineup);
    }

    private long createMatchAndReturnId() {
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

        Competition superLiga = competitionRepository.findById(1L)
                .orElseThrow(() -> new RuntimeException("Superliga nije pronađena"));

        Season currentSeason = seasonRepository.findBySeasonYear(2025)
                .orElseThrow(() -> new RuntimeException("Sezona 2025 nije pronađena"));

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(superLiga, 2025)
                .orElseThrow(() -> new RuntimeException("SeasonCompetition nije pronađen"));

        List<CompetitionEntry> leagueEntries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> allTeamsInLeague = leagueEntries.stream()
                .map(CompetitionEntry::getTeam)
                .filter(t -> t != null)
                .toList();

        if (allTeamsInLeague.size() < 2) {
            throw new RuntimeException("Nema dovoljno timova u Superligi za demo meč");
        }

        Team homeTeam = allTeamsInLeague.stream()
                .filter(t -> "OFK Omladinac".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Omladinac nije u Superligi"));

        List<Team> possibleAway = allTeamsInLeague.stream()
                .filter(t -> !t.getId().equals(homeTeam.getId()))
                .toList();

        if (possibleAway.isEmpty()) {
            throw new RuntimeException("Nema protivnika za Omladinac u Superligi");
        }
        Team awayTeam = possibleAway.get(random.nextInt(possibleAway.size()));

        log.info("Demo meč: {} vs {} (random iz Superlige)", homeTeam.getName(), awayTeam.getName());

        List<Player> homePlayers = playerRepository.findByTeam(homeTeam);
        List<Player> awayPlayers = playerRepository.findByTeam(awayTeam);

        if (homePlayers.isEmpty() || awayPlayers.isEmpty()) {
            log.warn("Nema igrača za tim – popunjavam...");
            if (homePlayers.isEmpty()) {
                playerFactory.createOmladinacPlayers(homeTeam);
                homePlayers = playerRepository.findByTeam(homeTeam);
            }
            if (awayPlayers.isEmpty()) {
                playerFactory.createRandomTeamPlayers(awayTeam.getName(), awayTeam);
                awayPlayers = playerRepository.findByTeam(awayTeam);
            }
        }

        Lineup homeLineup = createLineupForMatch(homeTeam, homePlayers, "4-4-2");
        Lineup awayLineup = createLineupForMatch(awayTeam, awayPlayers, "4-2-3-1");

        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        match.setMatchDate(clock.getCurrentDate());

        match = matchRepository.save(match);

        log.info("Kreiran demo meč ID: {}, Home: {}, Away: {}",
                match.getId(), match.getHomeTeam().getName(), match.getAwayTeam().getName());

        return match.getId();
    }

    private void simulateRestOfMatchday(Competition league, Season season, Team alreadyPlayedHome, Team alreadyPlayedAway) {
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow();

        GameClock clock = gameClockRepository.findById(1L).orElseThrow();

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> teams = entries.stream().map(CompetitionEntry::getTeam).toList();

        List<Team> remainingTeams = teams.stream()
                .filter(t -> {
                    if (alreadyPlayedHome == null || alreadyPlayedAway == null) return true;
                    return !t.getId().equals(alreadyPlayedHome.getId()) && !t.getId().equals(alreadyPlayedAway.getId());
                })
                .collect(Collectors.toList());

        if (remainingTeams.size() % 2 != 0) {
            log.warn("Neparan broj timova za simulaciju: {}", remainingTeams.size());
        }

        Collections.shuffle(remainingTeams);

        for (int i = 0; i + 1 < remainingTeams.size(); i += 2) {
            Team home = remainingTeams.get(i);
            Team away = remainingTeams.get(i + 1);

            int homeGoals = random.nextInt(6);
            int awayGoals = random.nextInt(6);

            Match simulatedMatch = new Match();
            simulatedMatch.setHomeTeam(home);
            simulatedMatch.setAwayTeam(away);
            simulatedMatch.setHomeGoals(homeGoals);
            simulatedMatch.setAwayGoals(awayGoals);
            simulatedMatch.setMatchDate(clock.getCurrentDate());
            matchRepository.save(simulatedMatch);

            CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home)
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("Tim " + home.getName() + " nije u ligi"));

            CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("Tim " + away.getName() + " nije u ligi"));

            homeEntry.setPoints(homeEntry.getPoints() + (homeGoals > awayGoals ? 3 : homeGoals == awayGoals ? 1 : 0));
            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);

            awayEntry.setPoints(awayEntry.getPoints() + (awayGoals > homeGoals ? 3 : awayGoals == homeGoals ? 1 : 0));
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

           homeEntry.setWins((homeEntry.getWins() != null ? homeEntry.getWins() : 0) + (homeGoals > awayGoals ? 1 : 0));
            homeEntry.setDraws((homeEntry.getDraws() != null ? homeEntry.getDraws() : 0) + (homeGoals == awayGoals ? 1 : 0));
            homeEntry.setLosses((homeEntry.getLosses() != null ? homeEntry.getLosses() : 0) + (homeGoals < awayGoals ? 1 : 0));

            awayEntry.setWins((awayEntry.getWins() != null ? awayEntry.getWins() : 0) + (awayGoals > homeGoals ? 1 : 0));
            awayEntry.setDraws((awayEntry.getDraws() != null ? awayEntry.getDraws() : 0) + (awayGoals == homeGoals ? 1 : 0));
            awayEntry.setLosses((awayEntry.getLosses() != null ? awayEntry.getLosses() : 0) + (awayGoals < homeGoals ? 1 : 0));

            competitionEntryRepository.save(homeEntry);
            competitionEntryRepository.save(awayEntry);

            log.info("Simuliran meč: {} {}:{} {} | Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                    home.getName(), homeGoals, awayGoals, away.getName(),
                    homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                    awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
        }

        // Sortiraj i ažuriraj pozicije
/*        List<CompetitionEntry> updatedEntries = competitionEntryRepository.findBySeasonCompetition(sc);
        updatedEntries.sort(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()));

        for (int pos = 0; pos < updatedEntries.size(); pos++) {
            CompetitionEntry entry = updatedEntries.get(pos);
            entry.setPosition(pos + 1);
            competitionEntryRepository.save(entry);
        }*/

        log.info("Kolo završeno – pozicije ažurirane za ligu {}", league.getName());
    }

    @SneakyThrows
    @GetMapping("/start-demo-old")
    public ResponseEntity<Map<String, String>> startDemoNew() {
        Thread.sleep(800);

        Long matchId = createMatchAndReturnId();
        log.info("Kreiran demo meč sa ID: {}", matchId);

        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        Season currentSeason = seasonRepository.findBySeasonYear(2025).orElse(null);

        if (superLiga == null || currentSeason == null) {
            log.error("Ne mogu da pronađem ligu ili sezonu!");
            return ResponseEntity.badRequest().body(Map.of("error", "Liga ili sezona nije pronađena"));
        }

        // 1. Dohvati demo timove (da ih isključimo iz simulacije)
        Match demoMatch = matchRepository.findById(matchId).orElseThrow(() -> new RuntimeException("Demo meč nije kreiran"));
        Team omladinac = demoMatch.getHomeTeam();
        Team protivnik = demoMatch.getAwayTeam();

        // 2. Prvo simuliraj 4 random meča (isključujući Omladinac par)
        simulateRestOfMatchday(superLiga, currentSeason, omladinac, protivnik);

        // 3. Zatim odigraj demo meč (Omladinac vs random)
        demoSimulationService.startDemoSimulation(matchId)
                .thenAccept(played -> {
                    log.info("Demo simulacija završena za meč ID: {}", matchId);

                    // 4. Ažuriraj tabelu za ceo dan (svih 5 mečeva)
                    updateLeagueTableForMatchday(superLiga, currentSeason);
                })
                .exceptionally(throwable -> {
                    log.error("Greška u demo simulaciji meča {}", matchId, throwable);
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

    private void updateLeagueTableForMatchday(Competition league, Season season) {
// U metodi updateLeagueTableForMatchDay ili gde god treba

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow(() -> new RuntimeException("Sezona nije pronađena za ligu"));

// Dohvati SVE CompetitionEntry za tu sezonu lige
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);

// Izvuci ID-ove timova
        List<Long> teamIds = entries.stream()
                .map(entry -> entry.getTeam().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

// Sad koristi teamIds za pretragu mečeva
        List<Match> allLeagueMatches = matchRepository.findByHomeTeamIdInAndAwayTeamIdIn(teamIds, teamIds);

        log.info("Ukupno pronađeno {} mečeva u ligi {}", allLeagueMatches.size(), league.getName());

        if (allLeagueMatches.isEmpty()) {
            log.warn("Nema mečeva u ligi za ažuriranje tabele!");
            return;
        }

        // 2. Sortiraj OPADAJUĆE po datumu (najnoviji prvi)
        allLeagueMatches.sort(Comparator.comparing(Match::getMatchDate, Comparator.reverseOrder()));

        // 3. Uzmi samo poslednjih 5 (poslednje kolo)
        List<Match> lastMatches = allLeagueMatches.stream()
                .limit(5)
                .toList();

        log.info("Ažuriranje tabele na osnovu poslednjih {} mečeva (poslednje kolo)", lastMatches.size());

        // 5. Ažuriraj samo za poslednjih 5 mečeva
        for (Match match : lastMatches) {
 /*           if (match.getHomeGoals() == null || match.getAwayGoals() == null) {
                log.debug("Preskačem neodigran meč: {} vs {}", match.getHomeTeam().getName(), match.getAwayTeam().getName());
                continue;
            }*/

            Team home = match.getHomeTeam();
            Team away = match.getAwayTeam();

            CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home)
                    .stream().findFirst().orElse(null);
            CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                    .stream().findFirst().orElse(null);

            if (homeEntry == null || awayEntry == null) {
                log.warn("Nema entry-ja za timove u meču {} vs {}", home.getName(), away.getName());
                continue;
            }

            int homeG = match.getHomeGoals();
            int awayG = match.getAwayGoals();

/*            homeEntry.setPoints(homeEntry.getPoints() + (homeG > awayG ? 3 : homeG == awayG ? 1 : 0));
            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeG);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayG);*/
            homeEntry.setWins(homeEntry.getWins() + (homeG > awayG ? 1 : 0));
            homeEntry.setDraws(homeEntry.getDraws() + (homeG == awayG ? 1 : 0));
            homeEntry.setLosses(homeEntry.getLosses() + (homeG < awayG ? 1 : 0));

/*            awayEntry.setPoints(awayEntry.getPoints() + (awayG > homeG ? 3 : awayG == homeG ? 1 : 0));
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayG);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeG);*/
            awayEntry.setWins(awayEntry.getWins() + (awayG > homeG ? 1 : 0));
            awayEntry.setDraws(awayEntry.getDraws() + (awayG == homeG ? 1 : 0));
            awayEntry.setLosses(awayEntry.getLosses() + (awayG < homeG ? 1 : 0));

            competitionEntryRepository.save(homeEntry);
            competitionEntryRepository.save(awayEntry);

            log.info("Ažuriran meč {} {}:{} {} → Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                    home.getName(), homeG, awayG, away.getName(),
                    homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                    awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
        }

        // 6. Sortiraj i postavi pozicije
        List<CompetitionEntry> updatedEntries = competitionEntryRepository.findBySeasonCompetition(sc);
        updatedEntries.sort(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()));

        for (int pos = 0; pos < updatedEntries.size(); pos++) {
            CompetitionEntry entry = updatedEntries.get(pos);
            entry.setPosition(pos + 1);
            competitionEntryRepository.save(entry);
        }

        log.info("Tabela lige ažurirana na osnovu poslednjih {} mečeva (poslednje kolo)", lastMatches.size());
    }}