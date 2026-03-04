package org.example.footballmanager.cleanSheet;

import io.micrometer.common.lang.Nullable;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.cleanSheet.dto.SimulatedMatchResult;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.util.events.EventCreator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class CleanSheetService {

    @Autowired private CompetitionEntryRepository competitionEntryRepository;
    @Autowired private SeasonCompetitionRepository  seasonCompetitionRepository;
    @Autowired private GameClockRepository gameClockRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private EventCreator eventCreator;
    @Autowired private MatchStatisticEngine matchStatisticEngine;
    @Autowired private TeamRepository teamRepository;

    @Transactional
    public List<SimulatedMatchResult> simulateMatchDay(Competition league, Season season, @Nullable Team alreadyPlayedHome, @Nullable Team alreadyPlayedAway) {
        List<SimulatedMatchResult> simulatedMatchResults = new ArrayList<>();
        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, season.getSeasonYear()).orElseThrow();
        GameClock clock = gameClockRepository.findById(1L).orElseThrow();
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> teams = entries.stream().map(CompetitionEntry::getTeam).toList();
        List<Team> remainingTeams = teams.stream().filter(t -> {if (alreadyPlayedHome == null || alreadyPlayedAway == null) return true;
                    return !t.getId().equals(alreadyPlayedHome.getId()) && !t.getId().equals(alreadyPlayedAway.getId());}).collect(Collectors.toList());

        if (remainingTeams.size() % 2 != 0) {
            log.warn("Neparan broj timova za simulaciju: {}", remainingTeams.size());
        }
        Collections.shuffle(remainingTeams);
        for (int i = 0; i + 1 < remainingTeams.size(); i += 2) {
            Team home = remainingTeams.get(i);
            Team away = remainingTeams.get(i + 1);
            simulatedMatchResults.add(simulateSingleMatch(home, away, sc, clock));
        }

        log.info("Round ended – positions updated for league  {}", league.getName());
        return simulatedMatchResults;
    }
    public SimulatedMatchResult simulateSingleMatch(Team home, Team away, SeasonCompetition sc, GameClock clock) {
        Random rnd = new Random();

        // Kreiraj Match (još ga ne snimamo)
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setMatchDate(clock.getCurrentDate());

        int homeGoals = rnd.nextInt(6);
        int awayGoals = rnd.nextInt(6);

        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);

        // Generiši eventi (golovi + fake stats) – ovo ne snima, samo kreira objekte
        List<MatchEvent> events = generateSimulatedMatchEvents(match, homeGoals, awayGoals);

        // Kreiraj ažurirane CompetitionEntry (Runtime izmene)
        CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home)
                .orElseThrow(() -> new RuntimeException("Home team not found"));

        CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                .orElseThrow(() -> new RuntimeException("Away team not found"));

        // Napravi kopije za ažuriranje (da original ne menjamo)
        CompetitionEntry homeUpdate = copyEntry(homeEntry);
        CompetitionEntry awayUpdate = copyEntry(awayEntry);

        // Ažuriraj bodove, golove, W/D/L
        if (homeGoals > awayGoals) {
            homeUpdate.setPoints(homeUpdate.getPoints() + 3);
            homeUpdate.setWins(homeUpdate.getWins() + 1);
        } else if (homeGoals == awayGoals) {
            homeUpdate.setPoints(homeUpdate.getPoints() + 1);
            awayUpdate.setPoints(awayUpdate.getPoints() + 1);
            homeUpdate.setDraws(homeUpdate.getDraws() + 1);
            awayUpdate.setDraws(awayUpdate.getDraws() + 1);
        } else {
            awayUpdate.setPoints(awayUpdate.getPoints() + 3);
            awayUpdate.setWins(awayUpdate.getWins() + 1);
        }

        homeUpdate.setLosses(homeUpdate.getLosses() + (homeGoals < awayGoals ? 1 : 0));
        awayUpdate.setLosses(awayUpdate.getLosses() + (awayGoals < homeGoals ? 1 : 0));

        homeUpdate.setGoalsScored(homeUpdate.getGoalsScored() + homeGoals);
        homeUpdate.setGoalsConceded(homeUpdate.getGoalsConceded() + awayGoals);
        awayUpdate.setGoalsScored(awayUpdate.getGoalsScored() + awayGoals);
        awayUpdate.setGoalsConceded(awayUpdate.getGoalsConceded() + homeGoals);

        // Vrati rezultat – ništa nije snimljeno u bazi
        return SimulatedMatchResult.builder()
                .match(match)
                .events(events)
                .homeEntryUpdate(homeUpdate)
                .awayEntryUpdate(awayUpdate)
                .summary(home.getName() + " " + homeGoals + ":" + awayGoals + " " + away.getName())
                .homeGoals(homeGoals)
                .awayGoals(awayGoals)
                .build();
    }
    private CompetitionEntry copyEntry(CompetitionEntry original) {
        CompetitionEntry copy = new CompetitionEntry();
        copy.setId(original.getId()); // ako treba
        copy.setTeam(original.getTeam());
        copy.setSeasonCompetition(original.getSeasonCompetition());
        copy.setPoints(original.getPoints());
        copy.setGoalsScored(original.getGoalsScored());
        copy.setGoalsConceded(original.getGoalsConceded());
        copy.setWins(original.getWins());
        copy.setDraws(original.getDraws());
        copy.setLosses(original.getLosses());
        copy.setPosition(original.getPosition());
        return copy;
    }
    public List<MatchEvent> generateSimulatedMatchEvents(Match simulatedMatch, int homeGoals, int awayGoals) {
        List<MatchEvent> events = new ArrayList<>();

        Team home = simulatedMatch.getHomeTeam();
        Team away = simulatedMatch.getAwayTeam();

        List<Player> homePlayers = playerRepository.findByTeam(home);
        List<Player> awayPlayers = playerRepository.findByTeam(away);

        if (homePlayers.isEmpty() || awayPlayers.isEmpty()) {
            log.warn("Nema igrača za generisanje eventa – tim: {}", home.getName());
            return null;
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
                if (isHomeGoal) {
                    remainingHomeGoals--;
                } else {
                    remainingAwayGoals--;
                }
                if(isHomeGoal) {goal.setScoreAfterGoal((homeGoals-remainingHomeGoals) + ":" + (awayGoals-remainingAwayGoals));}
                else{goal.setScoreAfterGoal((homeGoals-remainingHomeGoals) + ":" + (awayGoals-remainingAwayGoals));}

                events.add(goal);
                lastMinute = minute;

            }
        }

        // 3. Dodaj fake statistiku (šutevi, korneri, kartoni...)
        events = generateFakeAdditionalStats(simulatedMatch, homePlayers, awayPlayers, homeGoals, awayGoals, rnd, events);

        return events;
    }
    public List<MatchEvent>  generateFakeAdditionalStats(Match match, List<Player> homePlayers, List<Player> awayPlayers, int homeGoals, int awayGoals, Random rnd, List<MatchEvent> events) {

        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();

        // 1. Realistični brojevi statistike
        // Šutevi: pobednik / bolji tim ima više
        int homeShotsTotal     = homeGoals + rnd.nextInt(6) + 4;           // 4–9 + golovi
        int awayShotsTotal     = awayGoals + rnd.nextInt(6) + 4;

        // Šutevi u okvir: ~35–55% od ukupnih šuteva
        int homeShotsOnTarget  = Math.min(homeGoals + rnd.nextInt(5), (int)(homeShotsTotal * (0.35 + rnd.nextDouble() * 0.2)));
        int awayShotsOnTarget  = Math.min(awayGoals + rnd.nextInt(5), (int)(awayShotsTotal * (0.35 + rnd.nextDouble() * 0.2)));

        int homeShotsOffTarget = homeShotsTotal-homeShotsOnTarget;
        int awayShotsOffTarget = awayShotsTotal-awayShotsOnTarget;

        // Korneri: 2–12 po timu, više kod boljeg tima
        int homeCorners        = rnd.nextInt(11) + 2 + (homeGoals > awayGoals ? 2 : 0);
        int awayCorners        = rnd.nextInt(11) + 2 + (awayGoals > homeGoals ? 2 : 0);

        // Faulovi: 8–25 po timu
        int homeFouls          = rnd.nextInt(18) + 8;
        int awayFouls          = rnd.nextInt(18) + 8;

        // Ofsajdi: 0–8
        int homeOffsides       = rnd.nextInt(9);
        int awayOffsides       = rnd.nextInt(9);

        // Penali: 0–2 po meču, veća šansa ako je mnogo faulova u šesnaestercu
        int totalPenalties     = rnd.nextInt(3); // 0–2
        boolean homePenalty    = totalPenalties > 0 && rnd.nextBoolean();
        boolean awayPenalty    = totalPenalties > 1 || (totalPenalties == 1 && !homePenalty);

        // Kartoni
        int homeYellows        = rnd.nextInt(5) + (homeFouls > 18 ? 1 : 0); // više faulova → više kartona
        int awayYellows        = rnd.nextInt(5) + (awayFouls > 18 ? 1 : 0);
        int homeReds           = rnd.nextInt(2);
        int awayReds           = rnd.nextInt(2);


        // Korneri – snimamo ~40–60% da izgleda realno
        int homeCornersToSave  = (int)(homeCorners * (0.4 + rnd.nextDouble() * 0.2));
        int awayCornersToSave  = (int)(awayCorners * (0.4 + rnd.nextDouble() * 0.2));

        for (int i = 0; i < homeCornersToSave; i++) {
            CornerEvent c = new CornerEvent();
            c.setMatch(match);
            c.setTeam(home);
            c.setMinute(rnd.nextInt(90) + 1);
            c.setPlayer(matchStatisticEngine.getRandomPlayerByPosition(homePlayers, List.of(Position.WNG, Position.MID, Position.ATT), rnd));
            events.add(c);

        }

        for (int i = 0; i < awayCornersToSave; i++) {
            CornerEvent c = new CornerEvent();
            c.setMatch(match);
            c.setTeam(away);
            c.setMinute(rnd.nextInt(90) + 1);
            c.setPlayer(matchStatisticEngine.getRandomPlayerByPosition(awayPlayers, List.of(Position.WNG, Position.MID, Position.ATT), rnd));
            events.add(c);
        }

        // Šutevi na gol – snimamo deo šuteva u okvir
        for (int i = 0; i < homeShotsOnTarget; i++) {
            ShotOnTargetEvent s = new ShotOnTargetEvent();
            s.setMatch(match);
            s.setTeam(home);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(matchStatisticEngine.getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            events.add(s);
        }

        for (int i = 0; i < awayShotsOnTarget; i++) {
            ShotOnTargetEvent s = new ShotOnTargetEvent();
            s.setMatch(match);
            s.setTeam(away);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(matchStatisticEngine.getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            events.add(s);
        }

        // Šutevi na gol – snimamo deo šuteva van okvira
        for (int i = 0; i < homeShotsOffTarget; i++) {
            ShotOffTargetEvent s = new ShotOffTargetEvent();
            s.setMatch(match);
            s.setTeam(home);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(matchStatisticEngine.getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            events.add(s);
        }

        for (int i = 0; i < awayShotsOffTarget; i++) {
            ShotOffTargetEvent s = new ShotOffTargetEvent();
            s.setMatch(match);
            s.setTeam(away);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(matchStatisticEngine.getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            events.add(s);
        }

        // Penali (ako ih ima)
        if (homePenalty) {
            PenaltyEvent p = new PenaltyEvent();
            p.setMatch(match);
            p.setTeam(home);
            p.setMinute(rnd.nextInt(90) + 1);
            p.setTaker(matchStatisticEngine.getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID), rnd));
            p.setScored(rnd.nextDouble() < 0.75); // ~75% uspešnosti penala
            events.add(p);
        }

        if (awayPenalty) {
            PenaltyEvent p = new PenaltyEvent();
            p.setMatch(match);
            p.setTeam(away);
            p.setMinute(rnd.nextInt(90) + 1);
            p.setTaker(matchStatisticEngine.getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID), rnd));
            p.setScored(rnd.nextDouble() < 0.75);
            events.add(p);
        }

        // Žuti kartoni – snimamo ~60% da ne bude previše
        int homeYellowsToSave = (int)(homeYellows * 0.6);
        for (int i = 0; i < homeYellowsToSave; i++) {
            Player offender = matchStatisticEngine.getRandomPlayerByPosition(homePlayers, null, rnd); // bilo ko
            YellowCardEvent yc = new YellowCardEvent();
            yc.setMatch(match);
            yc.setTeam(home);
            yc.setPlayer(offender);
            yc.setMinute(rnd.nextInt(90) + 1);
            events.add(yc);
        }

        // Isto za goste...
        int awayYellowsToSave = (int)(awayYellows * 0.6);
        for (int i = 0; i < awayYellowsToSave; i++) {
            Player offender = matchStatisticEngine.getRandomPlayerByPosition(awayPlayers, null, rnd);
            YellowCardEvent yc = new YellowCardEvent();
            yc.setMatch(match);
            yc.setTeam(away);
            yc.setPlayer(offender);
            yc.setMinute(rnd.nextInt(90) + 1);
            events.add(yc);
        }

        // Crveni kartoni – retki, snimamo sve
        for (int i = 0; i < homeReds; i++) {
            Player offender = matchStatisticEngine.getRandomPlayerByPosition(homePlayers, null, rnd);
            RedCardEvent rc = new RedCardEvent();
            rc.setMatch(match);
            rc.setTeam(home);
            rc.setPlayer(offender);
            rc.setMinute(rnd.nextInt(90) + 1);
            events.add(rc);
        }

        // Isto za goste
        for (int i = 0; i < awayReds; i++) {
            Player offender = matchStatisticEngine.getRandomPlayerByPosition(awayPlayers, null, rnd);
            RedCardEvent rc = new RedCardEvent();
            rc.setMatch(match);
            rc.setTeam(away);
            rc.setPlayer(offender);
            rc.setMinute(rnd.nextInt(90) + 1);
            events.add(rc);
        }

        log.info("Generisana fake statistika za simulirani meč {}:{} {} vs {} – šutevi {}/{}, korneri {}/{}, kartoni {}/{}",
                home.getName(), homeGoals, awayGoals, away.getName(),
                homeShotsTotal, awayShotsTotal, homeCorners, awayCorners, homeYellows, awayYellows);

        return events;
    }
    public List<Team> generateRandomLeagueTeams(int numberOfTeams) {
        List<Team> allTeams = teamRepository.findAll();
        Collections.shuffle(allTeams);
        return allTeams.subList(0, Math.min(numberOfTeams, allTeams.size()));
    }
}