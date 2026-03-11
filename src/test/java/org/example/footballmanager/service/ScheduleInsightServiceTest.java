package org.example.footballmanager.service;

import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleInsightServiceTest {

    @Mock private PlayerRepository playerRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private LineupRepository lineupRepository;

    @InjectMocks private ScheduleInsightService scheduleInsightService;

    @Test
    void buildFixtureInsightsFavoursStrongerHomeTeamButKeepsBalancedDrawShare() {
        Team home = new Team();
        home.setId(1L);
        home.setName("OFK Omladinac");

        Team away = new Team();
        away.setId(2L);
        away.setName("FK Rival");

        List<Player> homePlayers = createPlayers(home, 82, 7.5);
        List<Player> awayPlayers = createPlayers(away, 70, 6.3);
        when(playerRepository.findByTeamId(1L)).thenReturn(homePlayers);
        when(playerRepository.findByTeamId(2L)).thenReturn(awayPlayers);
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.of(createLineup(home, homePlayers)));
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(2L)).thenReturn(Optional.of(createLineup(away, awayPlayers)));
        when(matchRepository.findByHomeTeamIdOrAwayTeamId(1L, 1L)).thenReturn(List.of(
                match(home, away, 2, 0, true, LocalDateTime.of(2026, 2, 1, 15, 0)),
                match(away, home, 1, 1, true, LocalDateTime.of(2026, 1, 25, 15, 0)),
                match(home, away, 3, 1, true, LocalDateTime.of(2026, 1, 18, 15, 0))
        ));
        when(matchRepository.findByHomeTeamIdOrAwayTeamId(2L, 2L)).thenReturn(List.of(
                match(away, home, 0, 1, true, LocalDateTime.of(2026, 2, 2, 15, 0)),
                match(away, home, 1, 1, true, LocalDateTime.of(2026, 1, 26, 15, 0)),
                match(home, away, 2, 0, true, LocalDateTime.of(2026, 1, 19, 15, 0))
        ));

        Map<Long, ScheduleInsightService.TeamSnapshot> snapshots = scheduleInsightService.buildTeamSnapshots(List.of(home, away));
        ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(home, away, snapshots);

        assertTrue(insights.homeTeamStrength() > insights.awayTeamStrength());
        assertTrue(insights.homeTeamForm() > insights.awayTeamForm());
        assertEquals(100,
                insights.prediction().homeWinProbability()
                        + insights.prediction().drawProbability()
                        + insights.prediction().awayWinProbability());
        assertTrue(insights.prediction().homeWinProbability() > insights.prediction().awayWinProbability());
        assertTrue(insights.prediction().expectedHomeGoals() > insights.prediction().expectedAwayGoals());
        assertEquals("HOME_WIN", insights.prediction().mostLikelyResult());
        assertTrue(insights.prediction().confidence() >= 48 && insights.prediction().confidence() <= 87);
    }

    @Test
    void buildFixtureInsightsKeepsAwayLeanConsistentWithExpectedGoals() {
        Team home = new Team();
        home.setId(1L);
        home.setName("NK Kraljevo Sport");

        Team away = new Team();
        away.setId(2L);
        away.setName("TSK Beograd");

        ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(
                home,
                away,
                Map.of(
                        1L, new ScheduleInsightService.TeamSnapshot(64, 8.2, 5),
                        2L, new ScheduleInsightService.TeamSnapshot(69, 8.5, 5)
                )
        );

        assertEquals("AWAY_WIN", insights.prediction().mostLikelyResult());
        assertTrue(insights.prediction().expectedAwayGoals() > insights.prediction().expectedHomeGoals());
    }

    private List<Player> createPlayers(Team team, int baseRating, double baseForm) {
        return java.util.stream.IntStream.range(0, 14)
                .mapToObj(index -> {
                    Player player = new Player();
                    player.setId(team.getId() * 100 + index);
                    player.setTeam(team);
                    player.setName(team.getName() + " P" + index);
                    player.setRating(baseRating - index);
                    player.setForm(Math.max(4.5, baseForm - index * 0.08));
                    player.setInjured(false);
                    return player;
                })
                .toList();
    }

    private Lineup createLineup(Team team, List<Player> players) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setStartingPlayers(players.subList(0, 11));
        lineup.setStarterOrderFromIds(players.subList(0, 11).stream().map(Player::getId).toList());
        return lineup;
    }

    private Match match(Team home, Team away, int homeGoals, int awayGoals, boolean played, LocalDateTime dateTime) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setPlayed(played);
        match.setMatchDate(dateTime);
        return match;
    }
}