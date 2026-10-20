package org.example.basketballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.basketballmanager.engine.BbMatchEngine;
import org.example.basketballmanager.engine.BbMatchResult;
import org.example.basketballmanager.engine.BbPlayerGameStats;
import org.example.basketballmanager.model.*;
import org.example.basketballmanager.repository.*;
import org.example.commonmanager.model.CommonCompetition;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BbMatchSimulationService {

    private final BbMatchEngine matchEngine;
    private final BbTeamRepository teamRepository;
    private final BbPlayerRepository playerRepository;
    private final BbMatchRepository matchRepository;
    private final BbMatchFixtureRepository matchFixtureRepository;
    private final BbPlayerSeasonStatsRepository seasonStatsRepository;

    public BbMatchSimulationService(BbMatchEngine matchEngine,
                                    BbTeamRepository teamRepository,
                                    BbPlayerRepository playerRepository,
                                    BbMatchRepository matchRepository,
                                    BbMatchFixtureRepository matchFixtureRepository,
                                    BbPlayerSeasonStatsRepository seasonStatsRepository) {
        this.matchEngine = matchEngine;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.seasonStatsRepository = seasonStatsRepository;
    }

    @Transactional
    public BbMatchResult simulateAndSave(Long homeTeamId, Long awayTeamId) {
        log.info("simulateAndSave called: home={} away={}", homeTeamId, awayTeamId);
        BbTeam home = teamRepository.findById(homeTeamId)
                .orElseThrow(() -> new RuntimeException("Home team not found: " + homeTeamId));
        BbTeam away = teamRepository.findById(awayTeamId)
                .orElseThrow(() -> new RuntimeException("Away team not found: " + awayTeamId));

        return simulate(home, away, null);
    }

    @Transactional
    public BbMatchResult simulateFixture(Long fixtureId) {
        log.info("simulateFixture called for fixtureId={}", fixtureId);
        BbMatchFixture fixture = matchFixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new RuntimeException("Fixture not found: " + fixtureId));
        log.info("Fixture found: home={} away={}", fixture.getHomeTeam().getName(), fixture.getAwayTeam().getName());
        BbMatchResult result = simulate(fixture.getHomeTeam(), fixture.getAwayTeam(), fixture);
        log.info("Simulation complete: {} {} - {} {}", fixture.getHomeTeam().getName(), result.getHomeScore(), result.getAwayScore(), fixture.getAwayTeam().getName());

        fixture.setPlayed(true);
        matchFixtureRepository.save(fixture);

        return result;
    }

    private BbMatchResult simulate(BbTeam home, BbTeam away, BbMatchFixture fixture) {
        log.info("Starting simulation: {} vs {}", home.getName(), away.getName());
        BbMatchResult result = matchEngine.simulate(home, away);
        log.info("Engine returned: {} - {}", result.getHomeScore(), result.getAwayScore());

        CommonCompetition competition = home.getCompetition();
        int seasonYear = fixture != null ? fixture.getSeasonYear() : 2025;
        Long competitionId = competition != null ? competition.getId() : null;

        // Serialize events and player stats for storage
        String eventsJson = result.getEvents() != null
                ? result.getEvents().stream().collect(Collectors.joining("|"))
                : "";
        String homeStatsJson = result.getHomePlayerStats() != null
                ? result.getHomePlayerStats().stream().map(this::playerStatsToString).collect(Collectors.joining(";"))
                : "";
        String awayStatsJson = result.getAwayPlayerStats() != null
                ? result.getAwayPlayerStats().stream().map(this::playerStatsToString).collect(Collectors.joining(";"))
                : "";

        BbMatch match = BbMatch.builder()
                .homeTeam(home)
                .awayTeam(away)
                .seasonYear(seasonYear)
                .roundNumber(fixture != null ? fixture.getRoundNumber() : 0)
                .matchDate(fixture != null ? fixture.getMatchDate() : LocalDateTime.now())
                .played(true)
                .homeScore(result.getHomeScore())
                .awayScore(result.getAwayScore())
                .homeQuarterScores(result.getHomeQuarterScores())
                .awayQuarterScores(result.getAwayQuarterScores())
                .events(eventsJson)
                .homePlayerStats(homeStatsJson)
                .awayPlayerStats(awayStatsJson)
                .build();
        if (competitionId != null) {
            match.setCompetitionId(competitionId);
        }
        if (fixture != null) {
            fixture.setPlayedMatch(match);
            matchFixtureRepository.save(fixture);
            log.info("Fixture saved with playedMatch");
        }
        matchRepository.save(match);
        log.info("Match saved to DB with events and player stats");

        savePlayerStats(result.getHomePlayerStats(), seasonYear, competitionId, home);
        savePlayerStats(result.getAwayPlayerStats(), seasonYear, competitionId, away);
        log.info("CPlayer stats saved");

        return result;
    }

    private String playerStatsToString(BbPlayerGameStats gs) {
        return String.join(",",
                String.valueOf(gs.getPlayerId()),
                gs.getPlayerName(),
                gs.getPosition(),
                String.valueOf(gs.getMinutes()),
                String.valueOf(gs.getPoints()),
                String.valueOf(gs.getRebounds()),
                String.valueOf(gs.getAssists()),
                String.valueOf(gs.getSteals()),
                String.valueOf(gs.getBlocks()),
                String.valueOf(gs.getTurnovers()),
                String.valueOf(gs.getFouls()),
                String.valueOf(gs.getTwoPtMade()),
                String.valueOf(gs.getTwoPtAttempted()),
                String.valueOf(gs.getThreePtMade()),
                String.valueOf(gs.getThreePtAttempted()),
                String.valueOf(gs.getFtMade()),
                String.valueOf(gs.getFtAttempted())
        );
    }

    private void savePlayerStats(java.util.List<BbPlayerGameStats> gameStats, int seasonYear, Long competitionId, BbTeam team) {
        for (BbPlayerGameStats gs : gameStats) {
            playerRepository.findById(gs.getPlayerId()).ifPresentOrElse(player -> {
                BbPlayerStats stats = player.getStats();
                if (stats == null) {
                    stats = new BbPlayerStats();
                    player.setStats(stats);
                }
                stats.setGamesPlayed(stats.getGamesPlayed() + 1);
                stats.setPointsScored(stats.getPointsScored() + gs.getPoints());
                stats.setReboundsTotal(stats.getReboundsTotal() + gs.getRebounds());
                stats.setAssistsTotal(stats.getAssistsTotal() + gs.getAssists());
                stats.setStealsTotal(stats.getStealsTotal() + gs.getSteals());
                stats.setBlocksTotal(stats.getBlocksTotal() + gs.getBlocks());
                stats.setTurnoversTotal(stats.getTurnoversTotal() + gs.getTurnovers());
                stats.setTwoPtMade(stats.getTwoPtMade() + gs.getTwoPtMade());
                stats.setTwoPtAttempted(stats.getTwoPtAttempted() + gs.getTwoPtAttempted());
                stats.setThreePtMade(stats.getThreePtMade() + gs.getThreePtMade());
                stats.setThreePtAttempted(stats.getThreePtAttempted() + gs.getThreePtAttempted());
                stats.setFtMade(stats.getFtMade() + gs.getFtMade());
                stats.setFtAttempted(stats.getFtAttempted() + gs.getFtAttempted());
                playerRepository.save(player);

                // Also save per-season stats
                var sOpt = seasonStatsRepository.findByPlayerIdAndSeasonYearAndCompetitionId(
                        player.getId(), seasonYear, competitionId);
                BbPlayerSeasonStats s = sOpt.orElseGet(() -> BbPlayerSeasonStats.builder()
                        .player(player)
                        .seasonYear(seasonYear)
                        .competitionId(competitionId)
                        .teamId(team.getId())
                        .teamName(team.getName())
                        .build());
                s.setGamesPlayed(s.getGamesPlayed() + 1);
                s.setPointsScored(s.getPointsScored() + gs.getPoints());
                s.setReboundsTotal(s.getReboundsTotal() + gs.getRebounds());
                s.setAssistsTotal(s.getAssistsTotal() + gs.getAssists());
                s.setStealsTotal(s.getStealsTotal() + gs.getSteals());
                s.setBlocksTotal(s.getBlocksTotal() + gs.getBlocks());
                s.setTurnoversTotal(s.getTurnoversTotal() + gs.getTurnovers());
                s.setTwoPtMade(s.getTwoPtMade() + gs.getTwoPtMade());
                s.setTwoPtAttempted(s.getTwoPtAttempted() + gs.getTwoPtAttempted());
                s.setThreePtMade(s.getThreePtMade() + gs.getThreePtMade());
                s.setThreePtAttempted(s.getThreePtAttempted() + gs.getThreePtAttempted());
                s.setFtMade(s.getFtMade() + gs.getFtMade());
                s.setFtAttempted(s.getFtAttempted() + gs.getFtAttempted());
                seasonStatsRepository.save(s);
            }, () -> log.warn("CPlayer {} not found, skipping stats", gs.getPlayerId()));
        }
    }
}
