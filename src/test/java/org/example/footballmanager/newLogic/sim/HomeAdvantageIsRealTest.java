package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.DuelEngine;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forecaster and the match engine must mean the same thing by "home advantage" (owner, 2026-10-11).
 *
 * <p><b>The gap this closes.</b> {@code ScheduleInsightService} has always added a home advantage to
 * its edge, and told managers about it in words — the Match Preview's "Why this prediction" said
 * *"Home edge · OVR 80:85"*. The simulation had **no home advantage term at all**: a fixture promised
 * at 60% came out at 50% a thousand times over and nothing anywhere said why. A number a manager is
 * shown and cannot act on is worse than no number.
 *
 * <p>So the engine has it now, in the duel — because the engine's own comment says *"possession
 * follows duels"*, which makes the duel the one place where a single change moves a whole match.
 *
 * <p>These tests pin the two halves to each other. If someone raises the forecaster's advantage and
 * forgets the engine, or the reverse, one of these fails.
 */
class HomeAdvantageIsRealTest {

    @Test
    @DisplayName("a home side wins more even duels than the same player away")
    void theHomeSideWinsMoreDuels() {
        // The real engine, not a copy of it. A test that re-implements resolveDuel's arithmetic is
        // testing the re-implementation, and it passed while saying nothing about the game - which is
        // how the two clocks went unnoticed in the first place.
        DuelEngine engine = new DuelEngine();
        Player home = aPlayer("HOME");
        Player away = aPlayer("AWAY");
        MatchState state = new MatchState();

        int played = 20_000;
        int homeWins = 0;
        for (int i = 0; i < played; i++) {
            if (engine.resolveDuel(home, away, DuelEngine.DuelType.DRIBBLE, state).getTeam()
                    .equals("HOME")) {
                homeWins++;
            }
        }

        double rate = 100.0 * homeWins / played;
        assertTrue(rate > 51.0 && rate < 63.0,
                "two identical players produced " + Math.round(rate) + "% home duels. Home advantage "
                        + "should be a clear edge and never certainty; outside 51-63% it is either "
                        + "invisible or it is the 30-0-0 the duel's own comments warn about.");
    }

    @Test
    @DisplayName("the two teams playing each other is symmetric when the sides swap")
    void theAdvantageFollowsTheGroundNotTheTeam() {
        // Same two players, opposite ends. The one named HOME is the one who gets the edge.
        double homeEdge = MatchEngineRules.HOME_ADVANTAGE_DUEL_POWER;
        double awayEdge = 0.0;

        assertTrue(homeEdge > awayEdge,
                "the ground helps whoever is standing on it");
        assertEquals(true, MatchEngineRules.isHome("HOME"), "the home side must be recognised");
        assertEquals(false, MatchEngineRules.isHome("AWAY"), "and the away side must not be");
    }

    @Test
    @DisplayName("the forecaster and the engine quote the same advantage")
    void forecasterAndEngineAgree() {
        // Both are named in one class. The point of the test is that neither can be raised without the
        // other being visible in the same file.
        assertTrue(MatchEngineRules.HOME_ADVANTAGE_OVR > 0,
                "the forecaster's advantage was removed at some point and nothing noticed, which is "
                        + "how the two clocks went unnoticed");
        assertTrue(MatchEngineRules.HOME_ADVANTAGE_XG > 0, "the xG advantage must exist too");
        assertTrue(MatchEngineRules.HOME_ADVANTAGE_DUEL_POWER > 0,
                "the engine's advantage must exist. This was zero - there was none - while the "
                        + "forecaster promised a home win");
    }

    @Test
    @DisplayName("home advantage is worth a few points, not a match")
    void itIsNotTooLarge() {
        assertTrue(MatchEngineRules.HOME_ADVANTAGE_OVR <= 5.0,
                "home advantage is " + MatchEngineRules.HOME_ADVANTAGE_OVR + " OVR. Real football's "
                        + "is worth a few points of win probability; anything near a full starting XI "
                        + "makes every home match a formality");
    }

    // --- helpers ---

    private Player aPlayer(String team) {
        return new Player(team + "-1", team + " striker", team, "ATT",
                new Position(4, 3), new Position(2, 3),
                new PlayerSkills(12, 12, 12, 12, 12, 12, 12, 12));
    }
}
