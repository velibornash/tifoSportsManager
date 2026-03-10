package org.example.footballmanager.service;

import org.example.footballmanager.dto.TeamMedicalOverviewDTO;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamMedicalServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private PlayerRepository playerRepository;

    @InjectMocks private TeamMedicalService teamMedicalService;

    @Test
    void buildOverviewCountsInjuriesRehabAndAverageCondition() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        Player injured = player(7L, "Marko", 26, 24, 10);
        injured.setInjuryDaysRemaining(10);
        Player tired = player(8L, "Petar", 24, 19, 0);
        Player fit = player(9L, "Nikola", 23, 6, 0);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerRepository.findByTeamId(1L)).thenReturn(List.of(injured, tired, fit));

        TeamMedicalOverviewDTO overview = teamMedicalService.buildOverview(1L);

        assertNotNull(overview);
        assertEquals(3, overview.getTotalPlayers());
        assertEquals(2, overview.getAvailableCount());
        assertEquals(1, overview.getInjuredCount());
        assertEquals(0, overview.getCriticalInjuryCount());
        assertEquals(2, overview.getRehabCount());
        assertEquals(84, overview.getAverageConditionPercent());
        assertEquals(List.of(7L, 8L), overview.getRecoveryQueue().stream().map(p -> p.getId()).toList());
    }

    @Test
    void applyRecoveryReducesFatigueAndInjuryDays() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        Player player = player(5L, "Luka", 27, 26, 7);
        player.setForm(6.8);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerRepository.findById(5L)).thenReturn(Optional.of(player));
        when(playerRepository.findByTeamId(1L)).thenReturn(List.of(player));
        when(playerRepository.save(any(Player.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TeamMedicalOverviewDTO overview = teamMedicalService.applyRecovery(1L, 5L);

        assertNotNull(overview);
        assertEquals(14, player.getSkills().getFatigue());
        assertEquals(4, player.getInjuryDaysRemaining());
        verify(playerRepository).save(player);
    }

    private Player player(Long id, String name, int age, int fatigue, int injuryDays) {
        Skills skills = new Skills();
        skills.setFatigue(fatigue);
        skills.setStamina(12);

        Player player = new Player();
        player.setId(id);
        player.setName(name);
        player.setAge(age);
        player.setPosition(Position.MID);
        player.setSkills(skills);
        player.setTeam(team());
        player.setForm(7.0);
        player.setInjuryDaysRemaining(injuryDays);
        return player;
    }

    private Team team() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");
        return team;
    }
}