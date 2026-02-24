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
    public RuntimeSaveToDB(PlayerRepository playerRepository, MatchStatisticEngine matchStatisticEngineHandling, MatchRepository matchRepository, CompetitionRepository competitionRepository, SeasonRepository seasonRepository, SeasonCompetitionRepository seasonCompetitionRepository, CompetitionEntryRepository competitionEntryRepository) {
        this.playerRepository = playerRepository;
        this.matchStatisticEngineHandling = matchStatisticEngineHandling;
        this.matchRepository = matchRepository;
        this.competitionRepository = competitionRepository;
        this.seasonRepository = seasonRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
    }

    private void batchSaveMatchEvents(Match match, List<MatchEvent> events, List<Player> homePlayers, List<Player> awayPlayers) {
        int batchSize = 50; // PostgreSQL safe
        int i = 0;

        for (MatchEvent e : events) {
            e.setMatch(match);
            if(e instanceof GoalEvent g)
            {
                    // --- ažuriraj scorer ---
                    if (g.getScorer() != null) {
                        Player scorer = g.getScorer();
                        scorer.setTotalGoals(scorer.getTotalGoals() + 1);
                        playerRepository.save(scorer);
/*                        // update u runtime listi
                        homePlayers.stream()
                                .filter(p -> p.getId().equals(scorer.getId()))
                                .forEach(p -> p.setTotalGoals(scorer.getTotalGoals()));
                        awayPlayers.stream()
                                .filter(p -> p.getId().equals(scorer.getId()))
                                .forEach(p -> p.setTotalGoals(scorer.getTotalGoals()));*/
                    }
                    // --- ažuriraj assistant ---
                    if (g.getAssistant() != null) {
                        Player assistant = g.getAssistant();
                        assistant.setTotalAssists(assistant.getTotalAssists() + 1);
                        playerRepository.save(assistant);
/*                        // update u runtime listi
                        homePlayers.stream()
                                .filter(p -> p.getId().equals(assistant.getId()))
                                .forEach(p -> p.setTotalAssists(assistant.getTotalAssists()));

                        awayPlayers.stream()
                                .filter(p -> p.getId().equals(assistant.getId()))
                                .forEach(p -> p.setTotalAssists(assistant.getTotalAssists()));*/
                    }
            }
            em.merge(e);         // detached entities + merge

            if (++i % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }

        em.flush();
        em.clear();

    }
    private void batchSaveGoalEvents(Match match, List<GoalEvent> goals, List<Player> homePlayers, List<Player> awayPlayers) {
        int batchSize = 50; // PostgreSQL safe
        int i = 0;
        for (GoalEvent g : goals) {
            g.setMatch(match);

            // --- ažuriraj scorer ---
            if (g.getScorer() != null) {
                Player scorer = g.getScorer();
                scorer.setTotalGoals(scorer.getTotalGoals() + 1);
                playerRepository.save(scorer);
                // update u runtime listi
                homePlayers.stream()
                        .filter(p -> p.getId().equals(scorer.getId()))
                        .forEach(p -> p.setTotalGoals(scorer.getTotalGoals()));
                awayPlayers.stream()
                        .filter(p -> p.getId().equals(scorer.getId()))
                        .forEach(p -> p.setTotalGoals(scorer.getTotalGoals()));
            }
            // --- ažuriraj assistant ---
            if (g.getAssistant() != null) {
                Player assistant = g.getAssistant();
                assistant.setTotalAssists(assistant.getTotalAssists() + 1);
                playerRepository.save(assistant);
                // update u runtime listi
                homePlayers.stream()
                        .filter(p -> p.getId().equals(assistant.getId()))
                        .forEach(p -> p.setTotalAssists(assistant.getTotalAssists()));

                awayPlayers.stream()
                        .filter(p -> p.getId().equals(assistant.getId()))
                        .forEach(p -> p.setTotalAssists(assistant.getTotalAssists()));
            }
            em.persist(g);
            if (++i % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();
        em.clear();
    }
    public Match finalizeMatchResult(Match match, List<Player> homePlayers, List<Player> awayPlayers, MatchRuntime rt) {
        rt.homeTeam = match.getHomeTeam();
        rt.awayTeam = match.getAwayTeam();

        // --- batch merge svih ostalih eventa (kartoni, povrede, itd.) ---
        batchSaveMatchEvents(match, rt.runtimeEvents, homePlayers, awayPlayers);
        // --- batch save golova i update igrača ---
        //batchSaveGoalEvents(match, rt.runtimeGoals, homePlayers, awayPlayers);
        // --- update meča ---
        match.setHomeGoals(rt.homeGoals);
        match.setAwayGoals(rt.awayGoals);
        matchRepository.save(match); // 🔹 sigurni save

        // --- simulacija kartona/povreda ---
        matchStatisticEngineHandling.simulateInjuriesAndCards(homePlayers, match);
        matchStatisticEngineHandling.simulateInjuriesAndCards(awayPlayers, match);

        // --- ocene igrača i stats ---
        homePlayers = matchStatisticEngineHandling.assignRatings(homePlayers, rt.runtimeGoals); // koristimo runtime, ne bazu
        awayPlayers = matchStatisticEngineHandling.assignRatings(awayPlayers, rt.runtimeGoals);

        matchStatisticEngineHandling.savePlayerStats(match, homePlayers, rt.runtimeGoals, rt.runtimeEvents.stream()
                        .filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream()
                        .filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        matchStatisticEngineHandling.savePlayerStats(match, awayPlayers, rt.runtimeGoals, rt.runtimeEvents.stream()
                        .filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream()
                        .filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        // NOVO: snimi tick pozicije u bazu (samo jednom, na kraju)
        batchSaveTickPositions(match, rt);
        // --- za report odmah koristimo runtimeGoalove + runtimeEvente ---
        System.out.println(matchStatisticEngineHandling.generateMatchReport(match, rt, homePlayers, awayPlayers));
        updateLeagueTable(match, rt);
        return match;
    }
    // Na kraju finalizeMatchResult
    private void updateLeagueTable(Match match, MatchRuntime rt) {

        // Pretpostavimo da su timovi u Superligi (id=1) – kasnije možeš naći pravu ligu
        Competition superLiga = competitionRepository.findByNameAndCountryIsoCode("Superliga Srbije", "SRB").orElse(null);
        if (superLiga == null) return;

        Season currentSeason = seasonRepository.findBySeasonYear(2025).orElse(null);
        if (currentSeason == null) return;

        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, 2025).orElse(null);
        if (sc == null) return;

        // Ažuriraj home tim
        CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, match.getHomeTeam()).orElse(null);
        if (homeEntry != null) {
            int points = 0;
            if (rt.homeGoals > rt.awayGoals) {points = 3;}
            else if (rt.awayGoals == rt.homeGoals) {points = 1;}
            homeEntry.setPoints(homeEntry.getPoints() + points); // npr. 3 za pobedu, 1 za remi
            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + rt.homeGoals);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + rt.awayGoals);
            competitionEntryRepository.save(homeEntry);
        }

        // Ažuriraj away tim (isto)
        CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, match.getAwayTeam()).orElse(null);
        if (awayEntry != null) {
            int points = 0;
            if (rt.homeGoals < rt.awayGoals) {points = 3;}
            else if (rt.awayGoals == rt.homeGoals) {points = 1;}
            awayEntry.setPoints(awayEntry.getPoints() + points);
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + rt.awayGoals);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + rt.homeGoals);
            competitionEntryRepository.save(awayEntry);
        }
    }
    private void batchSaveTickPositions(Match match, MatchRuntime rt) {
        int batchSize = 50;
        int i = 0;

        for (MatchRuntime.TickPositionSnapshot snapshot : rt.positionHistory) {
            MatchTickState state = new MatchTickState();
            state.setMatch(match);
            state.setTick(snapshot.tick);
            state.setMinute(snapshot.tick / 10);

            try {
                state.setPlayerPositionsJson(objectMapper.writeValueAsString(snapshot.players));
                state.setBallPositionJson(objectMapper.writeValueAsString(rt.ballHistory.get(i))); // sinhronizuj sa indeksom
                state.setCurrentCarrierId(rt.currentCarrier != null ? rt.currentCarrier.getId() : null);

                em.persist(state);

                if (++i % batchSize == 0) {
                    em.flush();
                    em.clear();
                }
            } catch (JsonProcessingException e) {
                log.error("Greška pri serijalizaciji tick stanja za meč {} tick {}", match.getId(), snapshot.tick, e);
            }
        }

        em.flush();
        em.clear();
        log.info("Snimljeno {} tick stanja za meč {}", rt.positionHistory.size(), match.getId());
    }
}
