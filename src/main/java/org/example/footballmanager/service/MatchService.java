package org.example.footballmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.MatchEndedEvent;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.simulator.MatchSimulator;
import org.example.footballmanager.util.MatchRatingCalculator;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
public class MatchService {

    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final TeamRepository teamRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final PlayerRepository playerRepository;
    private final MatchSimulator matchSimulator;

    private final Random random = new Random();

    public MatchService(MatchRepository matchRepository,
                        LineupRepository lineupRepository,
                        TeamRepository teamRepository,
                        MatchPlayerStatsRepository matchPlayerStatsRepository,
                        PlayerRepository playerRepository,
                        MatchSimulator matchSimulator) {
        this.matchRepository = matchRepository;
        this.lineupRepository = lineupRepository;
        this.teamRepository = teamRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.playerRepository = playerRepository;
        this.matchSimulator = matchSimulator;
    }

    public Match playMatch(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));

        Lineup homeLineup = match.getHomeLineup();
        Lineup awayLineup = match.getAwayLineup();

        if (homeLineup == null || awayLineup == null) {
            throw new RuntimeException("Postave nisu dodeljene meču.");
        }

        List<Player> homePlayers = homeLineup.getStartingPlayers();
        List<Player> awayPlayers = awayLineup.getStartingPlayers();

        if (homePlayers.size() != 11 || awayPlayers.size() != 11) {
            throw new RuntimeException("Each team must have exactly 11 players in lineup.");
        }

        // Simulacija preko MatchSimulator-a
        matchSimulator.simulateMatch(match, homePlayers, awayPlayers);

        // Konačan rezultat baziran na golovima
        int finalHomeGoals = (int) match.getGoals().stream()
                .filter(g -> g.getTeam().equals(match.getHomeTeam()))
                .count();
        int finalAwayGoals = (int) match.getGoals().stream()
                .filter(g -> g.getTeam().equals(match.getAwayTeam()))
                .count();

        match.setHomeGoals(finalHomeGoals);
        match.setAwayGoals(finalAwayGoals);
        match.setPlayed(true);

        // Dodaj kraj utakmice kao event
        MatchEndedEvent endedEvent = new MatchEndedEvent();
        endedEvent.setMatch(match);
        endedEvent.setMinute(90);
        //endedEvent.setDescription("Match ended: " + finalHomeGoals + " - " + finalAwayGoals);
        endedEvent.setKeyEvent(true);
        endedEvent.setVisualize(true);
        match.getAllMatchEvents().add(endedEvent);

        simulateInjuriesAndCards(homePlayers);
        simulateInjuriesAndCards(awayPlayers);

        assignRatings(homePlayers, match);
        assignRatings(awayPlayers, match);

        savePlayerStats(match, homePlayers);
        savePlayerStats(match, awayPlayers);

        return matchRepository.save(match);
    }

    private void assignRatings(List<Player> players, Match match) {
        for (Player player : players) {
            int rating = MatchRatingCalculator.calculate(player, match);
            player.setRating(rating);
        }
    }

    private void simulateInjuriesAndCards(List<Player> players) {
        // Placeholder za povrede i kartone
    }

    private void savePlayerStats(Match match, List<Player> players) {
        for (Player player : players) {
            long goals = match.getGoals().stream()
                    .filter(g -> g.getScorer().equals(player))
                    .count();

            long assists = match.getGoals().stream()
                    .filter(g -> player.equals(g.getAssistant()))
                    .count();

            MatchPlayerStats stats = new MatchPlayerStats();
            stats.setMatch(match);
            stats.setPlayer(player);
            stats.setGoals((int) goals);
            stats.setAssists((int) assists);
            stats.setYellowCards(random.nextInt(2));
            stats.setRedCards(0);
            stats.setMinutesPlayed(90);
            stats.setRating(player.getRating());

            matchPlayerStatsRepository.save(stats);
        }
    }

    public Match simulateMatch(Long homeTeamId, Long awayTeamId, String homeFormation, String awayFormation) {
        Match match = new Match();
        match.setHomeFormation(homeFormation);
        match.setAwayFormation(awayFormation);
        match.setMatchDate(java.time.LocalDateTime.now());

        Team homeTeam = teamRepository.findById(homeTeamId)
                .orElseThrow(() -> new RuntimeException("Home team not found"));
        Team awayTeam = teamRepository.findById(awayTeamId)
                .orElseThrow(() -> new RuntimeException("Away team not found"));

        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);

        return matchRepository.save(match);
    }

    public void printMatchDetails(Match match) {
        System.out.println("\n" + generateMatchReport(match));
    }

    private void printRatings(List<Player> players, Match match) {
        for (Player player : players) {
            MatchPlayerStats stats = matchPlayerStatsRepository.findByMatchAndPlayer(match, player);
            System.out.printf("- %s: %d (golova: %d, asistencija: %d)%n",
                    player.getName(),
                    stats.getRating(),
                    stats.getGoals(),
                    stats.getAssists());
        }
    }

    public String generateMatchReport(Match match) {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("%s %d - %d %s%n%n",
                match.getHomeTeam().getName(),
                match.getHomeGoals(),
                match.getAwayGoals(),
                match.getAwayTeam().getName()));

        sb.append("Strelci:\n");
        match.getGoals().stream()
                .sorted(Comparator.comparingInt(GoalEvent::getMinute))
                .forEach(g -> {
                    String assist = g.getAssistant() != null ? " (asist. " + g.getAssistant().getName() + ")" : "";
                    sb.append(String.format("⚽ %d' %s%s %s%n",
                            g.getMinute(),
                            g.getScorer().getName(),
                            assist,
                            g.getScoreAfterGoal()));
                });

        sb.append("\nIzveštaj:\n");
        match.getAllMatchEvents().stream()
                .sorted(Comparator.comparingInt(MatchEvent::getMinute))
                .forEach(e -> sb.append(String.format("%s%n", e.getDescription())));

        sb.append("\nOcene igrača - ").append(match.getHomeTeam().getName()).append("\n");
        appendPlayerRatings(sb, match.getHomeLineup().getStartingPlayers(), match);

        sb.append("\nOcene igrača - ").append(match.getAwayTeam().getName()).append("\n");
        appendPlayerRatings(sb, match.getAwayLineup().getStartingPlayers(), match);

        return sb.toString();
    }

    private void appendPlayerRatings(StringBuilder sb, List<Player> players, Match match) {
        for (Player player : players) {
            MatchPlayerStats stats = matchPlayerStatsRepository.findByMatchAndPlayer(match, player);
            sb.append(String.format("- %s: %d (golova: %d, asistencija: %d)%n",
                    player.getName(),
                    stats.getRating(),
                    stats.getGoals(),
                    stats.getAssists()));
        }
    }
}