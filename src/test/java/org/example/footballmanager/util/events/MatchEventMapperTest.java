package org.example.footballmanager.util.events;

import org.example.footballmanager.dto.ChanceEventDTO;
import org.example.footballmanager.dto.GoalEventDTO;
import org.example.footballmanager.dto.MatchEventDTO;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.ChanceEvent;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.PassEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchEventMapperTest {

    private final MatchEventMapper mapper = new MatchEventMapper();

    @Test
    void mapsGoalAsCriticalKeyEventWithRoundedXg() {
        Team team = team("Omladinac");
        Player scorer = player("Marko", team);
        Player assistant = player("Nikola", team);

        GoalEvent event = new GoalEvent();
        event.setMinute(24);
        event.setTeam(team);
        event.setScorer(scorer);
        event.setAssistant(assistant);
        event.setScoreAfterGoal("1-0");
        event.setXG(0.186);

        GoalEventDTO dto = (GoalEventDTO) mapper.toDto(event);

        assertEquals("goal", dto.getType());
        assertEquals("key", dto.getDisplayCategory());
        assertEquals("critical", dto.getImportance());
        assertTrue(dto.isKeyEvent());
        assertEquals("24'", dto.getClockLabel());
        assertEquals(0.19, dto.getXG());
        assertEquals("Marko", dto.getScorerName());
        assertEquals("Nikola", dto.getAssistantName());
        assertEquals("Omladinac", dto.getTeamName());
    }

    @Test
    void mapsDangerousChanceAsHighImportanceKeyEvent() {
        Team team = team("Omladinac");
        Player player = player("Petar", team);

        ChanceEvent event = new ChanceEvent();
        event.setMinute(57);
        event.setTeam(team);
        event.setPlayer(player);
        event.setDangerous(true);

        ChanceEventDTO dto = (ChanceEventDTO) mapper.toDto(event);

        assertEquals("chance", dto.getType());
        assertEquals("key", dto.getDisplayCategory());
        assertEquals("high", dto.getImportance());
        assertTrue(dto.isKeyEvent());
        assertTrue(dto.isDangerous());
    }

    @Test
    void mapsPassAsMicroNonKeyEvent() {
        Team team = team("Omladinac");
        Player passer = player("Luka", team);
        Player receiver = player("Jovan", team);

        PassEvent event = new PassEvent();
        event.setMinute(11);
        event.setTeam(team);
        event.setPasser(passer);
        event.setReceiver(receiver);

        MatchEventDTO dto = mapper.toDto(event);

        assertEquals("pass", dto.getType());
        assertEquals("micro", dto.getDisplayCategory());
        assertEquals("low", dto.getImportance());
        assertFalse(dto.isKeyEvent());
        assertEquals("Luka", dto.getPlayerName());
        assertEquals("Jovan", dto.getTargetPlayerName());
    }

    private Team team(String name) {
        Team team = new Team();
        team.setName(name);
        return team;
    }

    private Player player(String name, Team team) {
        Player player = new Player();
        player.setName(name);
        player.setPosition(Position.MID);
        team.addPlayer(player);
        return player;
    }
}