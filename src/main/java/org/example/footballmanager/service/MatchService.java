package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.simulator.MatchSimulator;
import org.example.footballmanager.util.MatchRatingCalculator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

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

    @Async
    @Transactional
    @SneakyThrows
    public CompletableFuture<Match> playMatch(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));

        if (match.getHomeLineup() == null || match.getAwayLineup() == null)
            throw new RuntimeException("Postave nisu dodeljene meču.");

        List<Player> homePlayers = match.getHomeLineup().getStartingPlayers();
        List<Player> awayPlayers = match.getAwayLineup().getStartingPlayers();

        if (homePlayers.size() != 11 || awayPlayers.size() != 11)
            throw new RuntimeException("Each team must have exactly 11 players in lineup.");

        Crowd crowd = new Crowd();
        Referee referee = new Referee();

        Tactics homeTactics = new Tactics();
        Formation homeFormation = new Formation();
        homeFormation.setName("Home formation");
        homeTactics.setName("Home tactics");
        homeTactics.setFormation(homeFormation);

        Tactics awayTactics = new Tactics();
        Formation awayFormation = new Formation();
        awayFormation.setName("Away formation");
        awayTactics.setName("Away tactics");
        awayTactics.setFormation(awayFormation);

        matchSimulator.simulateMatch(match, crowd, referee, homeTactics, awayTactics, homePlayers, awayPlayers);

        // Osveži match iz baze da bi eventovi bili učitani (važno posle simulacije)
        match = matchRepository.findById(matchId).orElseThrow();

// Brojanje golova
        final Team homeTeam = match.getHomeTeam();
        final Team awayTeam = match.getAwayTeam();

        match.setHomeGoals((int) match.getGoals().stream()
                .filter(g -> g.getScorer() != null && g.getScorer().getTeam().equals(homeTeam))
                .count());

        match.setAwayGoals((int) match.getGoals().stream()
                .filter(g -> g.getScorer() != null && g.getScorer().getTeam().equals(awayTeam))
                .count());

        // Povrede, kartoni, ocene, statistika
        simulateInjuriesAndCards(homePlayers, match);
        simulateInjuriesAndCards(awayPlayers, match);
        assignRatings(homePlayers, match);
        assignRatings(awayPlayers, match);
        savePlayerStats(match, homePlayers);
        savePlayerStats(match, awayPlayers);

        Match saved = matchRepository.save(match);
        log.info("Simulacija završena za meč {}", matchId);

        return CompletableFuture.completedFuture(saved);
    }

    // Ostale metode ostaju iste (assignRatings, simulateInjuriesAndCards, savePlayerStats, simulateMatch, generateMatchReport, appendPlayerRatings)
    private void assignRatings(List<Player> players, Match match) {
        for (Player player : players)
            player.setRating(MatchRatingCalculator.calculate(player, match));
    }

    private void simulateInjuriesAndCards(List<Player> players, Match match) {
        for (Player player : players) {
            if (random.nextDouble() < 0.05) {
                InjuryEvent injury = new InjuryEvent();
                injury.setMinute(random.nextInt(90) + 1);
                injury.setPlayer(player);
                injury.setMatch(match);
                injury.apply();
            }
            if (random.nextDouble() < 0.1) {
                YellowCardEvent yc = new YellowCardEvent();
                yc.setMinute(random.nextInt(90) + 1);
                yc.setPlayer(player);
                yc.setMatch(match);
                yc.apply();
            }
        }
    }

    private void savePlayerStats(Match match, List<Player> players) {
        for (Player player : players) {
            long goals = match.getGoals().stream().filter(g -> g.getScorer().equals(player)).count();
            long assists = match.getGoals().stream().filter(g -> player.equals(g.getAssistant())).count();

            MatchPlayerStats stats = new MatchPlayerStats();
            stats.setMatch(match);
            stats.setPlayer(player);
            stats.setGoals((int) goals);
            stats.setAssists((int) assists);
            stats.setYellowCards((int) match.getYellowCards().stream().filter(y -> y.getPlayer().equals(player)).count());
            stats.setRedCards((int) match.getRedCards().stream().filter(r -> r.getPlayer().equals(player)).count());
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

    public String generateMatchReport(Match match) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s %d - %d %s%n%n",
                match.getHomeTeam().getName(),
                match.getHomeGoals(),
                match.getAwayGoals(),
                match.getAwayTeam().getName()));

        sb.append("Strelci:\n");
        match.getGoals().stream().sorted(Comparator.comparingInt(GoalEvent::getMinute))
                .forEach(g -> sb.append(String.format("⚽ %d' %s%s%n",
                        g.getMinute(),
                        g.getScorer().getName(),
                        g.getAssistant() != null ? " (asist. " + g.getAssistant().getName() + ")" : ""
                )));

        sb.append("\nEventi:\n");
        match.getAllMatchEvents().stream().sorted(Comparator.comparingInt(MatchEvent::getMinute))
                .forEach(e -> sb.append(String.format("[%d'] %s%n", e.getMinute(), e.getDescription())));

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