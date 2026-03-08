package org.example.footballmanager.engines;

import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIDecisionMakerTest {

    private final AIDecisionMaker decisionMaker = new AIDecisionMaker();

    @Test
    void prefersForwardPassOverSpeculativeShotFromEdgeOfZone() {
        Player shooter = player(9L, Position.ATT, 14, 15, 14, 13);
        Player runner = player(10L, Position.ATT, 12, 13, 14, 12);
        Player defender = player(21L, Position.DEF, 6, 6, 8, 14);

        MatchRuntime rt = runtime(
                List.of(shooter, runner),
                List.of(defender),
                List.of(
                        new PlayerPositionDTO(9, "HOME", 70.0, 50.0, 0, 0),
                        new PlayerPositionDTO(10, "HOME", 82.0, 48.0, 0, 0),
                        new PlayerPositionDTO(21, "AWAY", 88.0, 70.0, 0, 0)
                )
        );

        AIDecisionMaker.Decision decision = decisionMaker.makeDecision(shooter, rt, new Match(), 54);

        assertEquals(AIDecisionMaker.ActionType.PASS, decision.getAction());
        assertEquals(runner, decision.getTargetPlayer());
    }

    @Test
    void keepsCloseRangeCentralChanceAsShot() {
        Player poacher = player(9L, Position.ATT, 16, 17, 15, 14);
        Player teammate = player(10L, Position.MID, 10, 12, 12, 12);
        Player defender = player(21L, Position.DEF, 6, 7, 8, 14);

        MatchRuntime rt = runtime(
                List.of(poacher, teammate),
                List.of(defender),
                List.of(
                        new PlayerPositionDTO(9, "HOME", 85.0, 50.0, 0, 0),
                        new PlayerPositionDTO(10, "HOME", 79.0, 56.0, 0, 0),
                        new PlayerPositionDTO(21, "AWAY", 90.0, 74.0, 0, 0)
                )
        );

        AIDecisionMaker.Decision decision = decisionMaker.makeDecision(poacher, rt, new Match(), 72);

        assertEquals(AIDecisionMaker.ActionType.SHOT, decision.getAction());
    }

    @Test
    void prefersProgressivePassFromMediumRangeInsteadOfRushedShot() {
        Player carrier = player(8L, Position.MID, 16, 13, 15, 14);
        Player runner = player(11L, Position.ATT, 11, 14, 13, 13);
        Player defender = player(21L, Position.DEF, 7, 7, 8, 12);

        MatchRuntime rt = runtime(
                List.of(carrier, runner),
                List.of(defender),
                List.of(
                        new PlayerPositionDTO(8, "HOME", 79.0, 50.0, 0, 0),
                        new PlayerPositionDTO(11, "HOME", 87.0, 46.0, 0, 0),
                        new PlayerPositionDTO(21, "AWAY", 90.0, 71.0, 0, 0)
                )
        );

        AIDecisionMaker.Decision decision = decisionMaker.makeDecision(carrier, rt, new Match(), 63);

        assertEquals(AIDecisionMaker.ActionType.PASS, decision.getAction());
        assertEquals(runner, decision.getTargetPlayer());
    }

    private MatchRuntime runtime(List<Player> homePlayers, List<Player> awayPlayers, List<PlayerPositionDTO> positions) {
        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = homePlayers;
        rt.awayPlayers = awayPlayers;
        rt.players = positions;
        return rt;
    }

    private Player player(Long id, Position position, int passing, int striker, int technique, int pace) {
        Skills skills = new Skills();
        skills.setPassing(passing);
        skills.setStriker(striker);
        skills.setTechnique(technique);
        skills.setPace(pace);

        Player player = new Player();
        player.setId(id);
        player.setName("P" + id);
        player.setPosition(position);
        player.setSkills(skills);
        return player;
    }
}