package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules a fantasy squad has to satisfy, checked in one place (owner, 2026-10-17).
 *
 * <p><b>Pure, and it reports rather than refuses.</b> It takes a submission and the pool and returns every
 * violation it found, so the picker can show a manager all four things wrong with his squad instead of the
 * first one. A validator that returns a boolean forces the caller to guess, and a validator that throws on
 * the first problem makes fixing a squad a guessing game.
 *
 * <p><b>The server is the gate.</b> The picker will show the same rules as you pick, and that is a
 * courtesy — this is the check that decides. Anything that can be enforced here is enforced here, because
 * a rule that only exists in the browser is a rule that does not exist.
 *
 * <p>Every violation carries a stable {@code code} as well as a message, so the client can react to a
 * specific failure without matching on English.
 */
public final class FantasySquadValidator {

    private FantasySquadValidator() {
    }

    /** What a manager submitted for one player. */
    public record Selection(long playerId, boolean starter, boolean captain) {
    }

    /** One thing wrong with the squad. */
    public record Violation(String code, String message) {
    }

    public static final String WRONG_SIZE = "wrong_size";
    public static final String DUPLICATE_PLAYER = "duplicate_player";
    public static final String UNKNOWN_PLAYER = "unknown_player";
    public static final String WRONG_POSITION_SHAPE = "wrong_position_shape";
    public static final String WRONG_TIER_SHAPE = "wrong_tier_shape";
    public static final String TOO_MANY_FROM_CLUB = "too_many_from_club";
    public static final String OVER_BUDGET = "over_budget";
    public static final String WRONG_STARTER_COUNT = "wrong_starter_count";
    public static final String WRONG_CAPTAIN_COUNT = "wrong_captain_count";
    public static final String CAPTAIN_NOT_STARTING = "captain_not_starting";
    public static final String STARTING_MINIMUM = "starting_minimum";
    public static final String NO_RESERVE_GOALKEEPER = "no_reserve_goalkeeper";

    /**
     * Checks a whole squad and returns everything wrong with it.
     *
     * @param selections what the manager submitted. Order is preserved but not significant.
     * @param pool the country's priced pool, which is where tier, club and price come from. Tier and price
     *        are read from here rather than from the submission, so a client cannot propose its own.
     * @return every violation found, in a stable order. Empty means the squad is legal.
     */
    public static List<Violation> validate(List<Selection> selections, List<FantasyPoolPlayer> pool) {
        List<Violation> violations = new ArrayList<>();
        if (selections == null || selections.isEmpty()) {
            violations.add(new Violation(WRONG_SIZE,
                    "A squad is " + FantasyRules.SQUAD_SIZE + " players and you have none."));
            return violations;
        }

        if (selections.size() != FantasyRules.SQUAD_SIZE) {
            violations.add(new Violation(WRONG_SIZE, "A squad is exactly " + FantasyRules.SQUAD_SIZE
                    + " players and you have " + selections.size() + "."));
        }

        Map<Long, FantasyPoolPlayer> byId = new HashMap<>();
        for (FantasyPoolPlayer player : pool) {
            byId.put(player.playerId(), player);
        }

        Set<Long> seen = new LinkedHashSet<>();
        List<FantasyPoolPlayer> resolved = new ArrayList<>();
        List<Selection> resolvedSelections = new ArrayList<>();
        boolean unknown = false;
        boolean duplicate = false;
        for (Selection selection : selections) {
            if (!seen.add(selection.playerId())) {
                duplicate = true;
                continue;
            }
            FantasyPoolPlayer player = byId.get(selection.playerId());
            if (player == null) {
                unknown = true;
                continue;
            }
            resolved.add(player);
            resolvedSelections.add(selection);
        }
        if (duplicate) {
            violations.add(new Violation(DUPLICATE_PLAYER, "The same player is in the squad twice."));
        }
        if (unknown) {
            violations.add(new Violation(UNKNOWN_PLAYER,
                    "At least one player is not in this country's pyramid. Only players who play here can "
                            + "be picked."));
        }

        // Everything below is about a squad that exists, so a partial or foreign submission stops here
        // rather than producing a pile of follow-on complaints about a squad that was never legal.
        if (duplicate || unknown || resolved.size() != FantasyRules.SQUAD_SIZE) {
            return violations;
        }

        checkPositionShape(resolved, violations);
        checkTierShape(resolved, violations);
        checkClubLimit(resolved, violations);
        checkBudget(resolved, violations);
        checkCaptainAndBench(resolved, resolvedSelections, violations);
        return violations;
    }

    /** Two goalkeepers, five defenders, five midfielders, three attackers. */
    private static void checkPositionShape(List<FantasyPoolPlayer> squad, List<Violation> violations) {
        Map<Position, Integer> counts = new LinkedHashMap<>();
        for (FantasyPoolPlayer player : squad) {
            counts.merge(player.fantasyPosition(), 1, Integer::sum);
        }
        List<String> wrong = new ArrayList<>();
        for (Position position : new Position[]{Position.GK, Position.DEF, Position.MID, Position.ATT}) {
            int have = counts.getOrDefault(position, 0);
            int want = FantasyRules.squadSlots(position);
            if (have != want) {
                wrong.add(want + " " + position + ", you have " + have);
            }
        }
        if (!wrong.isEmpty()) {
            violations.add(new Violation(WRONG_POSITION_SHAPE,
                    "A squad is 2 goalkeepers, 5 defenders, 5 midfielders and 3 attackers (wingers count as "
                            + "midfielders). Wrong: " + String.join("; ", wrong) + "."));
        }
    }

    /** Exactly three players from each tier of the pyramid. This is the rule that makes the game. */
    private static void checkTierShape(List<FantasyPoolPlayer> squad, List<Violation> violations) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (FantasyPoolPlayer player : squad) {
            counts.merge(player.tier(), 1, Integer::sum);
        }
        List<String> wrong = new ArrayList<>();
        for (int tier = 1; tier <= FantasyRules.TIERS_REQUIRED; tier++) {
            int have = counts.getOrDefault(tier, 0);
            if (have != FantasyRules.PER_TIER) {
                wrong.add("tier " + tier + ": " + have + " of " + FantasyRules.PER_TIER);
            }
        }
        if (!wrong.isEmpty()) {
            violations.add(new Violation(WRONG_TIER_SHAPE,
                    "Exactly " + FantasyRules.PER_TIER + " players from every tier — that is what sends you "
                            + "scouting the lower divisions. Wrong: " + String.join("; ", wrong) + "."));
        }
    }

    /** At most two players from any one club. */
    private static void checkClubLimit(List<FantasyPoolPlayer> squad, List<Violation> violations) {
        Map<String, Integer> perClub = new LinkedHashMap<>();
        for (FantasyPoolPlayer player : squad) {
            perClub.merge(player.clubName(), 1, Integer::sum);
        }
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : perClub.entrySet()) {
            if (entry.getValue() > FantasyRules.MAX_PER_CLUB) {
                wrong.add(entry.getKey() + " (" + entry.getValue() + ")");
            }
        }
        if (!wrong.isEmpty()) {
            violations.add(new Violation(TOO_MANY_FROM_CLUB,
                    "At most " + FantasyRules.MAX_PER_CLUB + " players from one club. Over: "
                            + String.join(", ", wrong) + "."));
        }
    }

    private static void checkBudget(List<FantasyPoolPlayer> squad, List<Violation> violations) {
        double spent = totalPrice(squad);
        if (spent > FantasyPricing.BUDGET) {
            violations.add(new Violation(OVER_BUDGET, "You have spent " + round(spent) + " and the budget is "
                    + round(FantasyPricing.BUDGET) + ". You are " + round(spent - FantasyPricing.BUDGET)
                    + " over."));
        }
    }

    /** Eleven starters meeting the minimums, exactly one captain, and a genuine reserve goalkeeper. */
    private static void checkCaptainAndBench(List<FantasyPoolPlayer> squad, List<Selection> selections,
                                             List<Violation> violations) {
        List<FantasyPoolPlayer> starters = new ArrayList<>();
        int captains = 0;
        boolean captainStarting = true;
        for (int i = 0; i < squad.size(); i++) {
            Selection selection = selections.get(i);
            if (selection.captain()) {
                captains++;
                if (!selection.starter()) {
                    captainStarting = false;
                }
            }
            if (selection.starter()) {
                starters.add(squad.get(i));
            }
        }

        if (starters.size() != FantasyRules.STARTERS) {
            violations.add(new Violation(WRONG_STARTER_COUNT, "You name " + FantasyRules.STARTERS
                    + " starters and you have named " + starters.size() + "."));
        }

        if (captains != 1) {
            violations.add(new Violation(WRONG_CAPTAIN_COUNT, captains == 0
                    ? "You have not named a captain. The captain scores double."
                    : "You have named " + captains + " captains. There is one."));
        } else if (!captainStarting) {
            violations.add(new Violation(CAPTAIN_NOT_STARTING,
                    "Your captain is on the bench. He has to be in the eleven to score double."));
        }

        Map<Position, Integer> starting = new LinkedHashMap<>();
        for (FantasyPoolPlayer player : starters) {
            starting.merge(player.fantasyPosition(), 1, Integer::sum);
        }
        List<String> missing = new ArrayList<>();
        for (Position position : new Position[]{Position.GK, Position.DEF, Position.MID, Position.ATT}) {
            int have = starting.getOrDefault(position, 0);
            int need = FantasyRules.startingMinimum(position);
            if (have < need) {
                missing.add("at least " + need + " " + position + ", you have " + have);
            }
        }
        if (!missing.isEmpty()) {
            violations.add(new Violation(STARTING_MINIMUM, "Your eleven must contain " + String.join("; ", missing)
                    + "."));
        }

        // Two goalkeepers are in the squad and exactly one of them starts, so the other is the reserve.
        // Two starting means the fifteenth man is not a goalkeeper, and none starting means there is no
        // keeper on the pitch at all.
        if (starting.getOrDefault(Position.GK, 0) != 1) {
            violations.add(new Violation(NO_RESERVE_GOALKEEPER,
                    "Exactly one goalkeeper starts; the other is the reserve. Your eleven has "
                            + starting.getOrDefault(Position.GK, 0) + "."));
        }
    }

    /** What the fifteen cost, at the prices in the pool. */
    public static double totalPrice(List<FantasyPoolPlayer> squad) {
        double total = 0.0;
        for (FantasyPoolPlayer player : squad) {
            total += player.price();
        }
        return total;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
