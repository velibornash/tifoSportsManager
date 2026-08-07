package org.example.footballmanager.newLogic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.dto.PlayerPositionDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.SimulationService;
import org.example.footballmanager.newLogic.engine_v1.RealisticMatchEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class RealisticDebugIntegrationTest extends BaseTest {

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    PlayerRepository playerRepository;
    @Autowired
    LineupRepository lineupRepository;
    @Autowired
    MatchRepository matchRepository;
    @Autowired
    SimulationService simulationService;
    @Autowired
    RealisticMatchEngine realisticMatchEngine;
    @Autowired
    MatchTickStateRepository tickStateRepository;
    @Autowired
    ObjectMapper objectMapper;

    private Skills baseSkillsFor(Position pos) {
        Skills s = new Skills();
        s.setStamina(12);
        s.setPace(12);
        s.setTechnique(10);
        s.setPassing(10);
        s.setPlaymaker(8);
        s.setStriker(10);
        s.setDefender(10);
        s.setGoalkeeper(8);
        s.setFatigue(3);
        return s;
    }

    private Team createTeam(String name) {
        Team t = new Team();
        t.setName(name);
        t = teamRepository.save(t);
        return t;
    }

    private List<Player> createStartingXI(Team team) {
        List<Player> starters = new ArrayList<>();
        Position[] posOrder = new Position[]{Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF, Position.MID, Position.MID, Position.MID, Position.MID, Position.ATT, Position.ATT};
        for (int i = 0; i < posOrder.length; i++) {
            Player p = new Player();
            p.setName(team.getName() + " P" + (i+1));
            p.setPosition(posOrder[i]);
            Skills s = baseSkillsFor(posOrder[i]);
            if (posOrder[i] == Position.GK) s.setGoalkeeper(14);
            if (posOrder[i] == Position.ATT) s.setStriker(14);
            p.setSkills(s);
            p.setTeam(team);
            p = playerRepository.save(p);
            starters.add(p);
        }
        return starters;
    }

    private Lineup createLineup(Team team, List<Player> starters) {
        Lineup lu = new Lineup();
        lu.setTeam(team);
        lu.getStartingPlayers().addAll(starters);
        List<Long> ids = new ArrayList<>(); for (Player p : starters) ids.add(p.getId());
        lu.setStarterOrderFromIds(ids);
        return lineupRepository.save(lu);
    }

    @Test
    void simulateThreeMatchesInDebugMode() throws Exception {
        List<Map<String,Object>> summary = new ArrayList<>();

        for (int run = 0; run < 3; run++) {
            Team home = createTeam("HomeTest" + run);
            Team away = createTeam("AwayTest" + run);

            List<Player> homeStarters = createStartingXI(home);
            List<Player> awayStarters = createStartingXI(away);

            Lineup homeLu = createLineup(home, homeStarters);
            Lineup awayLu = createLineup(away, awayStarters);

            Match match = new Match();
            match.setHomeTeam(home);
            match.setAwayTeam(away);
            match.setHomeLineup(homeLu);
            match.setAwayLineup(awayLu);
            match = matchRepository.save(match);

            // Run engine directly (avoid DB persistence issues in tests)
            org.example.footballmanager.newLogic.model.MatchRuntime rt = realisticMatchEngine.simulateRealisticMatch(match);
            assertNotNull(rt);

            List<MatchTickState> ticks = new ArrayList<>();
            // Use in-memory rt.tickStates for metrics
            int tickCount = rt.tickStates != null ? rt.tickStates.size() : 0;
            int homeGoals = rt.homeGoals;
            int awayGoals = rt.awayGoals;

            // Build parsed tick map from runtime TickState objects
            Map<Integer, PlayerPositionDTO[]> parsed = new HashMap<>();
            if (rt.tickStates != null) {
                for (var ts : rt.tickStates) {
                    PlayerPositionDTO[] arr = objectMapper.convertValue(ts.players, PlayerPositionDTO[].class);
                    parsed.put(ts.tick, arr);
                }
            }

            // Check max per-tick movement for teleportation detection
            double maxMove = 0.0;
            List<Integer> tickKeys = new ArrayList<>(parsed.keySet());
            Collections.sort(tickKeys);
            for (int i = 0; i < tickKeys.size() - 1; i++) {
                PlayerPositionDTO[] a = parsed.get(tickKeys.get(i));
                PlayerPositionDTO[] b = parsed.get(tickKeys.get(i+1));
                if (a == null || b == null || a.length != b.length) continue;
                Map<Integer, PlayerPositionDTO> mapB = new HashMap<>();
                for (PlayerPositionDTO p : b) mapB.put(p.getId(), p);
                for (PlayerPositionDTO p : a) {
                    PlayerPositionDTO q = mapB.get(p.getId());
                    if (q == null) continue;
                    double dx = p.getX() - q.getX(); double dy = p.getY() - q.getY();
                    double dist = Math.sqrt(dx*dx + dy*dy);
                    if (dist > maxMove) maxMove = dist;
                }
            }

            Map<String,Object> res = new HashMap<>();
            res.put("matchId", match.getId());
            res.put("ticks", tickCount);
            res.put("score", homeGoals + "-" + awayGoals);
            res.put("maxMovePerTick", maxMove);
            summary.add(res);

            System.out.printf("Run %d: Match %d ticks=%d score=%s maxMove=%.3f\n", run+1, match.getId(), tickCount, homeGoals + "-" + awayGoals, maxMove);
        }

        // Simple assertions to ensure tests produced output
        assertEquals(3, summary.size());
    }
}
