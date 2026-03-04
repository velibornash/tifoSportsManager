package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.MatchStartEvent;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.TacticsAdjustmentService;
import org.example.footballmanager.util.events.EventCreator;
import org.example.footballmanager.util.events.MatchEventFactory;
import org.example.footballmanager.util.match.MatchContext;
import org.example.footballmanager.util.players.PlayerFactory;
import org.example.footballmanager.util.teams.TeamStrengthCalculator;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class MatchEngine {

    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final Random random = new Random();
    private final Set<Long> runningMatches = ConcurrentHashMap.newKeySet();
    private final MatchPlaybackEngine matchPlaybackEngine;
    private final GameClockRepository gameClockRepository;
    private final CompetitionRepository competitionRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerFactory playerFactory;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final EventCreator eventCreator;
    private final MatchStatisticEngine matchStatisticEngine;

    public Match loadAndValidateMatch(long matchId) {return matchRepository.findById(matchId).orElseThrow(() -> new RuntimeException("Match not found"));}
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
    public Match createMatch() {
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

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(superLiga, 2025)
                .orElseThrow(() -> new RuntimeException("SeasonCompetition nije pronađen"));

        List<CompetitionEntry> leagueEntries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> allTeamsInLeague = leagueEntries.stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
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

        return match;
    }
    public boolean startSimulationOnlyIfNotRunning(long matchId) {
        if (!runningMatches.add(matchId)) {
            log.info("Match {} već se simulira!", matchId);
            return true;
        }
        return false;
    }
    public Tactics createHomeTactics(Match match) {
        Tactics tactics = new Tactics();
        Formation formation = new Formation();
        formation.setName(match.getHomeLineup().getFormation() != null ? match.getHomeLineup().getFormation() : "4-4-2");
        formation.setOffenseModifier(1.05);
        formation.setDefenseModifier(0.95);
        formation.setPossessionModifier(1.0);
        tactics.setFormation(formation);
        return tactics;
    }
    public Tactics createAwayTactics(Match match) {
        Tactics tactics = new Tactics();
        Formation formation = new Formation();
        formation.setName(match.getAwayLineup().getFormation() != null ? match.getAwayLineup().getFormation() : "4-2-3-1");
        formation.setOffenseModifier(1.1);
        formation.setDefenseModifier(0.98);
        formation.setPossessionModifier(1.05);
        tactics.setFormation(formation);
        return tactics;
    }
    public MatchRuntime simulateFullMatch(Match match) {

        MatchRuntime rt = new MatchRuntime();
        rt= matchPlaybackEngine.initializeRuntimeAndPositions(rt);
        rt.homePlayers = new ArrayList<>(match.getHomeLineup().getStartingPlayers());
        rt.awayPlayers = new ArrayList<>(match.getAwayLineup().getStartingPlayers());

        rt.runtimeEvents = new ArrayList<>();
        rt.runtimeGoals = new ArrayList<>();
        rt.homeTactics = createHomeTactics(match);
        rt.awayTactics = createAwayTactics(match);
        MatchEventFactory factory = new MatchEventFactory();
        MatchContext context = new MatchContext(match, rt.crowd, rt.referee, rt.homeTactics, rt.awayTactics);
        MatchStartEvent matchStartEvent = new MatchStartEvent();
        matchStartEvent.setMinute(1);
        matchStartEvent.setMatch(match);
        matchStartEvent.setHomeTeamName(match.getHomeTeam().getName());
        matchStartEvent.setAwayTeamName(match.getAwayTeam().getName());
        rt.runtimeEvents.add(matchStartEvent);
        // generišemo sve minute unapred
        for (int minute = 1; minute <= 90; minute++) {
            context.setCurrentMinute(minute);
                updateFatigue(context);
                updatePossession(context, rt.homePlayers, rt.awayPlayers, rt.homeTactics.getFormation(), rt.awayTactics.getFormation());
                tacticsAdjustmentService.adjustTactics(context);
                MatchEvent event = factory.createRandomEvent(context, rt.homePlayers, rt.awayPlayers, rt.homeTactics.getFormation(), rt.awayTactics.getFormation());

                if (event != null) {
                    event.setMinute(minute);
                    event.apply();
                    rt.runtimeEvents.add(event);

                    processSpecialEvents(event, rt, match);if (event instanceof GoalEvent goal) {rt.runtimeGoals.add(goal);}
                }

            // SNIMANJE POZICIJA – na kraju svake minute (ili češće ako želiš finiji replay)
            // Koristiš duboku kopiju da se ne menja kasnije
            rt.positionHistory.add(new MatchRuntime.TickPositionSnapshot(minute * 10, rt.players)); // npr. tick = minute * 10
            rt.ballHistory.add(new BallPositionDTO(rt.ball.getX(), rt.ball.getY())); // kopija lopte
        }

        log.info("Engine završio generisanje meča. Eventa: {}", rt.runtimeEvents.size());
        return rt;
    }
    public void simulateRestOfMatchDay(Competition league, Season season, Team alreadyPlayedHome, Team alreadyPlayedAway) {
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow();

        GameClock clock = gameClockRepository.findById(1L).orElseThrow();

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> teams = entries.stream().map(CompetitionEntry::getTeam).toList();

        List<Team> remainingTeams = teams.stream()
                .filter(t -> {
                    if (alreadyPlayedHome == null || alreadyPlayedAway == null) return true;
                    return !t.getId().equals(alreadyPlayedHome.getId()) && !t.getId().equals(alreadyPlayedAway.getId());}).collect(Collectors.toList());

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

            generateSimulatedMatchEvents(simulatedMatch, homeGoals, awayGoals);

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
        log.info("Kolo završeno – pozicije ažurirane za ligu {}", league.getName());
    }
    private void processSpecialEvents(MatchEvent event, MatchRuntime rt, Match match) {

        if (event instanceof GoalEvent goal) {
            goal.setMatch(match);
            if (goal.getTeam().equals(match.getHomeTeam())) {
                rt.homeGoals++;
            } else {
                rt.awayGoals++;
            }
            goal.setScoreAfterGoal(rt.homeGoals + ":" + rt.awayGoals);
           // rt.runtimeGoals.add(goal);
        }
    }
    private void updateFatigue(MatchContext context) {
        context.setFatigueFactor(
                Math.max(0.7, context.getFatigueFactor() - 0.002));
    }
    private void updatePossession(MatchContext context, List<Player> homePlayers, List<Player> awayPlayers, Formation homeFormation, Formation awayFormation) {

        double homeStrength =
                TeamStrengthCalculator.calculateTeamStrength(
                        homePlayers,
                        homeFormation,
                        context.getHomeTactics(),
                        true);

        double awayStrength =
                TeamStrengthCalculator.calculateTeamStrength(
                        awayPlayers,
                        awayFormation,
                        context.getAwayTactics(),
                        false);

        double total = homeStrength + awayStrength;

        if (random.nextDouble() < homeStrength / total) {
            context.setPossessionTeam(context.getMatch().getHomeTeam());
        } else {
            context.setPossessionTeam(context.getMatch().getAwayTeam());
        }
    }
    public void simulateSingleMatch(Team home, Team away, SeasonCompetition sc, GameClock clock) {

        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setMatchDate(clock.getCurrentDate());

        Random rnd = new Random();
        int homeGoals = rnd.nextInt(6);
        int awayGoals = rnd.nextInt(6);

        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);

        matchRepository.save(match);
        generateSimulatedMatchEvents(match, homeGoals, awayGoals);

        CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home)
                .orElseThrow(() -> new RuntimeException("Home team is not in league: " + home.getName()));

        CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                .orElseThrow(() -> new RuntimeException("Away team not in a league: " + away.getName()));

        if (homeGoals > awayGoals) {
            homeEntry.setPoints(homeEntry.getPoints() + 3);
            homeEntry.setWins(homeEntry.getWins() + 1);
        } else if (homeGoals == awayGoals) {
            homeEntry.setPoints(homeEntry.getPoints() + 1);
            awayEntry.setPoints(awayEntry.getPoints() + 1);
            homeEntry.setDraws(homeEntry.getDraws() + 1);
            awayEntry.setDraws(awayEntry.getDraws() + 1);
        } else {
            awayEntry.setPoints(awayEntry.getPoints() + 3);
            awayEntry.setWins(awayEntry.getWins() + 1);
        }

        homeEntry.setLosses(homeEntry.getLosses() + (homeGoals < awayGoals ? 1 : 0));
        awayEntry.setLosses(awayEntry.getLosses() + (awayGoals < homeGoals ? 1 : 0));

        homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
        homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);
        awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
        awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

        competitionEntryRepository.save(homeEntry);
        competitionEntryRepository.save(awayEntry);

        log.info("Simulated match: {} {}:{} {} | Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                home.getName(), homeGoals, awayGoals, away.getName(),
                homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
    }
    public void generateSimulatedMatchEvents(Match simulatedMatch, int homeGoals, int awayGoals) {
        Team home = simulatedMatch.getHomeTeam();
        Team away = simulatedMatch.getAwayTeam();

        List<Player> homePlayers = playerRepository.findByTeam(home);
        List<Player> awayPlayers = playerRepository.findByTeam(away);

        if (homePlayers.isEmpty() || awayPlayers.isEmpty()) {
            log.warn("Nema igrača za generisanje eventa – tim: {}", home.getName());
            return;
        }
        Random rnd = new Random();
        int remainingHomeGoals = homeGoals;
        int remainingAwayGoals = awayGoals;
        int lastMinute = 0;
        while (remainingHomeGoals > 0 || remainingAwayGoals > 0) {
            boolean isHomeGoal;
            if (remainingHomeGoals == 0) {
                isHomeGoal = false;
            } else if (remainingAwayGoals == 0) {
                isHomeGoal = true;
            } else {
                double homeChance = (double) remainingHomeGoals / (remainingHomeGoals + remainingAwayGoals);
                isHomeGoal = rnd.nextDouble() < (homeChance + 0.1); // +10% bias ka domu ako su izjednačeni
            }

            Team scoringTeam = isHomeGoal ? home : away;
            List<Player> scoringPlayers = isHomeGoal ? homePlayers : awayPlayers;
            List<Player> opponentPlayers = isHomeGoal ? awayPlayers : homePlayers;

            GoalEvent goal = eventCreator.createRandomGoalEventForSimulateMatch(simulatedMatch, scoringTeam, scoringPlayers, opponentPlayers, rnd);

            if (goal != null) {
                int remainingGoals = remainingHomeGoals + remainingAwayGoals - 1; // -1 jer ovaj gol već ide
                int minMinute = lastMinute + 1;
                int maxMinute = 90 - remainingGoals * 3; // ostavi bar 3 minuta po preostalom golu

                if (maxMinute < minMinute) maxMinute = minMinute;
                if (maxMinute > 90) maxMinute = 90;

                int minute = rnd.nextInt(minMinute, maxMinute + 1); // bound exclusive → +1
                goal.setMinute(minute);
                goal.setMatch(simulatedMatch);
                // Smanji brojač preostalih golova
                if (isHomeGoal) {
                    remainingHomeGoals--;
                } else {
                    remainingAwayGoals--;
                }
                if(isHomeGoal) {goal.setScoreAfterGoal((homeGoals-remainingHomeGoals) + ":" + (awayGoals-remainingAwayGoals));}
                else{goal.setScoreAfterGoal((homeGoals-remainingHomeGoals) + ":" + (awayGoals-remainingAwayGoals));}

                // Snimi event u bazu
                matchEventRepository.save(goal);
                lastMinute = minute;

            }
        }

        // 3. Dodaj fake statistiku (šutevi, korneri, kartoni...)
        matchStatisticEngine.generateFakeAdditionalStats(simulatedMatch, homePlayers, awayPlayers, homeGoals, awayGoals, rnd);
    }
}