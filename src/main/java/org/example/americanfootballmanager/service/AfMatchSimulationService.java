package org.example.americanfootballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.americanfootballmanager.engine.AfMatchEngine;
import org.example.americanfootballmanager.engine.AfMatchResult;
import org.example.americanfootballmanager.engine.AfPlayerGameStats;
import org.example.americanfootballmanager.model.*;
import org.example.americanfootballmanager.repository.*;
import org.example.commonmanager.model.CommonCompetition;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AfMatchSimulationService {

    private final AfMatchEngine matchEngine;
    private final AfTeamRepository teamRepository;
    private final AfPlayerRepository playerRepository;
    private final AfMatchRepository matchRepository;
    private final AfMatchFixtureRepository matchFixtureRepository;
    private final AfPlayerSeasonStatsRepository seasonStatsRepository;

    public AfMatchSimulationService(AfMatchEngine matchEngine,
                                     AfTeamRepository teamRepository,
                                     AfPlayerRepository playerRepository,
                                     AfMatchRepository matchRepository,
                                     AfMatchFixtureRepository matchFixtureRepository,
                                     AfPlayerSeasonStatsRepository seasonStatsRepository) {
        this.matchEngine = matchEngine;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.seasonStatsRepository = seasonStatsRepository;
    }

    @Transactional
    public AfMatchResult simulateAndSave(Long homeTeamId, Long awayTeamId) {
        AfTeam home = teamRepository.findById(homeTeamId)
                .orElseThrow(() -> new RuntimeException("Home team not found: " + homeTeamId));
        AfTeam away = teamRepository.findById(awayTeamId)
                .orElseThrow(() -> new RuntimeException("Away team not found: " + awayTeamId));
        return simulate(home, away, null);
    }

    @Transactional
    public AfMatchResult simulateFixture(Long fixtureId) {
        AfMatchFixture fixture = matchFixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new RuntimeException("Fixture not found: " + fixtureId));
        AfMatchResult result = simulate(fixture.getHomeTeam(), fixture.getAwayTeam(), fixture);

        fixture.setPlayed(true);
        matchFixtureRepository.save(fixture);

        return result;
    }

    private AfMatchResult simulate(AfTeam home, AfTeam away, AfMatchFixture fixture) {
        AfMatchResult result = matchEngine.simulate(home, away);

        CommonCompetition competition = home.getCompetition();
        int seasonYear = fixture != null ? fixture.getSeasonYear() : 2025;
        Long competitionId = competition != null ? competition.getId() : null;

        String eventsJson = result.getEvents() != null
                ? result.getEvents().stream().collect(Collectors.joining("||"))
                : "";
        String homeStatsJson = result.getHomePlayerStats() != null
                ? result.getHomePlayerStats().stream().map(this::playerStatsToString).collect(Collectors.joining(";"))
                : "";
        String awayStatsJson = result.getAwayPlayerStats() != null
                ? result.getAwayPlayerStats().stream().map(this::playerStatsToString).collect(Collectors.joining(";"))
                : "";

        AfMatch match = AfMatch.builder()
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
        }
        matchRepository.save(match);

        savePlayerStats(result.getHomePlayerStats(), seasonYear, competitionId, home);
        savePlayerStats(result.getAwayPlayerStats(), seasonYear, competitionId, away);

        return result;
    }

    private String playerStatsToString(AfPlayerGameStats gs) {
        return String.join(",",
                String.valueOf(gs.getPlayerId()),
                gs.getPlayerName(),
                gs.getPosition(),
                String.valueOf(gs.getMinutes()),
                String.valueOf(gs.getTouchdowns()),
                String.valueOf(gs.getFieldGoalsMade()),
                String.valueOf(gs.getFieldGoalsAttempted()),
                String.valueOf(gs.getTackles()),
                String.valueOf(gs.getInterceptions()),
                String.valueOf(gs.getSacks()),
                String.valueOf(gs.getPassingYards()),
                String.valueOf(gs.getRushingYards()),
                String.valueOf(gs.getReceivingYards()),
                String.valueOf(gs.getPassingTouchdowns()),
                String.valueOf(gs.getRushingTouchdowns()),
                String.valueOf(gs.getReceivingTouchdowns()),
                String.valueOf(gs.getTwoPointConversions()),
                String.valueOf(gs.getFumbles())
        );
    }

    private void savePlayerStats(java.util.List<AfPlayerGameStats> gameStats, int seasonYear, Long competitionId, AfTeam team) {
        for (AfPlayerGameStats gs : gameStats) {
            playerRepository.findById(gs.getPlayerId()).ifPresentOrElse(player -> {
                AfPlayerStats stats = player.getStats();
                if (stats == null) {
                    stats = new AfPlayerStats();
                    player.setStats(stats);
                }
                stats.setGamesPlayed(stats.getGamesPlayed() + 1);
                stats.setTouchdowns(stats.getTouchdowns() + gs.getTouchdowns());
                stats.setFieldGoalsMade(stats.getFieldGoalsMade() + gs.getFieldGoalsMade());
                stats.setFieldGoalsAttempted(stats.getFieldGoalsAttempted() + gs.getFieldGoalsAttempted());
                stats.setTackles(stats.getTackles() + gs.getTackles());
                stats.setInterceptions(stats.getInterceptions() + gs.getInterceptions());
                stats.setSacks(stats.getSacks() + gs.getSacks());
                stats.setPassingYards(stats.getPassingYards() + gs.getPassingYards());
                stats.setRushingYards(stats.getRushingYards() + gs.getRushingYards());
                stats.setReceivingYards(stats.getReceivingYards() + gs.getReceivingYards());
                stats.setPassingTouchdowns(stats.getPassingTouchdowns() + gs.getPassingTouchdowns());
                stats.setRushingTouchdowns(stats.getRushingTouchdowns() + gs.getRushingTouchdowns());
                stats.setReceivingTouchdowns(stats.getReceivingTouchdowns() + gs.getReceivingTouchdowns());
                stats.setTwoPointConversions(stats.getTwoPointConversions() + gs.getTwoPointConversions());
                stats.setFumbles(stats.getFumbles() + gs.getFumbles());
                playerRepository.save(player);

                var sOpt = seasonStatsRepository.findByPlayerIdAndSeasonYearAndCompetitionId(
                        player.getId(), seasonYear, competitionId);
                AfPlayerSeasonStats s = sOpt.orElseGet(() -> AfPlayerSeasonStats.builder()
                        .player(player)
                        .seasonYear(seasonYear)
                        .competitionId(competitionId)
                        .teamId(team.getId())
                        .teamName(team.getName())
                        .build());
                s.setGamesPlayed(s.getGamesPlayed() + 1);
                s.setTouchdowns(s.getTouchdowns() + gs.getTouchdowns());
                s.setFieldGoalsMade(s.getFieldGoalsMade() + gs.getFieldGoalsMade());
                s.setFieldGoalsAttempted(s.getFieldGoalsAttempted() + gs.getFieldGoalsAttempted());
                s.setTackles(s.getTackles() + gs.getTackles());
                s.setInterceptions(s.getInterceptions() + gs.getInterceptions());
                s.setSacks(s.getSacks() + gs.getSacks());
                s.setPassingYards(s.getPassingYards() + gs.getPassingYards());
                s.setRushingYards(s.getRushingYards() + gs.getRushingYards());
                s.setReceivingYards(s.getReceivingYards() + gs.getReceivingYards());
                s.setPassingTouchdowns(s.getPassingTouchdowns() + gs.getPassingTouchdowns());
                s.setRushingTouchdowns(s.getRushingTouchdowns() + gs.getRushingTouchdowns());
                s.setReceivingTouchdowns(s.getReceivingTouchdowns() + gs.getReceivingTouchdowns());
                s.setTwoPointConversions(s.getTwoPointConversions() + gs.getTwoPointConversions());
                s.setFumbles(s.getFumbles() + gs.getFumbles());
                seasonStatsRepository.save(s);
            }, () -> log.warn("CPlayer {} not found, skipping stats", gs.getPlayerId()));
        }
    }
}
