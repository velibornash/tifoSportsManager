package org.example.footballmanager.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.RedCardEvent;
import org.example.footballmanager.model.event.YellowCardEvent;
import org.example.footballmanager.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class RuntimeSaveToDB {
    private final PlayerRepository playerRepository;
    private final MatchStatisticEngine matchStatisticEngineHandling;
    private final MatchRepository matchRepository;
    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private MatchTickStateRepository tickRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public RuntimeSaveToDB(
            PlayerRepository playerRepository,
            MatchStatisticEngine matchStatisticEngineHandling,
            MatchRepository matchRepository,
            CompetitionRepository competitionRepository,
            SeasonRepository seasonRepository,
            SeasonCompetitionRepository seasonCompetitionRepository,
            CompetitionEntryRepository competitionEntryRepository
    ) {
        this.playerRepository = playerRepository;
        this.matchStatisticEngineHandling = matchStatisticEngineHandling;
        this.matchRepository = matchRepository;
        this.competitionRepository = competitionRepository;
        this.seasonRepository = seasonRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
    }

    private void batchSaveMatchEvents(Match match, List<MatchEvent> events, List<Player> homePlayers, List<Player> awayPlayers) {
        for (MatchEvent event : events) {
            event.setMatch(match);
            if (event instanceof GoalEvent goal) {
                if (!goal.isScored()) {
                    em.persist(event);
                    continue;
                }
                if (goal.getScorer() != null) {
                    Player scorer = goal.getScorer();
                    scorer.setTotalGoals(scorer.getTotalGoals() + 1);
                    playerRepository.save(scorer);
                }
                if (goal.getAssistant() != null) {
                    Player assistant = goal.getAssistant();
                    assistant.setTotalAssists(assistant.getTotalAssists() + 1);
                    playerRepository.save(assistant);
                }
            }
            em.persist(event);
        }

        em.flush();
    }

    public Match finalizeMatchResult(Match match, List<Player> homePlayers, List<Player> awayPlayers, MatchRuntime rt) {
        rt.homeTeam = match.getHomeTeam();
        rt.awayTeam = match.getAwayTeam();

        batchSaveMatchEvents(match, rt.runtimeEvents, homePlayers, awayPlayers);

        match.setHomeGoals(rt.homeGoals);
        match.setAwayGoals(rt.awayGoals);
        match.setPlayed(true);
        match.setStarted(true);
        matchRepository.save(match);

        matchStatisticEngineHandling.simulateInjuriesAndCards(homePlayers, match);
        matchStatisticEngineHandling.simulateInjuriesAndCards(awayPlayers, match);

        homePlayers = matchStatisticEngineHandling.assignRatings(homePlayers, rt.runtimeGoals);
        awayPlayers = matchStatisticEngineHandling.assignRatings(awayPlayers, rt.runtimeGoals);

        matchStatisticEngineHandling.savePlayerStats(
                match,
                homePlayers,
                rt.runtimeGoals,
                rt.runtimeEvents.stream().filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream().filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        matchStatisticEngineHandling.savePlayerStats(
                match,
                awayPlayers,
                rt.runtimeGoals,
                rt.runtimeEvents.stream().filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream().filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        batchSaveTickPositions(match, rt);

        System.out.println(matchStatisticEngineHandling.generateMatchReport(match, rt, homePlayers, awayPlayers));
        updateLeagueTable(match, rt);
        return match;
    }

    private void updateLeagueTable(Match match, MatchRuntime rt) {
        Competition league = match.getCompetition();
        if (league == null) {
            league = competitionRepository.findByNameAndCountryIsoCode("Superliga Srbije", "SRB").orElse(null);
        }
        if (league == null) return;

        Integer seasonYear = match.getSeasonYear();
        if (seasonYear == null) {
            seasonYear = 2025;
        }
        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, seasonYear).orElse(null);
        if (sc == null) return;

        CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, match.getHomeTeam()).orElse(null);
        if (homeEntry != null) {
            int points = 0;
            if (rt.homeGoals > rt.awayGoals) points = 3;
            else if (rt.awayGoals == rt.homeGoals) points = 1;
            homeEntry.setPoints(homeEntry.getPoints() + points);
            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + rt.homeGoals);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + rt.awayGoals);
            competitionEntryRepository.save(homeEntry);
        }

        CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, match.getAwayTeam()).orElse(null);
        if (awayEntry != null) {
            int points = 0;
            if (rt.homeGoals < rt.awayGoals) points = 3;
            else if (rt.awayGoals == rt.homeGoals) points = 1;
            awayEntry.setPoints(awayEntry.getPoints() + points);
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + rt.awayGoals);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + rt.homeGoals);
            competitionEntryRepository.save(awayEntry);
        }
    }

    private void batchSaveTickPositions(Match match, MatchRuntime rt) {
        int batchSize = 50;
        int i = 0;

        tickRepository.deleteByMatch(match);

        for (MatchRuntime.TickState snapshot : rt.tickStates) {
            MatchTickState state = new MatchTickState();
            state.setMatch(match);
            state.setTick(snapshot.tick);
            int ticksPerMinute = rt.ticksPerMinute > 0 ? rt.ticksPerMinute : 27;
            state.setMinute(snapshot.tick / ticksPerMinute);

            try {
                state.setPlayerPositionsJson(objectMapper.writeValueAsString(snapshot.players));
                state.setBallPositionJson(objectMapper.writeValueAsString(snapshot.ball));
                state.setCurrentCarrierId(snapshot.carrierId >= 0 ? snapshot.carrierId : null);

                em.persist(state);

                if (++i % batchSize == 0) {
                    em.flush();
                    em.clear();
                }
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize tick state for match {} tick {}", match.getId(), snapshot.tick, e);
            }
        }

        em.flush();
        em.clear();
        log.info("Saved {} tick states for match {}", rt.tickStates.size(), match.getId());
    }
}
