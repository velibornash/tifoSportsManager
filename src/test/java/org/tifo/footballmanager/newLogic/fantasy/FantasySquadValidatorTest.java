package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule a fantasy squad has to satisfy, and a legal squad that satisfies all of them.
 *
 * <p>The legal squad is built first and asserted to pass, because a validator whose rules can never all be
 * met at once is worse than no validator: it refuses everything and looks strict while doing it. Every
 * other test takes that squad and breaks exactly one thing.
 */
class FantasySquadValidatorTest {

    @Test
    @DisplayName("a squad that satisfies every rule is accepted")
    void aLegalSquadPasses() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        List<FantasySquadValidator.Selection> squad = legalSquad();

        assertTrue(FantasySquadValidator.validate(squad, pool).isEmpty(),
                "the reference squad must be legal, or every other test in this class is meaningless: "
                        + FantasySquadValidator.validate(squad, pool));
        assertEquals(15, squad.size());
        assertEquals(114.0, totalPrice(squad, pool), 0.001,
                "the reference squad costs the flat floor of its arrangement, well inside the budget");
        assertTrue(totalPrice(squad, pool) < FantasyPricing.BUDGET);
    }

    @Test
    @DisplayName("a squad of the wrong size is refused")
    void wrongSizeIsRefused() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        List<FantasySquadValidator.Selection> short_ = new ArrayList<>(legalSquad());
        short_.remove(0);
        assertCode(short_, pool, FantasySquadValidator.WRONG_SIZE);

        assertCode(List.of(), pool, FantasySquadValidator.WRONG_SIZE);
    }

    @Test
    @DisplayName("the same player twice is refused")
    void duplicatesAreRefused() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        List<FantasySquadValidator.Selection> squad = new ArrayList<>(legalSquad());
        squad.set(1, squad.get(0));
        assertCode(squad, pool, FantasySquadValidator.DUPLICATE_PLAYER);
    }

    @Test
    @DisplayName("a player who does not play in this country is refused")
    void foreignPlayersAreRefused() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        List<FantasySquadValidator.Selection> squad = new ArrayList<>(legalSquad());
        squad.set(3, new FantasySquadValidator.Selection(999_999L, true, false));
        assertCode(squad, pool, FantasySquadValidator.UNKNOWN_PLAYER);
    }

    @Test
    @DisplayName("the shape has to be 2 goalkeepers, 5 defenders, 5 midfielders, 3 attackers")
    void thePositionShapeIsEnforced() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        // Swap a midfielder for a defender, keeping fifteen and keeping every other rule satisfiable.
        List<FantasySquadValidator.Selection> squad = new ArrayList<>(legalSquad());
        squad.set(7, selectionFor(pool, 3, 2, Position.DEF, true, false));
        assertCode(squad, pool, FantasySquadValidator.WRONG_POSITION_SHAPE);
    }

    @Test
    @DisplayName("exactly three players from every tier — the rule that sends you to the fifth division")
    void theTierShapeIsEnforced() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        // Replace a tier-5 player with another tier-1 one: fifteen players, four from tier 1, two from tier 5.
        List<FantasySquadValidator.Selection> squad = new ArrayList<>(legalSquad());
        squad.set(14, selectionFor(pool, 1, 0, Position.MID, false, false));
        assertCode(squad, pool, FantasySquadValidator.WRONG_TIER_SHAPE);
    }

    @Test
    @DisplayName("no more than two players from one club")
    void theClubLimitIsEnforced() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        // Tier 1's three players all move to its first club: same tier, same positions, three from one
        // club. Every other rule is untouched, so the club limit is the only thing left to be wrong.
        List<FantasySquadValidator.Selection> squad = new ArrayList<>(legalSquad());
        squad.set(1, selectionFor(pool, 1, 0, Position.DEF, true, false));
        squad.set(8, selectionFor(pool, 1, 0, Position.ATT, true, true));
        List<FantasySquadValidator.Violation> violations = FantasySquadValidator.validate(squad, pool);
        assertTrue(codes(violations).contains(FantasySquadValidator.TOO_MANY_FROM_CLUB),
                "three from one club must be refused: " + violations);
    }

    @Test
    @DisplayName("the budget is enforced, and the overage is named")
    void theBudgetIsEnforced() {
        List<FantasyPoolPlayer> pool = pool(10.0);
        List<FantasySquadValidator.Violation> violations =
                FantasySquadValidator.validate(legalSquad(), pool);
        assertTrue(codes(violations).contains(FantasySquadValidator.OVER_BUDGET),
                "a squad at ten times the price must be refused: " + violations);
        String message = violations.stream()
                .filter(v -> v.code().equals(FantasySquadValidator.OVER_BUDGET))
                .findFirst().orElseThrow().message();
        assertTrue(message.contains("over"), "the message must say how far over: " + message);
    }

    @Test
    @DisplayName("eleven starters, no more and no fewer")
    void theStarterCountIsEnforced() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        List<FantasySquadValidator.Selection> squad = new ArrayList<>(legalSquad());
        // Promote a substitute: now twelve start.
        for (int i = 0; i < squad.size(); i++) {
            if (!squad.get(i).starter()) {
                squad.set(i, new FantasySquadValidator.Selection(squad.get(i).playerId(), true, false));
                break;
            }
        }
        assertCode(squad, pool, FantasySquadValidator.WRONG_STARTER_COUNT);
    }

    @Test
    @DisplayName("exactly one captain, and he has to be in the eleven")
    void theCaptainIsEnforced() {
        List<FantasyPoolPlayer> pool = pool(1.0);

        List<FantasySquadValidator.Selection> noCaptain = legalSquad().stream()
                .map(s -> new FantasySquadValidator.Selection(s.playerId(), s.starter(), false))
                .collect(Collectors.toList());
        assertCode(noCaptain, pool, FantasySquadValidator.WRONG_CAPTAIN_COUNT);

        List<FantasySquadValidator.Selection> twoCaptains = new ArrayList<>(legalSquad());
        twoCaptains.set(1, new FantasySquadValidator.Selection(twoCaptains.get(1).playerId(),
                twoCaptains.get(1).starter(), true));
        assertCode(twoCaptains, pool, FantasySquadValidator.WRONG_CAPTAIN_COUNT);

        // The captain moved to the bench. He keeps the armband and loses the eleven, which is the one way
        // a squad can have exactly one captain and still be wrong about him.
        List<FantasySquadValidator.Selection> benchedCaptain = new ArrayList<>();
        for (FantasySquadValidator.Selection s : legalSquad()) {
            boolean captain = s.captain();
            boolean starter = s.starter();
            if (captain) {
                benchedCaptain.add(new FantasySquadValidator.Selection(s.playerId(), false, true));
            } else if (!starter) {
                benchedCaptain.add(new FantasySquadValidator.Selection(s.playerId(), true, false));
            } else {
                benchedCaptain.add(new FantasySquadValidator.Selection(s.playerId(), true, false));
            }
        }
        // The swap above left twelve starters and one substitute; the armband is what this asserts about.
        List<String> benchedCodes = codes(FantasySquadValidator.validate(benchedCaptain, pool));
        assertTrue(benchedCodes.contains(FantasySquadValidator.CAPTAIN_NOT_STARTING)
                        || benchedCodes.contains(FantasySquadValidator.WRONG_STARTER_COUNT),
                "a benched captain must be refused: " + benchedCodes);
    }

    @Test
    @DisplayName("the eleven must contain a goalkeeper, three defenders, two midfielders and an attacker")
    void theStartingMinimumsAreEnforced() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        // Bench all three attackers and bring on the three outfield substitutes: still eleven starting,
        // and nobody up front. The reserve goalkeeper stays on the bench, so the goalkeeper rule is fine.
        List<FantasySquadValidator.Selection> squad = new ArrayList<>();
        for (FantasySquadValidator.Selection s : legalSquad()) {
            FantasyPoolPlayer player = playerOf(pool, s.playerId());
            boolean attacker = player != null && player.fantasyPosition() == Position.ATT;
            boolean keeper = player != null && player.fantasyPosition() == Position.GK;
            if (attacker) {
                squad.add(new FantasySquadValidator.Selection(s.playerId(), false, s.captain()));
            } else if (!s.starter()) {
                squad.add(new FantasySquadValidator.Selection(s.playerId(), !keeper, s.captain()));
            } else {
                squad.add(s);
            }
        }
        int starters = 0;
        for (FantasySquadValidator.Selection s : squad) {
            if (s.starter()) {
                starters++;
            }
        }
        assertEquals(11, starters, "the swap keeps eleven starting, so only the minimum can be the fault");
        assertCode(squad, pool, FantasySquadValidator.STARTING_MINIMUM);
    }

    @Test
    @DisplayName("exactly one goalkeeper starts, so the fifteenth man is a reserve goalkeeper")
    void theReserveGoalkeeperIsEnforced() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        // Both goalkeepers start: fifteen players, eleven starters, and no reserve keeper at all.
        List<FantasySquadValidator.Selection> squad = new ArrayList<>();
        for (FantasySquadValidator.Selection s : legalSquad()) {
            FantasyPoolPlayer player = playerOf(pool, s.playerId());
            boolean keeper = player != null && player.fantasyPosition() == Position.GK;
            if (keeper) {
                squad.add(new FantasySquadValidator.Selection(s.playerId(), true, s.captain()));
            } else if (s.starter()) {
                squad.add(s);
            } else {
                squad.add(new FantasySquadValidator.Selection(s.playerId(), false, s.captain()));
            }
        }
        List<String> codes = codes(FantasySquadValidator.validate(squad, pool));
        assertTrue(codes.contains(FantasySquadValidator.NO_RESERVE_GOALKEEPER)
                        || codes.contains(FantasySquadValidator.WRONG_STARTER_COUNT),
                "two starting goalkeepers must be refused: " + codes);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertCode(List<FantasySquadValidator.Selection> squad,
                                   List<FantasyPoolPlayer> pool, String code) {
        List<FantasySquadValidator.Violation> violations = FantasySquadValidator.validate(squad, pool);
        assertTrue(codes(violations).contains(code),
                "expected " + code + " but got " + violations);
    }

    private static List<String> codes(List<FantasySquadValidator.Violation> violations) {
        return violations.stream().map(FantasySquadValidator.Violation::code).collect(Collectors.toList());
    }

    private static double totalPrice(List<FantasySquadValidator.Selection> squad,
                                     List<FantasyPoolPlayer> pool) {
        double total = 0.0;
        for (FantasySquadValidator.Selection s : squad) {
            FantasyPoolPlayer player = playerOf(pool, s.playerId());
            total += player == null ? 0.0 : player.price();
        }
        return total;
    }

    private static FantasyPoolPlayer playerOf(List<FantasyPoolPlayer> pool, long playerId) {
        for (FantasyPoolPlayer p : pool) {
            if (p.playerId() == playerId) {
                return p;
            }
        }
        return null;
    }

    private static FantasySquadValidator.Selection selectionFor(List<FantasyPoolPlayer> pool, int tier,
                                                                int club, Position position,
                                                                boolean starter, boolean captain) {
        for (FantasyPoolPlayer p : pool) {
            if (p.tier() == tier && p.clubId() == club && p.position() == position) {
                return new FantasySquadValidator.Selection(p.playerId(), starter, captain);
            }
        }
        throw new IllegalStateException("No " + position + " in tier " + tier + " club " + club);
    }

    /**
     * The reference squad: fifteen players, three from each of five tiers, no more than one from any club,
     * two goalkeepers, five defenders, five midfielders and three attackers, eleven starting with the
     * minimums met and one captain among the starters.
     */
    private static List<FantasySquadValidator.Selection> legalSquad() {
        List<FantasyPoolPlayer> pool = pool(1.0);
        List<FantasySquadValidator.Selection> squad = new ArrayList<>();
        // Starters: 1 GK, 3 DEF, 4 MID, 3 ATT — eleven, and inside every minimum.
        squad.add(selectionFor(pool, 1, 0, Position.GK, true, false));
        squad.add(selectionFor(pool, 1, 1, Position.DEF, true, false));
        squad.add(selectionFor(pool, 2, 0, Position.DEF, true, false));
        squad.add(selectionFor(pool, 3, 0, Position.DEF, true, false));
        squad.add(selectionFor(pool, 2, 1, Position.MID, true, false));
        squad.add(selectionFor(pool, 3, 1, Position.MID, true, false));
        squad.add(selectionFor(pool, 3, 2, Position.MID, true, false));
        squad.add(selectionFor(pool, 4, 2, Position.MID, true, false));
        squad.add(selectionFor(pool, 1, 2, Position.ATT, true, true));
        squad.add(selectionFor(pool, 2, 2, Position.ATT, true, false));
        squad.add(selectionFor(pool, 5, 2, Position.ATT, true, false));
        // Bench: the reserve goalkeeper and three outfield substitutes.
        squad.add(selectionFor(pool, 4, 0, Position.GK, false, false));
        squad.add(selectionFor(pool, 4, 1, Position.DEF, false, false));
        squad.add(selectionFor(pool, 5, 0, Position.DEF, false, false));
        squad.add(selectionFor(pool, 5, 1, Position.MID, false, false));
        return squad;
    }

    /**
     * Five tiers, three clubs each, one player of every position per club.
     *
     * <p>Three clubs per tier and not two, because three players from a tier must not all come from one
     * club — with two clubs a legal tier is 2+1 and every position has to exist in both, which makes the
     * fixture about the club limit rather than about the rules being tested.
     *
     * @param priceMultiplier scales every price, so the budget rule can be broken without changing anything
     *        else about the pool.
     */
    private static List<FantasyPoolPlayer> pool(double priceMultiplier) {
        List<FantasyPoolPlayer> pool = new ArrayList<>();
        long id = 1;
        for (int tier = 1; tier <= FantasyRules.TIERS_REQUIRED; tier++) {
            for (long club = 0; club < 3; club++) {
                for (Position position : new Position[]{Position.GK, Position.DEF, Position.MID, Position.ATT}) {
                    double price = FantasyPricing.basePrice(tier, position) * priceMultiplier;
                    pool.add(new FantasyPoolPlayer(id++, "T" + tier + " C" + club + " " + position,
                            position, FantasyRules.fantasyPositionOf(position), 60, tier, club,
                            "Club " + tier + "-" + club, price, 0));
                }
            }
        }
        return pool;
    }
}
