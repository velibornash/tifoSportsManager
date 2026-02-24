package org.example.footballmanager.util.old;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.InjuryEvent;
import org.example.footballmanager.model.event.RedCardEvent;
import org.example.footballmanager.model.event.YellowCardEvent;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.old.oldService.DemoMatchRuntime;
import org.example.footballmanager.util.match.MatchRatingCalculator;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Slf4j
@Component
public class MatchStatisticHandling {
    private final Random random = new Random();
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    public MatchStatisticHandling(MatchPlayerStatsRepository matchPlayerStatsRepository) {
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
    }
    public List<Player> assignRatings(List<Player> players, List<GoalEvent> allGoals) {
        for (Player player : players) {
            player.setRating(MatchRatingCalculator.calculate(player, player.getTeam(), allGoals));
        }
        return players;
    }
    public void simulateInjuriesAndCards(List<Player> players, Match match) {
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
    public void savePlayerStats(Match match, List<Player> players, List<GoalEvent> allGoals, List<YellowCardEvent> allYellows, List<RedCardEvent> allReds) {
        for (Player player : players) {
            long goals = allGoals.stream()
                    .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                    .count();

            long assists = allGoals.stream().filter(g -> g.getAssistant() != null && g.getAssistant().equals(player)).count();
            long yellowCards = allYellows.stream().filter(y -> y.getPlayer() != null && y.getPlayer().equals(player)).count();
            long redCards = allReds.stream().filter(r -> r.getPlayer() != null && r.getPlayer().equals(player)).count();

            MatchPlayerStats stats = new MatchPlayerStats();
            stats.setMatch(match);
            stats.setPlayer(player);
            stats.setGoals((int) goals);
            stats.setAssists((int) assists);
            stats.setYellowCards((int) yellowCards);
            stats.setRedCards((int) redCards);
            stats.setMinutesPlayed(90);
            stats.setRating(player.getRating());
            matchPlayerStatsRepository.save(stats);
        }
    }
    public String generateMatchReport(Match match, DemoMatchRuntime rt, List<Player> homePlayers, List<Player> awayPlayers) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s %d - %d %s%n%n",
                match.getHomeTeam().getName(),
                rt.homeGoals,
                rt.awayGoals,
                match.getAwayTeam().getName()));
        // --- Strelci ---
        sb.append("Strelci:\n");
        // koristimo HashSet da ne dupliramo iste golove (minute+scorer)
        Set<String> addedGoals = new HashSet<>();
        rt.runtimeGoals.forEach(g -> {
            String scorerName = g.getScorer() != null ? g.getScorer().getName() : "N/A";
            String assistName = g.getAssistant() != null ? g.getAssistant().getName() : null;
            // oblik: 45' ⚽ Igrač (asistencija: Igrač)
            String desc;
            if (assistName != null) {
                desc = String.format("%d' ⚽ %s (asistencija: %s)", g.getMinute(), scorerName, assistName);
            } else {
                desc = String.format("%d' ⚽ %s", g.getMinute(), scorerName);
            }
            if (!addedGoals.contains(desc)) {
                sb.append(desc).append("\n");
                addedGoals.add(desc);
            }
        });
        sb.append("\n");
        // --- Ocene igrača ---
        sb.append("Ocene igrača - ").append(match.getHomeTeam().getName()).append("\n");
        appendPlayerRatings(sb, homePlayers, rt.runtimeGoals);
        sb.append("\nOcene igrača - ").append(match.getAwayTeam().getName()).append("\n");
        appendPlayerRatings(sb, awayPlayers, rt.runtimeGoals);
        return sb.toString();
    }
    public void appendPlayerRatings(StringBuilder sb, List<Player> players, List<GoalEvent> allGoals) {
        for (Player player : players) {
            long goals = allGoals.stream()
                    .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                    .count();

            long assists = allGoals.stream()
                    .filter(g -> g.getAssistant() != null && g.getAssistant().equals(player))
                    .count();

            sb.append(String.format("- %s: %d (golova: %d, asistencija: %d)%n",
                    player.getName(),
                    player.getRating(),
                    goals,
                    assists));
        }
    }
}