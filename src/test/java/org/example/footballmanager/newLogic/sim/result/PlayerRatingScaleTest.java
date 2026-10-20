package org.example.footballmanager.newLogic.sim.result;

import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The match rating, on the owner's scale (owner, 2026-10-20).
 *
 * <p><b>The complaint.</b> *"a GK who received 4 goals got 10, a player with 1 goal in a 1-4 loss got
 * 8.8 - too much. When the player had an average game it is 6.5, let's say, one goal 7-ish, three
 * goals is 9ish."*
 *
 * <p>Three things were wrong with the formula that produced those numbers.
 *
 * <ol>
 *   <li><b>The goalkeeper's saves were unbounded.</b> Each save was worth +0.35 and there is no limit
 *       on how many a keeper can make, so a keeper who was busy because his team was losing collected
 *       more rating for the disaster than a striker collects for a hat-trick. There was <b>no term at
 *       all</b> for the goals he let in: his rating had no idea how many had gone past him.</li>
 *   <li><b>A goal was worth 1.6</b> - more than a quarter of the whole scale - and the base was 6.0, so
 *       one goal in a 1-4 loss read as 8.8.</li>
 *   <li><b>The base was 6.0</b>, and the owner's ordinary match is a 6.5.</li>
 * </ol>
 *
 * <p>Every number below is read through the public outcome - the thing the manager sees - and not by
 * calling the private formula, because a test on the private method passes while the screen shows
 * something else.
 */
class PlayerRatingScaleTest {

    // --- the owner's four numbers ---

    @Test
    @DisplayName("an ordinary game is a 6.5")
    void anOrdinaryGameIsAboutSixAndAHalf() throws Exception {
        ProposalStatsCollector stats = aMatch();
        stats.onGoal("HOME", "h1", null);

        double rating = ratingOf(stats, "d1");

        assertTrue(rating >= 6.4 && rating <= 7.0,
                "an ordinary player in an ordinary game came out at " + rating + ". The owner asked for "
                        + "6.5 - 'when the player had an average game it is 6.5'.");
    }

    @Test
    @DisplayName("one goal is a 7")
    void oneGoalIsAboutSeven() throws Exception {
        ProposalStatsCollector stats = aMatch();
        stats.onGoal("HOME", "h1", null);
        stats.onGoal("AWAY", "a1", null);
        stats.onGoal("AWAY", "a1", null);
        stats.onGoal("AWAY", "a1", null);
        stats.onGoal("AWAY", "a1", null);

        double rating = ratingOf(stats, "h1");

        assertTrue(rating >= 6.5 && rating <= 7.2,
                "one goal in a 1-4 loss came out at " + rating + ". The owner asked for 'one goal 7-ish' "
                        + "and called 8.8 'too much'.");
    }

    @Test
    @DisplayName("three goals is a 9")
    void threeGoalsIsAboutNine() throws Exception {
        ProposalStatsCollector stats = aMatch();
        stats.onGoal("HOME", "h1", null);
        stats.onGoal("HOME", "h1", null);
        stats.onGoal("HOME", "h1", null);

        double rating = ratingOf(stats, "h1");

        assertTrue(rating >= 8.6 && rating <= 9.6,
                "a hat-trick came out at " + rating + ". The owner asked for 'three goals is 9ish' - not "
                        + "the ceiling, and not seven.");
    }

    @Test
    @DisplayName("a goalkeeper who conceded four is not at the ceiling")
    void aGoalkeeperWhoConcededFourIsNotAtTheCeiling() throws Exception {
        ProposalStatsCollector stats = aMatch();
        // Four goals against him, and a busy afternoon: ten saves.
        for (int i = 0; i < 4; i++) stats.onGoal("AWAY", "a1", null);
        for (int i = 0; i < 10; i++) stats.onSave("HOME");
        // The rest of an ordinary keeper's evening: he passed it out, and he won his duels.
        for (int i = 0; i < 10; i++) {
            stats.onPassAttempt("HOME", "h11");
            stats.onPassCompleted("HOME", "h1", "h11");
        }
        for (int i = 0; i < 9; i++) stats.onDuelWon("h11", "a2");

        double rating = ratingOf(stats, "h11");

        assertTrue(rating < 8.0,
                "a goalkeeper who let in four goals came out at " + rating + ". The owner saw a keeper "
                        + "with four goals against him get a 10 - a rating that says he had an "
                        + "exceptional game.");
    }

    @Test
    @DisplayName("the goals a keeper let in cost him rating")
    void theGoalsHeLetInCostHimRating() throws Exception {
        // The same four saves in both matches; the only difference is the four goals against him.
        ProposalStatsCollector clean = aMatch();
        for (int i = 0; i < 4; i++) clean.onSave("HOME");

        ProposalStatsCollector leaking = aMatch();
        for (int i = 0; i < 4; i++) leaking.onSave("HOME");
        for (int i = 0; i < 4; i++) leaking.onGoal("AWAY", "a1", null);

        assertTrue(ratingOf(leaking, "h11") < ratingOf(clean, "h11"),
                "a keeper who let in four goals rated " + ratingOf(leaking, "h11") + " and one who "
                        + "made the same saves and conceded nothing rated " + ratingOf(clean, "h11")
                        + ". His rating had no idea how many goals had gone past him.");
    }

    // --- helpers ---

    /**
     * The rating a named player came out at, read through the public outcome.
     */
    private double ratingOf(ProposalStatsCollector stats, String playerId) {
        for (String team : List.of("HOME", "AWAY")) {
            for (PlayerStats entry : stats.buildPlayerStats(team)) {
                if (playerId.equals(entry.playerId())) {
                    return entry.rating();
                }
            }
        }
        throw new AssertionError("no player stats for " + playerId);
    }

    /**
     * A collector with a full eleven either side.
     *
     * <p>Every id is deterministic - "h1", "a1" - so a failed assertion can name the player it is
     * about. {@code h11} is the home goalkeeper and {@code d1} the ordinary defender the team's
     * ordinary game is measured on.
     */
    private ProposalStatsCollector aMatch() {
        ProposalStatsCollector stats = new ProposalStatsCollector("Home", "Away");
        List<Player> squad = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            squad.add(aPlayer("h" + i, "HOME", i == 11 ? "GK" : "DEF"));
        }
        for (int i = 1; i <= 11; i++) {
            squad.add(aPlayer("a" + i, "AWAY", i == 11 ? "GK" : "ATT"));
        }
        squad.add(aPlayer("d1", "HOME", "DEF"));
        stats.registerPlayers(squad);
        return stats;
    }

    private Player aPlayer(String id, String team, String role) {
        return new Player(id, id, team, role, new Position(4, 3), new Position(2, 3),
                new PlayerSkills(12, 12, 12, 12, 12, 12, 12, 12));
    }
}
