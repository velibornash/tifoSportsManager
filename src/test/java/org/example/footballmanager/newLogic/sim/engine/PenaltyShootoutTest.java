package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A knockout tie settled from the spot (owner, 2026-09-30).
 *
 * <p>Nothing took a shootout anywhere in the game, so a level cup tie had no winner, the winner lookup
 * returned null, and the club dropped out of the competition. Every level tie cost a knockout round a
 * team. These tests are about the <i>rules</i>, because "it produces a winner" is a much weaker thing
 * to verify and a shootout with the wrong rules still produces a winner.
 */
class PenaltyShootoutTest {

    @Test
    @DisplayName("a shootout is only for a level tie")
    void refusesAMatchSomebodyWon() {
        List<Player> home = squad("HOME", 10);
        List<Player> away = squad("AWAY", 10);

        // A shootout settles a tie. Running one for a match that ended 2-1 would overwrite a real result
        // with a coin toss, which is the failure mode a shootout makes possible and the reason the guard
        // is here rather than left to the caller.
        assertThrows(IllegalArgumentException.class,
                () -> PenaltyShootout.run(home, away, 2, 1));
    }

    @Test
    @DisplayName("a shootout always produces a winner, and never a draw")
    void thereIsAlwaysAWinner() {
        for (int seed = 0; seed < 400; seed++) {
            SimulationRandom.seed(seed);
            PenaltyShootout.Result result = PenaltyShootout.run(squad("HOME", 10), squad("AWAY", 10), 1, 1);

            assertNotNull(result.winningTeam(), "seed " + seed + ": no winner");
            assertNotEquals(result.homeScored(), result.awayScored(),
                    "seed " + seed + ": the shootout finished level");
            String expected = result.homeScored() > result.awayScored() ? "HOME" : "AWAY";
            assertEquals(expected, result.winningTeam(),
                    "seed " + seed + ": the winner does not match the kick count");
        }
    }

    @Test
    @DisplayName("a shootout is five kicks each, then sudden death")
    void theShootoutHasTheRightNumberOfKicks() {
        int suddenDeathRuns = 0;
        int decidedInRegulation = 0;

        for (int seed = 0; seed < 400; seed++) {
            SimulationRandom.seed(seed);
            PenaltyShootout.Result result = PenaltyShootout.run(squad("HOME", 10), squad("AWAY", 10), 0, 0);
            if (result.suddenDeath()) {
                suddenDeathRuns++;
            } else {
                decidedInRegulation++;
            }
            // At most ten: five each, and fewer when it is decided early. My first version asserted at
            // least ten, which is the same mistake as a shootout that keeps kicking after the result —
            // it assumes the full five rounds are always taken.
            assertTrue(result.kicks().size() <= 10 || result.suddenDeath(),
                    "seed " + seed + ": " + result.kicks().size() + " kicks and no sudden death");
            assertEquals(0, result.kicks().size() % 2,
                    "seed " + seed + ": kicks came in pairs of one, so one side kicked twice");
            long homeKicks = result.kicks().stream().filter(k -> "HOME".equals(k.team())).count();
            long awayKicks = result.kicks().stream().filter(k -> "AWAY".equals(k.team())).count();
            assertTrue(Math.abs(homeKicks - awayKicks) <= 1,
                    "seed " + seed + ": the sides did not alternate, " + homeKicks + " against " + awayKicks);
        }

        // Both outcomes have to happen, or one of the branches is dead code. A real shootout reaches
        // sudden death roughly a quarter of the time.
        assertTrue(suddenDeathRuns > 20, "only " + suddenDeathRuns + " of 400 went to sudden death");
        assertTrue(decidedInRegulation > 200,
                "only " + decidedInRegulation + " of 400 were decided in ninety minutes");
    }

    @Test
    @DisplayName("it stops the moment the tie is decided, rather than after five rounds")
    void itStopsWhenDecided() {
        // A side three unanswered kicks up with two left cannot be caught, so the shootout is over. The
        // bug this catches is a shootout that keeps kicking after the result and then hands the winner
        // to whoever happened to be ahead at the end of five.
        int earlyFinishes = 0;
        for (int seed = 0; seed < 400; seed++) {
            SimulationRandom.seed(seed);
            PenaltyShootout.Result result = PenaltyShootout.run(squad("HOME", 10), squad("AWAY", 10), 0, 0);
            if (!result.suddenDeath() && result.kicks().size() < 10) {
                earlyFinishes++;
            }
        }
        assertTrue(earlyFinishes > 50,
                "only " + earlyFinishes + " of 400 shootouts stopped early, so the early-termination rule "
                        + "is not firing");
    }

    @Test
    @DisplayName("the side that loses the toss kicks first, and so kicks last")
    void theTossDecidesWhoGoesFirst() {
        // First kick means last kick under the 1994 rules, so the coin is worth something. Both orders
        // have to occur or the toss is not being made.
        boolean homeFirstSeen = false;
        boolean awayFirstSeen = false;
        for (int seed = 0; seed < 50; seed++) {
            SimulationRandom.seed(seed);
            PenaltyShootout.Result result = PenaltyShootout.run(squad("HOME", 10), squad("AWAY", 10), 0, 0);
            String first = result.kicks().get(0).team();
            if ("HOME".equals(first)) {
                homeFirstSeen = true;
            } else {
                awayFirstSeen = true;
            }
        }
        assertTrue(homeFirstSeen && awayFirstSeen, "the toss always came out the same way");
    }

    @Test
    @DisplayName("a keeper does not take a penalty")
    void theKeeperNeverTakes() {
        SimulationRandom.seed(4);
        PenaltyShootout.Result result = PenaltyShootout.run(squad("HOME", 10), squad("AWAY", 10), 0, 0);

        List<String> takers = result.kicks().stream().map(PenaltyShootout.Kick::takerName).toList();
        // The squad builder numbers players, and player 1 is the keeper on both sides.
        assertFalse(takers.contains("HOME GK"), "the keeper took a penalty");
        assertFalse(takers.contains("AWAY GK"), "the keeper took a penalty");
    }

    @Test
    @DisplayName("the best men take first, because a 3-0 with two left is over")
    void theStrongestTakeFirst() {
        List<Player> home = squad("HOME", 10);
        SimulationRandom.seed(9);
        PenaltyShootout.Result result = PenaltyShootout.run(home, squad("AWAY", 10), 0, 0);

        String firstTaker = result.kicks().stream()
                .filter(k -> "HOME".equals(k.team()))
                .findFirst()
                .orElseThrow()
                .takerName();
        assertTrue(firstTaker.contains("P2") || firstTaker.contains("P3"),
                "the first penalty was taken by " + firstTaker + " rather than the best men available");
    }

    @Test
    @DisplayName("a better taker and a worse keeper raise the chance of scoring")
    void theChanceComesFromBothMen() {
        Player goodTaker = player("T", "MID", 18, 18);
        Player poorTaker = player("T", "MID", 4, 4);
        Player goodKeeper = player("K", "GK", 18, 18);
        Player poorKeeper = player("K", "GK", 3, 3);

        assertTrue(PenaltyShootout.chanceOfScoring(goodTaker, goodKeeper)
                        > PenaltyShootout.chanceOfScoring(poorTaker, goodKeeper),
                "a better taker does not score more often against the same keeper");
        assertTrue(PenaltyShootout.chanceOfScoring(goodTaker, poorKeeper)
                        > PenaltyShootout.chanceOfScoring(goodTaker, goodKeeper),
                "a better keeper does not save more often against the same taker");
    }

    @Test
    @DisplayName("no penalty is ever certain, either way")
    void theChanceStaysOnBothSidesOfCertain() {
        // A chance of 1.0 means the shootout is a formality and 0.0 means it is a coin toss that the
        // better players cannot influence. Real shootouts are about 70-75%.
        Player bestTaker = player("T", "ATT", 20, 20);
        Player worstKeeper = player("K", "GK", 1, 1);
        double best = PenaltyShootout.chanceOfScoring(bestTaker, worstKeeper);
        double worst = PenaltyShootout.chanceOfScoring(worstKeeper, worstKeeper);

        assertTrue(best < 1.0, "the best taker against the worst keeper is certain to score: " + best);
        assertTrue(worst > 0.0, "nothing can ever be missed, so the keeper does not matter: " + worst);
    }

    @Test
    @DisplayName("an average pair converts about three kicks in four")
    void anAverageShootoutIsNotACoinFlip() {
        // Five kicks at 75% is 3-2 or 4-3 most of the time. A model that converts half its kicks makes
        // every shootout 5-5 and every one of them a sudden death.
        int scored = 0;
        int taken = 0;
        for (int seed = 0; seed < 400; seed++) {
            SimulationRandom.seed(seed);
            PenaltyShootout.Result result = PenaltyShootout.run(squad("HOME", 10), squad("AWAY", 10), 0, 0);
            taken += result.kicks().size();
            scored += result.homeScored() + result.awayScored();
        }
        double rate = scored / (double) taken;
        assertTrue(rate > 0.62 && rate < 0.85,
                "an average shootout converted " + Math.round(rate * 100) + "%, which is not a shootout "
                        + "anyone has watched");
    }

    @Test
    @DisplayName("a side with no squad cannot be decided, and says so")
    void noSquadMeansNoResult() {
        SimulationRandom.seed(1);
        PenaltyShootout.Result result = PenaltyShootout.run(List.of(), squad("AWAY", 10), 0, 0);

        assertNull(result.winningTeam(),
                "a shootout with nobody on one side produced a winner, which is a coin toss wearing a "
                        + "result's clothes");
    }

    // --- helpers ---

    private static List<Player> squad(String side, int outfielders) {
        List<Player> squad = new ArrayList<>();
        squad.add(player(side + " GK", "GK", 14, 12));
        for (int i = 0; i < outfielders; i++) {
            squad.add(player(side + " P" + (i + 2), "MID", 10, 10));
        }
        return squad;
    }

    private static Player player(String label, String role, double striker, double keeper) {
        // alternativePosition is requireNonNull, so a player cannot be built without one. A shootout has
        // no use for it, which is a fair sign that the engine's player wants an optional field it never
        // reads — but changing the constructor's contract is not this test's business.
        return new Player(label, label, label.startsWith("HOME") ? "HOME" : "AWAY", role,
                new Position(4.5, 3.5), new Position(4.5, 3.5),
                new PlayerSkills(10, 10, keeper, 10, 10, 10, striker, 10),
                180);
    }
}
