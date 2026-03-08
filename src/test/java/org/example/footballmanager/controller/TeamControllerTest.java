package org.example.footballmanager.controller;

import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
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

    @InjectMocks private TeamController teamController;

    @Test
    void getLineupTemplateMarksMissingTemplateAsUnsaved() {
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = teamController.getLineupTemplate(1L);

        assertEquals(200, response.getStatusCode().value());
        assertFalse((Boolean) response.getBody().get("saved"));
        assertEquals("4-4-2", response.getBody().get("formation"));
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
        assertTrue((Boolean) response.getBody().get("saved"));
        assertEquals(77L, response.getBody().get("id"));
        assertEquals(starterIds, response.getBody().get("starterIds"));
        assertEquals(benchIds, response.getBody().get("benchIds"));
    }
}