package org.example.footballmanager.newLogic.sim.tactics;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.sim.RealSquadFactory;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A role the tactic does not name must not be teleported onto a hardcoded cell.
 *
 * <p><b>This is a regression guard for a defect that step 3 made reachable.</b> Until a club's formation
 * decided its own role keys, every player in the world wore one of 4-4-2's eleven and the rules were
 * 4-4-2, so "a role these rules do not name" could not happen. It can now: a 4-3-3 club facing a
 * 4-4-2's rules asks about {@code CM}, {@code WL}, {@code WR} and {@code ST}.
 *
 * <p>Both the miss paths used to return a hardcoded {@code new Position(1.5, 3.5)} — the goalkeeper's
 * own-half corner. **Nine outfielders on one square metre of pitch**, which is not a subtle wrong
 * answer: it would have shown up as a wall of players and unreadable replays.
 *
 * <p>The contract now is that "no rule and no anchor" returns <b>null</b>, and every caller falls back
 * to the player's own position — which {@code RealSquadFactory} has already placed from his own
 * formation's anchors.
 */
class UnnamedRoleFallsBackTest extends BaseTest {

    /** 4-4-2's rules, as the owner's export holds them. */
    private static final String[] SLOTS_442 = RealSquadFactory.SLOT_ORDER;

    private static String rulesJsonFor(String[] slots, String ballStateKey) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String slot : slots) {
            for (String context : new String[]{"WE_HAVE_BALL", "OPPONENT_HAS_BALL"}) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"slotKey\":\"").append(slot)
                    .append("\",\"ballStateKey\":\"").append(ballStateKey)
                    .append("\",\"possessionContext\":\"").append(context)
                    .append("\",\"targetCellKey\":\"CELL_2_3\"}");
            }
        }
        return json.append(']').toString();
    }

    @Test
    @DisplayName("a role these rules do not name has no target, rather than the goalkeeper's cell")
    void anUnnamedRoleHasNoTarget() {
        TacticsRules rules = TacticsRules.fromProfileJson(
                rulesJsonFor(SLOTS_442, "CELL_2_3"), "4-4-2", "test");

        assertNotNull(rules, "the 4-4-2 profile did not load");
        // A ball at 3.5, 4.5 is the CELL_2_3 these rules cover.
        Position ball = new Position(3.5, 4.5);

        assertNotNull(rules.desiredCell("CMR", ball, "HOME"),
                "4-4-2 does have CMR and the profile covers this ball state, so this must resolve");

        assertNull(rules.desiredCell("CM", ball, "HOME"),
                "a 4-3-3 holding midfielder asked a 4-4-2's rules and got an answer. It used to get "
                        + "(1.5, 3.5) — the goalkeeper's own-half corner — which would have stacked "
                        + "every unmentioned outfielder of the other side onto one square metre.");

        assertNull(rules.anchorCell("WL", "HOME"),
                "the anchor lookup had the same hardcoded fallback and now answers honestly.");
    }

    @Test
    @DisplayName("a role the rules do name is unaffected — the null is only for misses")
    void aNamedRoleIsUnaffected() {
        TacticsRules rules = TacticsRules.fromProfileJson(
                rulesJsonFor(SLOTS_442, "CELL_2_3"), "4-4-2", "test");
        Position ball = new Position(3.5, 4.5);

        for (String slot : SLOTS_442) {
            assertNotNull(rules.desiredCell(slot, ball, "HOME"),
                    slot + " is one of the eleven 4-4-2 roles and the profile has a rule for this ball "
                            + "state, so it must resolve. If this fails the null is leaking.");
            assertNotNull(rules.anchorCell(slot, "HOME"),
                    slot + " has an anchor in the 4-4-2 catalog and must resolve");
        }
    }

    @Test
    @DisplayName("every role a 4-3-3 side wears is either answered or honestly absent")
    void a433SideIsEitherAnsweredOrAbsent() {
        // The match this exists for: a 4-3-3 club's eleven against a 4-4-2's rules. Nothing may be sent
        // to a cell the rules never mentioned.
        TacticsRules rules = TacticsRules.fromProfileJson(
                rulesJsonFor(SLOTS_442, "CELL_2_3"), "4-4-2", "test");
        String[] roles433 = RealSquadFactory.slotOrderFor("4-3-3");
        Position ball = new Position(3.5, 4.5);

        Set<String> named442 = Arrays.stream(SLOTS_442).collect(Collectors.toCollection(LinkedHashSet::new));
        int absent = 0;
        for (String role : roles433) {
            Position target = rules.desiredCell(role, ball, "AWAY");
            if (target == null) {
                absent++;
                continue;
            }
            assertTrue(named442.contains(role),
                    role + " is not a 4-4-2 role but the rules produced a target for it, so something "
                            + "is answering for a role it does not have");
        }
        assertTrue(absent > 0,
                "none of the 4-3-3 roles was absent, so this test is not exercising the miss path. The "
                        + "two vocabularies must differ — that is the whole reason the regression was "
                        + "possible.");
    }
}
