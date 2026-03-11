package org.example.footballmanager.engines;

import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RealisticMatchEngineTest {

    @Test
    void allowsShotAfterCentralReceptionAroundEighteenMetersEvenWithTwoNearbyDefenders() throws Exception {
        RealisticMatchEngine engine = new RealisticMatchEngine(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        Player shooter = player(9L, Position.ATT);
        Player defenderOne = player(21L, Position.DEF);
        Player defenderTwo = player(22L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(shooter);
        rt.awayPlayers = List.of(defenderOne, defenderTwo);
        rt.players = List.of(
                new PlayerPositionDTO(9, "HOME", 82.0, 50.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 86.0, 47.0, 0, 0),
                new PlayerPositionDTO(22, "AWAY", 87.0, 54.0, 0, 0)
        );
        rt.currentCarrier = new PlayerPositionDTO(9, "HOME", 82.0, 50.0, 0, 0);
        rt.ball = new BallPositionDTO(82.0, 50.0);
        rt.lastTouchTeam = "HOME";
        rt.tick = 12;
        rt.lastPassReceiverId = 9;
        rt.lastPassReceiveTeam = "HOME";
        rt.lastPassReceiveTick = 10;
        rt.lastPassReceiveX = 80.0;

        Method canShootNow = RealisticMatchEngine.class
                .getDeclaredMethod("canShootNow", MatchRuntime.class, Player.class, String.class);
        canShootNow.setAccessible(true);

        boolean result = (boolean) canShootNow.invoke(engine, rt, shooter, "HOME");

        assertTrue(result);
    }

    private Player player(Long id, Position position) {
        Player player = new Player();
        player.setId(id);
        player.setName("P" + id);
        player.setPosition(position);
        return player;
    }
}