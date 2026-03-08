package org.example.footballmanager.service;

import org.example.footballmanager.dto.LeagueMilestonesDTO;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Stadium;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.repository.GoalEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeagueMilestoneServiceTest {

    @Mock private MatchRepository matchRepository;
    @Mock private GoalEventRepository goalEventRepository;

    @InjectMocks private LeagueMilestoneService leagueMilestoneService;

    @Test
    void buildsSeasonMilestoneBoardFromPlayedMatchesAndGoals() {
        Competition league = new Competition();
        league.setId(1L);
        league.setName("Superliga Srbije");

        Team omladinac = team(1L, "Omladinac", 68.0, 6200);
        Team radnicki = team(2L, "Radnicki", 59.0, 5400);
        Team spartak = team(3L, "Spartak", 52.0, 4800);

        Player marko = player(11L, "Marko", omladinac);
        Player nikola = player(12L, "Nikola", omladinac);
        Player petar = player(21L, "Petar", radnicki);

        Match bigWin = match(101L, league, omladinac, spartak, 4, 0, 2026, 6, 6080);
        Match heavyLoss = match(102L, league, radnicki, omladinac, 0, 3, 2026, 11, 5250);
        Match tightGame = match(103L, league, spartak, radnicki, 1, 0, 2026, 18, 3880);

        GoalEvent g1 = goal(bigWin, omladinac, marko, nikola);
        GoalEvent g2 = goal(bigWin, omladinac, marko, nikola);
        GoalEvent g3 = goal(bigWin, omladinac, nikola, marko);
        GoalEvent g4 = goal(heavyLoss, omladinac, marko, nikola);
        GoalEvent g5 = goal(heavyLoss, omladinac, petar, null);

        when(matchRepository.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(1L, 2026))
                .thenReturn(List.of(bigWin, heavyLoss, tightGame));
        when(goalEventRepository.findAll()).thenReturn(List.of(g1, g2, g3, g4, g5));

        LeagueMilestonesDTO dto = leagueMilestoneService.buildLeagueMilestones(league, 2026);

        assertNotNull(dto);
        assertEquals(2026, dto.getSeasonYear());
        assertEquals("Marko", dto.getTopScorer().getPlayerName());
        assertEquals(3, dto.getTopScorer().getValue());
        assertEquals("Nikola", dto.getTopAssist().getPlayerName());
        assertEquals(3, dto.getTopAssist().getValue());
        assertEquals("Omladinac", dto.getBiggestWin().getTeamName());
        assertEquals("4-0 vs Spartak", dto.getBiggestWin().getSummary());
        assertEquals(4, dto.getBiggestWin().getGoalMargin());
        assertEquals("Radnicki", dto.getBiggestLoss().getTeamName());
        assertEquals("0-3 vs Omladinac", dto.getBiggestLoss().getSummary());
        assertEquals(3, dto.getBiggestLoss().getGoalMargin());
        assertEquals(5070, dto.getAttendance().getAverageAttendance());
        assertEquals(6080, dto.getAttendance().getHighestAttendance());
        assertNotNull(dto.getAttendance().getInsight());
    }

    private Team team(Long id, String name, double reputation, int capacity) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setReputation(reputation);
        Stadium stadium = new Stadium();
        stadium.setCapacity(capacity);
        team.setStadium(stadium);
        return team;
    }

    private Player player(Long id, String name, Team team) {
        Player player = new Player();
        player.setId(id);
        player.setName(name);
        player.setTeam(team);
        return player;
    }

    private Match match(Long id, Competition league, Team home, Team away, int homeGoals, int awayGoals, int seasonYear, int round, int attendance) {
        Match match = new Match();
        match.setId(id);
        match.setCompetition(league);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setSeasonYear(seasonYear);
        match.setRoundNumber(round);
        match.setMatchDate(LocalDateTime.of(2026, 3, Math.min(20, round + 1), 17, 0));
        match.setPlayed(true);
        match.setAttendance(attendance);
        return match;
    }

    private GoalEvent goal(Match match, Team team, Player scorer, Player assistant) {
        GoalEvent event = new GoalEvent();
        event.setMatch(match);
        event.setTeam(team);
        event.setScorer(scorer);
        event.setAssistant(assistant);
        event.setScored(true);
        return event;
    }
}