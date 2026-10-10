package org.example.footballmanager.newLogic.util.players;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player must not be created with a rating of zero (2026-10-10).
 *
 * <p><b>What this was found by.</b> The owner showed a match reading 91% possession, 10 shots to 1, and
 * not a single event belonging to the away side, and said possession was broken. It was not the engine —
 * his own club held <b>13 players, every one rated 0, with no role assigned</b>, and lost 2-0 while
 * dominating the ball. The engine's {@code Math.max(1, rating)} floors a zero rating at 1, so thirteen
 * blanks were a real but hopeless side, and the possession split faithfully described the mismatch.
 *
 * <p><b>Why the rating is the important half.</b> A missing role is survivable — {@code RealSquadFactory}
 * derives a role from position, so a null role still fields correctly. A missing rating is not: it is the
 * number every layer reads, and the world seeder, {@code BotSquadGenerator},
 * {@code BotLeagueStandardBackfill} and {@code PlayerRatingBackfill} all set it from
 * {@code careerRating()}. This one overload did not, which is how two clubs ended up unplayable.
 */
class PlayerFactoryGivesEveryPlayerARatingTest {

    private static Player create(Position position) {
        return PlayerFactory.createPlayer(
                "Test Player", 24, new Team(), 500_000, 2_000,
                180, 75, 6, 5, 70,
                40, 70, 75, 60, 55, 60, 70,
                position, 9);
    }

    @Test
    @DisplayName("a created player is rated from its skills, not left at zero")
    void aCreatedPlayerIsRated() {
        for (Position position : Position.values()) {
            Player player = create(position);
            assertTrue(player.getRating() > 0,
                    "a " + position + " created with 70-rated skills came out rated "
                            + player.getRating() + ". A rating of 0 is not a weak player: the engine "
                            + "floors it at 1, so the whole squad sits at the bottom of the scale against "
                            + "any properly seeded club.");
        }
    }

    @Test
    @DisplayName("better skills produce a better rating")
    void betterSkillsProduceABetterRating() {
        Player weak = PlayerFactory.createPlayer("W", 24, new Team(), 100_000, 500,
                180, 75, 5, 5, 20, 10, 20, 20, 15, 15, 15, 10, Position.MID, 9);
        Player strong = PlayerFactory.createPlayer("S", 24, new Team(), 5_000_000, 90_000,
                190, 80, 9, 8, 95, 85, 90, 92, 88, 90, 92, 90, Position.MID, 9);

        assertTrue(strong.getRating() > weak.getRating(),
                "a side built from 90-rated skills (" + strong.getRating() + ") must out-rate one built "
                        + "from 15-rated skills (" + weak.getRating() + "). Equal ratings here would mean "
                        + "the number is still coming from somewhere other than the skills.");
    }

    @Test
    @DisplayName("a created player has a role that matches its position")
    void aCreatedPlayerHasARole() {
        assertEquals(PlayerRole.GOALKEEPER, create(Position.GK).getRole());
        assertEquals(PlayerRole.CENTRE_BACK, create(Position.DEF).getRole());
        assertEquals(PlayerRole.CENTRE_MIDFIELDER, create(Position.MID).getRole());
        assertEquals(PlayerRole.WINGER, create(Position.WNG).getRole());
        assertEquals(PlayerRole.STRIKER, create(Position.ATT).getRole());
    }

    @Test
    @DisplayName("a player with no position is still given a usable role")
    void aPlayerWithNoPositionStillHasARole() {
        Player player = create(null);

        assertNotNull(player.getRole(),
                "a null role is read as MID by half the codebase, so a striker who never had one was a "
                        + "midfielder to everything that reads it. There is no 'unknown' here on purpose: "
                        + "the engine runs on position, and a missing position has a safe default.");
    }
}