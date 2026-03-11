package org.example.footballmanager.controller;

import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.service.MatchDetailService;
import org.example.footballmanager.service.MatchReportService;
import org.example.footballmanager.service.ScheduleInsightService;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchControllerTest {

    @Mock private MatchRepository matchRepository;
    @Mock private MatchDetailService matchDetailService;
    @Mock private MatchEventRepository matchEventRepository;
    @Mock private MatchEventMapper matchEventMapper;
    @Mock private MatchReportService matchReportService;
    @Mock private ScheduleInsightService scheduleInsightService;

    @InjectMocks private MatchController matchController;

    @Test
    void getMatchUsesTeamLoader() {
        Match match = new Match();
        match.setId(55L);
        match.setHomeTeam(team(1L, "OFK Omladinac"));
        match.setAwayTeam(team(2L, "Rival"));
        match.setMatchDate(LocalDateTime.of(2026, 3, 11, 18, 0));

        when(matchRepository.findWithTeamsById(55L)).thenReturn(Optional.of(match));

        var response = matchController.getMatch(55L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("OFK Omladinac", response.getBody().getHomeTeam());
        assertEquals("Rival", response.getBody().getAwayTeam());
    }

    @Test
    void getMatchLineupsUsesDetailedLoader() {
        Player starter = new Player();
        starter.setId(9L);
        starter.setName("Petar Petrovic");

        Lineup homeLineup = new Lineup();
        homeLineup.setStartingPlayers(List.of(starter));
        homeLineup.setStarterOrderFromIds(List.of(9L));

        Match match = new Match();
        match.setId(77L);
        match.setHomeTeam(team(1L, "OFK Omladinac"));
        match.setAwayTeam(team(2L, "Rival"));
        match.setHomeLineup(homeLineup);

        when(matchRepository.findDetailedById(77L)).thenReturn(Optional.of(match));

        var response = matchController.getMatchLineups(77L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("OFK Omladinac", response.getBody().get("homeTeam"));
        assertEquals(1, ((List<?>) response.getBody().get("homeLineup")).size());
        assertEquals(List.of(), response.getBody().get("awayLineup"));
    }

    @Test
    void revealMatchResultMarksOnlyAuthenticatedUsersSide() {
        Team home = team(1L, "OFK Omladinac");
        Team away = team(2L, "Rival");

        Match match = new Match();
        match.setId(88L);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setPlayed(true);
        match.setHomeResultRevealed(false);
        match.setAwayResultRevealed(false);

        User user = new User();
        user.setTeam(home);

        when(matchRepository.findWithTeamsById(88L)).thenReturn(Optional.of(match));
        when(matchRepository.save(match)).thenReturn(match);

        var response = matchController.revealMatchResult(88L, user);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(match.isHomeResultRevealed());
        assertFalse(match.isAwayResultRevealed());
        assertFalse(response.getBody().isResultHidden());
        assertTrue(response.getBody().isResultRevealed());
        verify(matchRepository).save(match);
    }

    private Team team(Long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }
}