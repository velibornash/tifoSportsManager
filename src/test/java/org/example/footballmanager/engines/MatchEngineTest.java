package org.example.footballmanager.engines;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MatchEngineTest {

    @Test
    void selectStartingPlayersKeepsSingleGoalkeeperEvenIfTwoArePreferred() {
        List<Player> pool = new ArrayList<>();
        pool.add(player(1L, Position.GK));
        pool.add(player(2L, Position.GK));
        pool.add(player(3L, Position.DEF));
        pool.add(player(4L, Position.DEF));
        pool.add(player(5L, Position.DEF));
        pool.add(player(6L, Position.DEF));
        pool.add(player(7L, Position.MID));
        pool.add(player(8L, Position.MID));
        pool.add(player(9L, Position.MID));
        pool.add(player(10L, Position.WNG));
        pool.add(player(11L, Position.WNG));
        pool.add(player(12L, Position.ATT));
        pool.add(player(13L, Position.ATT));

        List<Player> starters = MatchEngine.selectStartingPlayers(pool, List.of(2L, 1L, 12L, 13L, 7L, 8L, 9L, 3L, 4L, 5L, 6L));

        assertEquals(11, starters.size());
        assertEquals(1, starters.stream().filter(player -> player.getPosition() == Position.GK).count());
        assertEquals(2L, starters.get(0).getId());
        assertFalse(starters.stream().anyMatch(player -> player.getId().equals(1L)));
    }

    private Player player(Long id, Position position) {
        Player player = new Player();
        player.setId(id);
        player.setPosition(position);
        player.setInjured(false);
        return player;
    }
}