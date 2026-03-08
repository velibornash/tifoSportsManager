package org.example.footballmanager.zox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchTickState;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchTickStateRepository;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZoxReplayServiceTest {

    @Mock
    private MatchRepository matchRepository;

    @Mock
    private MatchTickStateRepository tickStateRepository;

    @Mock
    private MatchEventRepository matchEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ZoxReplayService replayService;

    @BeforeEach
    void setUp() {
        replayService = new ZoxReplayService(
                matchRepository,
                tickStateRepository,
                matchEventRepository,
                objectMapper,
                new MatchEventMapper()
        );
    }

    @Test
    void buildsMetadataAndChunkFromStoredTickStates() throws Exception {
        Match match = new Match();
        match.setId(1L);
        match.setHomeTeam(team(100L, "Omladinac"));
        match.setAwayTeam(team(200L, "Radnicki"));
        match.setHomeGoals(1);
        match.setAwayGoals(0);
        match.setPlayed(true);
        match.setFinished(true);

        Player homePlayer = player(10L, "Marko Nikolic", Position.ATT, 9, match.getHomeTeam());
        Player awayPlayer = player(20L, "Ivan Petrovic", Position.DEF, 4, match.getAwayTeam());
        Player homeAssistant = player(11L, "Nikola Jovanovic", Position.MID, 8, match.getHomeTeam());

        Lineup homeLineup = new Lineup();
        homeLineup.setFormation("4-4-2");
        homeLineup.setStartingPlayers(List.of(homePlayer, homeAssistant));
        match.setHomeLineup(homeLineup);

        Lineup awayLineup = new Lineup();
        awayLineup.setFormation("4-3-3");
        awayLineup.setStartingPlayers(List.of(awayPlayer));
        match.setAwayLineup(awayLineup);

        List<PlayerPositionDTO> tick0Players = List.of(
                new PlayerPositionDTO(10, "HOME", 15.0, 50.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 85.0, 50.0, 0, 0)
        );
        List<PlayerPositionDTO> tick80Players = List.of(
                new PlayerPositionDTO(10, "HOME", 30.0, 52.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 70.0, 48.0, 0, 0)
        );
        List<PlayerPositionDTO> tick120Players = List.of(
                new PlayerPositionDTO(10, "HOME", 42.0, 49.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 62.0, 51.0, 0, 0)
        );

        MatchTickState state0 = new MatchTickState(match, 0,
                objectMapper.writeValueAsString(tick0Players),
                objectMapper.writeValueAsString(new org.example.footballmanager.dto.BallPositionDTO(15.0, 50.0)),
                10,
                false,
                null);
        MatchTickState state80 = new MatchTickState(match, 80,
                objectMapper.writeValueAsString(tick80Players),
                objectMapper.writeValueAsString(new org.example.footballmanager.dto.BallPositionDTO(30.0, 52.0)),
                10,
                false,
                null);
        MatchTickState state120 = new MatchTickState(match, 120,
                objectMapper.writeValueAsString(tick120Players),
                objectMapper.writeValueAsString(new org.example.footballmanager.dto.BallPositionDTO(42.0, 49.0)),
                10,
                true,
                20);

        GoalEvent goalEvent = new GoalEvent();
        goalEvent.setId(900L);
        goalEvent.setMatch(match);
        goalEvent.setMinute(3);
        goalEvent.setTick(80);
        goalEvent.setTeam(match.getHomeTeam());
        goalEvent.setScorer(homePlayer);
        goalEvent.setAssistant(homeAssistant);
        goalEvent.setScoreAfterGoal("1-0");
        goalEvent.setXG(0.42);

        when(matchRepository.findById(1L)).thenReturn(Optional.of(match));
        when(tickStateRepository.findByMatchOrderByTickAsc(match)).thenReturn(List.of(state0, state80, state120));
        when(matchEventRepository.findByMatch(match)).thenReturn(List.of(goalEvent));

        ZoxPlaybackMetadataDTO metadata = replayService.getPlaybackMetadata(1L);
        ZoxPlaybackChunkDTO firstChunk = replayService.getPlaybackChunk(1L, 0);
        ZoxPlaybackChunkDTO secondChunk = replayService.getPlaybackChunk(1L, 1);

        assertEquals(1L, metadata.getMatchId());
        assertEquals(2, metadata.getChunkCount());
        assertEquals(120, metadata.getTotalTicks());
        assertEquals(44_400L, metadata.getTotalDurationMs());
        assertEquals(3, metadata.getPlayersData().size());
        assertEquals(1, metadata.getGoalsData().size());
        assertEquals("HOME", metadata.getGoalsData().getFirst().getTeamSide());
        assertEquals(1, metadata.getEventData().size());
        assertEquals(0.42, metadata.getEventData().getFirst().getXG());
        assertEquals("Nikola Jovanovic", metadata.getEventData().getFirst().getAssistantName());
        assertEquals("1-0", metadata.getEventData().getFirst().getScoreAfterGoal());
        String metadataJson = objectMapper.writeValueAsString(metadata);
        assertTrue(metadataJson.contains("\"chunk_count\":2"));
        assertTrue(metadataJson.contains("\"players\""));
        assertTrue(metadataJson.contains("\"xg\"") || metadataJson.contains("\"xG\""));
        assertTrue(metadataJson.contains("0.42"));

        assertEquals(0, firstChunk.getChunkIndex());
        assertFalse(firstChunk.isLastChunk());
        assertEquals(3, firstChunk.getFrames().size());
        assertEquals(1, firstChunk.getEventData().size());
        assertEquals(3, firstChunk.getPlayerPositions().get(10L).size());
        assertTrue(objectMapper.writeValueAsString(firstChunk).contains("\"ball\""));
        assertTrue(objectMapper.writeValueAsString(firstChunk).contains("\"events\""));

        assertEquals(1, secondChunk.getChunkIndex());
        assertTrue(secondChunk.isLastChunk());
        assertEquals(2, secondChunk.getFrames().size());
        assertTrue(secondChunk.getEventData().isEmpty());
        assertTrue(secondChunk.getBallData().getLast().isBallInTransit());
        assertEquals(20, secondChunk.getBallData().getLast().getPendingReceiverId());
    }

    private Team team(Long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }

    private Player player(Long id, String name, Position position, int squadNumber, Team team) {
        Player player = new Player();
        player.setId(id);
        player.setName(name);
        player.setPosition(position);
        player.setSquadNumber(squadNumber);
        player.setTeam(team);
        return player;
    }
}