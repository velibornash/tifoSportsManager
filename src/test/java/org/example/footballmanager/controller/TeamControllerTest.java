package org.example.footballmanager.controller;

import org.example.footballmanager.dto.LeagueMilestonesDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.LeagueMilestoneService;
import org.example.footballmanager.service.SeasonService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamControllerTest {

    @Mock private TeamRepository teamRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private LineupRepository lineupRepository;
    @Mock private MatchPlayerStatsRepository matchPlayerStatsRepository;
    @Mock private LeagueMilestoneService leagueMilestoneService;
    @Mock private SeasonService seasonService;

    @InjectMocks private TeamController teamController;

    @Test
    void getLineupTemplateMarksMissingTemplateAsUnsaved() {
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = teamController.getLineupTemplate(1L);

        assertEquals(200, response.getStatusCode().value());
        assertFalse((Boolean) response.getBody().get("saved"));
        assertEquals("4-4-2", response.getBody().get("formation"));
        assertEquals("BALANCED", response.getBody().get("style"));
        assertEquals(List.of(), response.getBody().get("starterIds"));
        assertEquals(List.of(), response.getBody().get("benchIds"));
    }

    @Test
    void saveLineupTemplatePreservesRequestedStarterAndBenchOrder() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        List<Player> players = new ArrayList<>();
        for (long id = 1; id <= 18; id++) {
            Player player = new Player();
            player.setId(id);
            player.setName("P" + id);
            player.setRating((int) (80 - id));
            player.setInjured(false);
            players.add(player);
        }

        List<Long> starterIds = List.of(11L, 1L, 7L, 5L, 3L, 9L, 2L, 4L, 6L, 8L, 10L);
        List<Long> benchIds = List.of(18L, 17L, 16L, 15L, 14L, 13L, 12L);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formation", "4-3-3");
        payload.put("style", "HIGH_PRESS");
        payload.put("starterIds", starterIds);
        payload.put("benchIds", benchIds);

        Lineup existingTemplate = new Lineup();
        existingTemplate.setId(77L);
        existingTemplate.setTeam(team);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerRepository.findByTeamId(1L)).thenReturn(players);
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.of(existingTemplate));
        when(lineupRepository.save(any(Lineup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = teamController.saveLineupTemplate(1L, payload);

        ArgumentCaptor<Lineup> captor = ArgumentCaptor.forClass(Lineup.class);
        verify(lineupRepository).save(captor.capture());
        Lineup saved = captor.getValue();

        assertEquals(starterIds, saved.getStartingPlayers().stream().map(Player::getId).toList());
        assertEquals(benchIds, saved.getSubstitutes().stream().map(Player::getId).toList());
        assertEquals("4-3-3", saved.getFormation());
        assertEquals("HIGH_PRESS", saved.getStyle());
        assertTrue((Boolean) response.getBody().get("saved"));
        assertEquals(77L, response.getBody().get("id"));
        assertEquals("HIGH_PRESS", response.getBody().get("style"));
        assertEquals(starterIds, response.getBody().get("starterIds"));
        assertEquals(benchIds, response.getBody().get("benchIds"));
    }

    @Test
    void getPlayersIncludesAppsAndAverageRating() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        Skills skills = new Skills();
        skills.setGoalkeeper(3);
        skills.setPace(11);
        skills.setStriker(14);
        skills.setPassing(10);
        skills.setTechnique(12);
        skills.setDefender(6);
        skills.setStamina(13);
        skills.setPlaymaker(9);
        skills.setFatigue(2);

        Player player = new Player();
        player.setId(5L);
        player.setName("Marko");
        player.setAge(24);
        player.setPosition(Position.ATT);
        player.setForm(7.2);
        player.setRating(74);
        player.setSkills(skills);
        player.setTeam(team);

        MatchPlayerStats first = new MatchPlayerStats();
        first.setPlayer(player);
        first.setRating(78);
        MatchPlayerStats second = new MatchPlayerStats();
        second.setPlayer(player);
        second.setRating(84);

        when(playerRepository.findByTeamId(1L)).thenReturn(List.of(player));
        when(matchPlayerStatsRepository.findByPlayerIdIn(List.of(5L))).thenReturn(List.of(first, second));

        ResponseEntity<List<PlayerDTO>> response = teamController.getPlayers(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(2, response.getBody().getFirst().getMatchesPlayed());
        assertEquals(8.1, response.getBody().getFirst().getAverageRating10());
    }

    @Test
    void getTeamMilestonesUsesActiveSeasonFallback() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        LeagueMilestonesDTO dto = LeagueMilestonesDTO.builder()
                .seasonYear(2026)
                .build();

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(leagueMilestoneService.buildTeamMilestones(team, 2026)).thenReturn(dto);

        ResponseEntity<LeagueMilestonesDTO> response = teamController.getTeamMilestones(1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertSame(dto, response.getBody());
        verify(seasonService).getActiveSeasonYear();
        verify(leagueMilestoneService).buildTeamMilestones(team, 2026);
    }
}