package org.example.footballmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.dto.TacticsEditorDTO;
import org.example.footballmanager.dto.TacticsEditorSaveRequest;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.TeamTacticsProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamTacticsServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private LineupRepository lineupRepository;
    @Mock private TeamTacticsProfileRepository teamTacticsProfileRepository;
    @Mock private TacticsProfileBackupService tacticsProfileBackupService;

    private TeamTacticsService teamTacticsService;

    @BeforeEach
    void setUp() {
        teamTacticsService = new TeamTacticsService(
                teamRepository,
                playerRepository,
                lineupRepository,
                teamTacticsProfileRepository,
                new ObjectMapper(),
                new FormationSlotCatalog(),
                tacticsProfileBackupService
        );
    }

    @Test
    void saveTacticsEditorStoresMutableLineupCollections() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        List<Player> players = new ArrayList<>();
        for (long id = 1; id <= 18; id++) {
            Player player = new Player();
            player.setId(id);
            player.setName("P" + id);
            player.setRating((int) (90 - id));
            player.setInjured(false);
            players.add(player);
        }

        List<Long> starterIds = List.of(11L, 1L, 7L, 5L, 3L, 9L, 2L, 4L, 6L, 8L, 10L);
        List<Long> benchIds = List.of(18L, 17L, 16L, 15L, 14L, 13L, 12L);
        Lineup existingLineup = new Lineup();
        existingLineup.setId(55L);
        existingLineup.setTeam(team);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerRepository.findByTeamId(1L)).thenReturn(players);
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.of(existingLineup));
        when(lineupRepository.save(any(Lineup.class))).thenAnswer(invocation -> {
            Lineup lineup = invocation.getArgument(0);
            Player probe = new Player();
            probe.setId(999L);
            lineup.getStartingPlayers().add(probe);
            lineup.getStartingPlayers().remove(probe);
            lineup.getSubstitutes().add(probe);
            lineup.getSubstitutes().remove(probe);
            return lineup;
        });
        when(teamTacticsProfileRepository.findByTeamId(1L)).thenReturn(Optional.empty());
        when(teamTacticsProfileRepository.save(any(TeamTacticsProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TacticsEditorSaveRequest request = new TacticsEditorSaveRequest();
        request.setFormation("4-3-3");
        request.setStyle("HIGH_PRESS");
        request.setStarterIds(starterIds);
        request.setBenchIds(benchIds);

        TacticsEditorDTO result = assertDoesNotThrow(() -> teamTacticsService.saveTacticsEditor(1L, request));

        assertNotNull(result);
        assertEquals(starterIds, result.getStarterIds());
        assertEquals(benchIds, result.getBenchIds());
        assertTrue(existingLineup.getStartingPlayers() instanceof ArrayList);
        assertTrue(existingLineup.getSubstitutes() instanceof ArrayList);
        verify(lineupRepository).save(existingLineup);
    }

    @Test
    void saveTacticsEditorMirrorsWeHaveBallRulesIntoOpponentHaveBallIncludingCorners() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        List<Player> players = new ArrayList<>();
        for (long id = 1; id <= 18; id++) {
            Player player = new Player();
            player.setId(id);
            player.setName("P" + id);
            player.setRating((int) (90 - id));
            player.setInjured(false);
            players.add(player);
        }

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerRepository.findByTeamId(1L)).thenReturn(players);
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.of(new Lineup()));
        when(lineupRepository.save(any(Lineup.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicReference<TeamTacticsProfile> savedProfile = new AtomicReference<>();
        when(teamTacticsProfileRepository.findByTeamId(1L)).thenAnswer(invocation -> Optional.ofNullable(savedProfile.get()));
        when(teamTacticsProfileRepository.save(any(TeamTacticsProfile.class))).thenAnswer(invocation -> {
            TeamTacticsProfile profile = invocation.getArgument(0);
            savedProfile.set(profile);
            return profile;
        });

        TacticsEditorSaveRequest request = new TacticsEditorSaveRequest();
        request.setFormation("4-4-2");
        request.setStyle("BALANCED");
        request.setStarterIds(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));
        request.setBenchIds(List.of(12L, 13L, 14L, 15L, 16L, 17L, 18L));
        request.setMovementRules(List.of(
                new org.example.footballmanager.dto.TacticsRuleDTO("STL", "CELL_4_2", "WE_HAVE_BALL", "CELL_4_3"),
                new org.example.footballmanager.dto.TacticsRuleDTO("STL", "ATTACK_LEFT_CORNER", "WE_HAVE_BALL", "CELL_4_4")
        ));

        TacticsEditorDTO result = teamTacticsService.saveTacticsEditor(1L, request);

        String mirrored = result.getMovementRules().stream()
                .filter(rule -> "STL".equals(rule.getSlotKey()))
                .filter(rule -> "CELL_4_2".equals(rule.getBallStateKey()))
                .filter(rule -> "OPPONENT_HAS_BALL".equals(rule.getPossessionContext()))
                .map(rule -> rule.getTargetCellKey())
                .findFirst()
                .orElse(null);

        String cornerMirrored = result.getMovementRules().stream()
                .filter(rule -> "STL".equals(rule.getSlotKey()))
                .filter(rule -> "ATTACK_LEFT_CORNER".equals(rule.getBallStateKey()))
                .filter(rule -> "OPPONENT_HAS_BALL".equals(rule.getPossessionContext()))
                .map(rule -> rule.getTargetCellKey())
                .findFirst()
                .orElse(null);

        assertEquals("CELL_4_3", mirrored);
        assertEquals("CELL_4_4", cornerMirrored);
        verify(tacticsProfileBackupService).saveOrUpdate(any(Team.class), any(TeamTacticsProfile.class));
    }
}
