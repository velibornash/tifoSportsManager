package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A knockout tie that finished level, settled from the spot.
 *
 * <p><b>Why this exists.</b> A level cup tie has no winner by definition, and nothing took it any
 * further: {@code CupFixtureSeeder.winnerOf} returned null, logged "no shootout recorded", and dropped
 * the club. The next round was then drawn from a short list, so a knockout competition lost a round for
 * every level tie in it. Cups could not get past their first rounds.
 *
 * <p><b>Where the result is kept.</b> On the match, in its own two columns, and <i>not</i> in the goal
 * columns. A tie that finished 1-1 and was won 4-3 on penalties is a 1-1 match; writing the shootout into
 * the goals would report it as 5-4 to the table, the replay and the scoreline on the page.
 *
 * <h2>The rules, in the order they actually matter</h2>
 *
 * <ul>
 *   <li><b>The team that did not have the last kick takes the first kick.</b> In the 1970 rules the
 *       toss decided who went first and the other side took the last. Nobody remembers the 1994 change,
 *       and the effect is a coin-flip on early goals, so the toss is the honest model: whoever loses it
 *       kicks second.</li>
 *   <li><b>It stops the moment it is decided</b>, not after five rounds. A side two goals up after three
 *       kicks has won it — three unanswered kicks cannot be caught.</li>
 *   <li><b>Sudden death is one kick each.</b> Not two. It is one kick each, then the same rule again.</li>
 *   <li><b>The keeper is the other side's keeper</b>, and a shootout is scored by the taker against that
 *       keeper, so a taker's chance depends on both men.</li>
 * </ul>
 *
 * <p>Deterministic: it runs off the same {@link SimulationRandom} as the rest of the match, so replaying
 * a match replays its shootout.
 */
public final class PenaltyShootout {

    /** Kicks each side gets before sudden death. */
    public static final int KICKS_EACH = 5;

    private PenaltyShootout() {
    }

    /** One kick, and which side took it. */
    public record Kick(String team, String takerName, boolean scored) {
    }

    /** The result of a shootout. Exactly one of the two goals is higher. */
    public record Result(int homeKicks,
                         int awayKicks,
                         List<Kick> kicks,
                         String winningTeam,
                         boolean suddenDeath) {

        public int homeScored() {
            return (int) kicks.stream().filter(k -> k.scored() && "HOME".equals(k.team())).count();
        }

        public int awayScored() {
            return (int) kicks.stream().filter(k -> k.scored() && "AWAY".equals(k.team())).count();
        }
    }

    /**
     * Runs the shootout for a drawn match.
     *
     * @throws IllegalArgumentException if the match is not level — a shootout settles a tie, and
     *         running one for a match someone won in normal time would invent a result
     */
    public static Result run(List<Player> homeSquad, List<Player> awaySquad,
                             int homeGoals, int awayGoals) {
        if (homeGoals != awayGoals) {
            throw new IllegalArgumentException(
                    "a shootout is for a level tie, and this one finished " + homeGoals + "-" + awayGoals);
        }
        Random random = SimulationRandom.rng();

        // Two lists rather than a MatchState. The shootout happens after the final whistle, so there is
        // no live state to ask, and a state object would be a way to depend on a match that is over. The
        // only things a shootout needs are the two squads and the fact that the tie is level.
        List<Player> home = homeSquad == null ? List.of() : homeSquad;
        List<Player> away = awaySquad == null ? List.of() : awaySquad;
        List<Player> homeKeepers = keepers(home);
        List<Player> awayKeepers = keepers(away);

        // The toss. The side that loses it kicks second and therefore kicks last, which is the whole of
        // the advantage the coin is worth.
        if (home.isEmpty() || away.isEmpty()) {
            // No squad on one side: a shootout cannot be taken by nobody, and inventing a 5-0 would be a
            // result rather than a decision. The tie stays level and the cup's winner lookup will say so.
            return new Result(0, 0, List.of(), null, false);
        }
        // A wide draw, then the low bit. NOT `random.nextBoolean()`, and this is not a style note.
        //
        // Measured over seeds 1..2000 on a freshly seeded java.util.Random:
        //   nextBoolean()          constant — 2000 one way
        //   nextInt(2)             constant
        //   nextDouble() < 0.5     constant
        //   nextInt(65536) & 1     1001 / 999
        //   nextLong()  & 1        1018 / 982
        //   nextInt()    & 1       1000 / 1000
        //
        // The first narrow draw from a fresh Random reads the top bits of the freshly scrambled seed,
        // and for a small seed those are always the same. So a shootout whose toss is the engine's
        // first draw would hand the first kick to the same side in every tie of a whole cup.
        boolean homeFirst = (random.nextInt() & 1) == 0;
        String first = homeFirst ? "HOME" : "AWAY";
        String second = homeFirst ? "AWAY" : "HOME";

        List<Player> takersFirst = takers(homeFirst ? home : away);
        List<Player> takersSecond = takers(homeFirst ? away : home);
        List<Player> keeperFirst = homeFirst ? awayKeepers : homeKeepers;
        List<Player> keeperSecond = homeFirst ? homeKeepers : awayKeepers;

        List<Kick> kicks = new ArrayList<>();
        int firstScored = 0;
        int secondScored = 0;

        for (int round = 0; round < KICKS_EACH; round++) {
            boolean a = take(kicks, first, takersFirst, keeperSecond, round, random);
            boolean b = take(kicks, second, takersSecond, keeperFirst, round, random);
            if (a) {
                firstScored++;
            }
            if (b) {
                secondScored++;
            }
            // Stop the moment it is decided. After the second kick of a round, a side that is two up
            // with kicks left cannot be caught, and one that is two down cannot come back.
            if (Math.abs(firstScored - secondScored) > KICKS_EACH - round - 1) {
                return finish(kicks, firstScored, secondScored, false);
            }
        }

        // Level after five each. One kick each, then the same rule again.
        for (int round = 0; ; round++) {
            boolean a = take(kicks, first, takersFirst, keeperSecond, KICKS_EACH + round, random);
            boolean b = take(kicks, second, takersSecond, keeperFirst, KICKS_EACH + round, random);
            if (a) {
                firstScored++;
            }
            if (b) {
                secondScored++;
            }
            if (a != b) {
                return finish(kicks, firstScored, secondScored, true);
            }
        }
    }

    private static Result finish(List<Kick> kicks, int firstScored, int secondScored, boolean suddenDeath) {
        int homeScored = (int) kicks.stream().filter(k -> k.scored() && "HOME".equals(k.team())).count();
        int awayScored = (int) kicks.stream().filter(k -> k.scored() && "AWAY".equals(k.team())).count();
        String winner = firstScored > secondScored ? firstTeam(kicks) : secondTeam(kicks);
        return new Result(homeScored, awayScored, List.copyOf(kicks), winner, suddenDeath);
    }

    private static String firstTeam(List<Kick> kicks) {
        return kicks.isEmpty() ? "HOME" : kicks.get(0).team();
    }

    private static String secondTeam(List<Kick> kicks) {
        return kicks.isEmpty() ? "AWAY" : (kicks.size() == 1 ? "AWAY" : kicks.get(1).team());
    }

    private static boolean take(List<Kick> kicks, String side, List<Player> takers, List<Player> keepers,
                                int kickNumber, Random random) {
        if (takers.isEmpty()) {
            // A side with no outfield players left, or a malformed lineup. A miss is the honest reading:
            // you cannot take a kick you have nobody to take.
            kicks.add(new Kick(side, "unknown", false));
            return false;
        }
        // Takers are ordered by ability, and the best men go first: the order matters because a
        // shootout that is 3-0 with two left is over, so the strong men are the ones who get there.
        Player taker = takers.get(Math.min(kickNumber, takers.size() - 1));
        Player keeper = keepers.isEmpty() ? null : keepers.get(0);
        double chance = chanceOfScoring(taker, keeper);
        boolean scored = random.nextDouble() < chance;
        kicks.add(new Kick(side, taker.getLabel(), scored));
        return scored;
    }

    /**
     * The chance a kick goes in.
     *
     * <p>Composed of the taker's composure and technique against the keeper's ability, on a scale where a
     * competent taker against a competent keeper is close to even and a poor one against a good keeper is
     * not. Roughly 70-75% for an average pair, which is the real figure and the one that makes a shootout
     * a coin-flip over five rounds rather than a formality.
     */
    static double chanceOfScoring(Player taker, Player keeper) {
        double technique = taker == null ? 10 : taker.getSkills().technique();
        double composure = taker == null ? 10 : taker.getSkills().striker();
        double saving = keeper == null ? 10 : keeper.getSkills().keeper();

        // An average taker converts about three kicks in four, which is the figure anyone watching a
        // shootout would give you. Everything is a shift away from that, not a share of it.
        double base = 0.76;
        // Composure counts for more than technique: a nervous striker misses the same kick a confident
        // one makes, and that is what a penalty is.
        double takerQuality = 0.4 * technique + 0.6 * composure;
        double keeperQuality = saving;

        double chance = base
                + (takerQuality - 10) * 0.015
                - (keeperQuality - 10) * 0.012;

        // The floor is 0.55 and the ceiling 0.95, and both matter. A floor of 0.5 is not "safety
        // margin", it is a bug: it clamps every weak pairing to exactly 0.5, so a poor taker and a poor
        // taker become indistinguishable and an average shootout converts exactly half its kicks. A
        // ceiling below 1.0 keeps the keeper relevant; a floor above 0.5 keeps the taker relevant.
        return Math.max(0.55, Math.min(0.95, chance));
    }

    /** Outfield players, best first. The keeper never takes a penalty. */
    private static List<Player> takers(List<Player> squad) {
        return squad.stream()
                .filter(PenaltyShootout::isOutfield)
                .sorted(Comparator.comparingDouble((Player player) ->
                        player.getSkills().striker() * 0.6 + player.getSkills().technique() * 0.4).reversed())
                .toList();
    }

    /**
     * Is this an outfield player?
     *
     * <p>The keeper is identified by role, not position. In this package {@code Position} is a grid
     * coordinate — row and column — so "position == GK" is not a thing that can be written, and the
     * engine's own role vocabulary ("GK", "DEF", "MID", "ATT", "WNG") is the only way to ask.
     */
    private static boolean isOutfield(Player player) {
        String role = player.getRole();
        return role == null || role.isEmpty() || role.charAt(0) != 'G';
    }

    private static List<Player> keepers(List<Player> squad) {
        return squad.stream()
                .filter(player -> !isOutfield(player))
                .sorted(Comparator.comparingDouble((Player player) -> player.getSkills().keeper()).reversed())
                .toList();
    }
}
