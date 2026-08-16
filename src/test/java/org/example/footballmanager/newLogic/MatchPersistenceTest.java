package org.example.footballmanager.newLogic;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.service.MatchPersistenceService;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MatchPersistenceTest extends BaseTest {

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchPersistenceService persistenceService;

    @Autowired
    private MatchRepository matchRepo;

    @Autowired
    private MatchPlayerStatsRepository statsRepo;

    @Test
    @Transactional
    void testMatchPersistsToDatabase() {
        System.out.println("\n=== TESTING DB PERSISTENCE ===");

        List<Team> teams = teamRepository.findAll();
        System.out.println("Total teams in DB: " + teams.size());

        if (teams.size() < 2) {
            System.out.println("Not enough teams in DB - skipping test");
            return;
        }

        Team homeTeam = teams.get(0);
        Team awayTeam = teams.get(1);
        System.out.println("Using teams: " + homeTeam.getName() + " vs " + awayTeam.getName());

        List<Player> homePlayers = playerRepository.findByTeamId(homeTeam.getId());
        List<Player> awayPlayers = playerRepository.findByTeamId(awayTeam.getId());
        System.out.println("Home players: " + homePlayers.size() + ", Away players: " + awayPlayers.size());

        if (homePlayers.isEmpty() || awayPlayers.isEmpty()) {
            System.out.println("Not enough players - skipping test");
            return;
        }

        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(
            store, teamRepository, playerRepository, null, persistenceService);

        long matchId = orchestrator.startMatch(homeTeam.getName(), awayTeam.getName());
        System.out.println("Started match ID: " + matchId);

        MatchResult result = orchestrator.simulate(matchId);
        System.out.println("Simulated result: " + result.homeGoals() + " - " + result.awayGoals());
        System.out.println("Total events: " + result.events().size());

        System.out.println("\n--- Checking DB ---");

        var matchOpt = matchRepo.findById(matchId);
        if (matchOpt.isEmpty()) {
            System.out.println("WARNING: Match not found in DB by ID " + matchId);
            var allMatches = matchRepo.findAll();
            System.out.println("Total matches in DB: " + allMatches.size());
            if (!allMatches.isEmpty()) {
                var lastMatch = allMatches.get(allMatches.size() - 1);
                System.out.println("Last match in DB: ID=" + lastMatch.getId() +
                    ", " + lastMatch.getHomeTeam().getName() + " " + lastMatch.getHomeGoals() + "-" + lastMatch.getAwayGoals() + " " + lastMatch.getAwayTeam().getName());
            }
        } else {
            Match match = matchOpt.get();
            System.out.println("Match found: " + match.getHomeTeam().getName() + " " +
                match.getHomeGoals() + "-" + match.getAwayGoals() + " " + match.getAwayTeam().getName());
            System.out.println("  Played: " + match.isPlayed() + ", Finished: " + match.isFinished());
            System.out.println("  Event JSON length: " + (match.getEventJson() != null ? match.getEventJson().length() : 0));
        }

        var playerStats = statsRepo.findByMatchId(matchId);
        System.out.println("Player stats records: " + playerStats.size());
        if (!playerStats.isEmpty()) {
            playerStats.stream().limit(5).forEach(s ->
                System.out.printf("  %s | Pos: %s | G:%d A:%d | YC:%d RC:%d | Min:%d | Rtg:%.1f%n",
                    s.getPlayer().getName(),
                    s.getPlayer().getPosition(),
                    s.getGoals(), s.getAssists(),
                    s.getYellowCards(), s.getRedCards(),
                    s.getMinutesPlayed(), s.getRating()));
        }

        System.out.println("\n=== PERSISTENCE TEST COMPLETE ===");
    }
}
