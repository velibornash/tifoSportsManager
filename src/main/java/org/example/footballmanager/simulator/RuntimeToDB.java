package org.example.footballmanager.simulator;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.RedCardEvent;
import org.example.footballmanager.model.event.YellowCardEvent;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.service.DemoMatchRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class RuntimeToDB {
    private final PlayerRepository playerRepository;
    private final MatchStatisticHandling  matchStatisticHandling;
    private final MatchRepository matchRepository;

    @Autowired
    private EntityManager em;

    public RuntimeToDB(PlayerRepository playerRepository, MatchStatisticHandling matchStatisticHandling, MatchRepository matchRepository) {
        this.playerRepository = playerRepository;
        this.matchStatisticHandling = matchStatisticHandling;
        this.matchRepository = matchRepository;
    }

    private void batchSaveMatchEvents(Match match, List<MatchEvent> events) {
        int batchSize = 50; // PostgreSQL safe
        int i = 0;

        for (MatchEvent e : events) {
            e.setMatch(match);   // samo poveži
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
    public Match finalizeMatchResult(Match match, List<Player> homePlayers, List<Player> awayPlayers, DemoMatchRuntime rt) {
        rt.homeTeam = match.getHomeTeam();
        rt.awayTeam = match.getAwayTeam();

        // --- batch merge svih ostalih eventa (kartoni, povrede, itd.) ---
        batchSaveMatchEvents(match, rt.runtimeEvents);
        // --- batch save golova i update igrača ---
        batchSaveGoalEvents(match, rt.runtimeGoals, homePlayers, awayPlayers);
        // --- update meča ---
        match.setHomeGoals(rt.homeGoals);
        match.setAwayGoals(rt.awayGoals);
        matchRepository.save(match); // 🔹 sigurni save

        // --- simulacija kartona/povreda ---
        matchStatisticHandling.simulateInjuriesAndCards(homePlayers, match);
        matchStatisticHandling.simulateInjuriesAndCards(awayPlayers, match);

        // --- ocene igrača i stats ---
        homePlayers = matchStatisticHandling.assignRatings(homePlayers, rt.runtimeGoals); // koristimo runtime, ne bazu
        awayPlayers = matchStatisticHandling.assignRatings(awayPlayers, rt.runtimeGoals);

        matchStatisticHandling.savePlayerStats(match, homePlayers, rt.runtimeGoals, rt.runtimeEvents.stream()
                        .filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream()
                        .filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        matchStatisticHandling.savePlayerStats(match, awayPlayers, rt.runtimeGoals, rt.runtimeEvents.stream()
                        .filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream()
                        .filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        // --- za report odmah koristimo runtimeGoalove + runtimeEvente ---
        System.out.println(matchStatisticHandling.generateMatchReport(match, rt, homePlayers, awayPlayers));

        return match;
    }
}
