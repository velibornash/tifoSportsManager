package org.example.footballmanager.newLogic.sim.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-MATCH-6, the engine's half: an order to change tactic during a match.
 *
 * <p>The owner's ruling, in full: *"Mogu se dodati 3 razlicite TAKTIKE. Za te 3 taktike mogu se dodati 10
 * REDOVA SA INSTRUKCIJAMA kad na koju da se predje tj 10 puta u toku utakmice igrac moze preci na drugu
 * taktiku."*
 *
 * <p>Two decisions on top of that, asked and answered: an order is **one-shot**, and when two are ready at
 * once **the last one written wins**.
 */
class TacticOrderFiresTest {

    private static final long TACTIC_A = 11L;
    private static final long TACTIC_B = 22L;
    private static final long TACTIC_C = 33L;

    @Test
    @DisplayName("nothing fires before its minute")
    void nothingBeforeTheMinute() {
        MatchTacticPlan.Order order = new MatchTacticPlan.Order(TACTIC_B,
                TacticMatchCondition.ALWAYS, 60, 1);

        assertTrue(MatchTacticPlan.orderInForce(List.of(order), 0, 59).isEmpty(),
                "an order fired before the minute it names");
        assertEquals(TACTIC_B, MatchTacticPlan.orderInForce(List.of(order), 0, 60)
                .orElseThrow().tacticId());
    }

    @Test
    @DisplayName("the condition is checked, not just the minute")
    void theConditionIsChecked() {
        MatchTacticPlan.Order whenLosing = new MatchTacticPlan.Order(TACTIC_B,
                TacticMatchCondition.TRAILING_BY_ONE, 60, 1);

        assertTrue(MatchTacticPlan.orderInForce(List.of(whenLosing), 0, 70).isEmpty(),
                "a 'trailing by one' order fired at 0-0");
        assertEquals(TACTIC_B, MatchTacticPlan.orderInForce(List.of(whenLosing), -1, 70)
                .orElseThrow().tacticId(), "a 'trailing by one' order did not fire while losing by one");
    }

    @Test
    @DisplayName("an order fires once and is then spent - the one-shot rule")
    void anOrderFiresOnlyOnce() {
        // The alternative the owner rejected: a standing order would flip the club back and forth as the
        // score moved, and ten rows would produce far more than ten switches.
        MatchTacticPlan.Order order = new MatchTacticPlan.Order(TACTIC_B,
                TacticMatchCondition.TRAILING_BY_ONE, 60, 1);

        assertEquals(TACTIC_B, MatchTacticPlan.orderInForce(List.of(order), -1, 60).orElseThrow().tacticId());
        assertFalse(order.isPending(), "the order is still waiting after it fired");

        assertTrue(MatchTacticPlan.orderInForce(List.of(order), -1, 61).isEmpty(),
                "the order fired a second time, so it is a standing rule and not a one-shot");
        assertTrue(MatchTacticPlan.orderInForce(List.of(order), -1, 89).isEmpty(),
                "the order fired again later in the match");
        assertEquals(60, order.firedAtMinute(), "the order did not record the minute it fired");
    }

    @Test
    @DisplayName("ten orders mean ten switches, not more")
    void tenOrdersMeanTenSwitches() {
        List<MatchTacticPlan.Order> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            orders.add(new MatchTacticPlan.Order((long) (100 + i), TacticMatchCondition.ALWAYS, i * 9, i + 1));
        }

        int switches = 0;
        for (int minute = 0; minute <= 90; minute++) {
            if (MatchTacticPlan.orderInForce(orders, 0, minute).isPresent()) {
                switches++;
            }
        }

        assertEquals(10, switches,
                "ten orders produced " + switches + " switches, so the cap and the semantics disagree");
    }

    @Test
    @DisplayName("the last matching order wins - the owner's ruling")
    void theLastMatchingOrderWins() {
        List<MatchTacticPlan.Order> orders = List.of(
                new MatchTacticPlan.Order(TACTIC_A, TacticMatchCondition.TRAILING_BY_ONE, 60, 1),
                new MatchTacticPlan.Order(TACTIC_C, TacticMatchCondition.TRAILING_BY_ONE, 60, 2));

        assertEquals(TACTIC_C, MatchTacticPlan.orderInForce(orders, -1, 60).orElseThrow().tacticId(),
                "the earlier order won, so the one the manager just added is ignored");
    }

    @Test
    @DisplayName("only one order fires per tick, even when several are ready")
    void oneOrderPerTick() {
        // Firing two would apply one and silently drop the other, and the manager would watch an
        // instruction disappear.
        List<MatchTacticPlan.Order> orders = List.of(
                new MatchTacticPlan.Order(TACTIC_A, TacticMatchCondition.ALWAYS, 30, 1),
                new MatchTacticPlan.Order(TACTIC_B, TacticMatchCondition.ALWAYS, 30, 2),
                new MatchTacticPlan.Order(TACTIC_C, TacticMatchCondition.ALWAYS, 30, 3));

        assertEquals(TACTIC_C, MatchTacticPlan.orderInForce(orders, 0, 30).orElseThrow().tacticId());
        assertEquals(2, orders.stream().filter(MatchTacticPlan.Order::isPending).count(),
                "more than one order was spent at the same tick");
    }

    @Test
    @DisplayName("a spent order does not outrank a later one")
    void aSpentOrderIsNotAskedAgain() {
        MatchTacticPlan.Order first = new MatchTacticPlan.Order(TACTIC_A,
                TacticMatchCondition.ALWAYS, 10, 1);
        MatchTacticPlan.Order second = new MatchTacticPlan.Order(TACTIC_B,
                TacticMatchCondition.ALWAYS, 20, 2);

        assertEquals(TACTIC_A, MatchTacticPlan.orderInForce(List.of(first, second), 0, 10)
                .orElseThrow().tacticId());
        assertEquals(TACTIC_B, MatchTacticPlan.orderInForce(List.of(first, second), 0, 20)
                .orElseThrow().tacticId());
        // Once both are spent nothing fires, and the resolver falls back to the plan's standing
        // instruction. That is the point of one-shot: a spent order cannot keep reasserting itself.
        assertTrue(MatchTacticPlan.orderInForce(List.of(first, second), 0, 40).isEmpty(),
                "a spent order fired again, so the club was told to change tactic twice");
    }

    @Test
    @DisplayName("an empty list fires nothing")
    void nothingToFire() {
        assertTrue(MatchTacticPlan.orderInForce(List.of(), 0, 45).isEmpty());
        assertTrue(MatchTacticPlan.orderInForce(null, 0, 45).isEmpty());
    }
}