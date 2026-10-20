package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.BallPhysicsEngine;
import org.tifo.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine version exists so a stored result can be attributed to the rules that produced it.
 *
 * <p>The assertion that matters is the third one: a tuning change must move the fingerprint even when
 * {@link MatchEngineVersion#VERSION} was not bumped. A version string on its own goes stale the moment
 * somebody changes a constant without it, and a stale version is worse than none — it is confidently
 * wrong. That property is not observable through {@link MatchEngineVersion#fingerprint()} alone, so
 * {@code fingerprintOf} exists as a package-private seam and is tested directly.
 */
class MatchEngineVersionTest {

    @Test
    void stampCarriesBothTheVersionAndAFingerprint() {
        String stamp = MatchEngineVersion.stamp();
        assertNotNull(stamp);
        assertTrue(stamp.startsWith(MatchEngineVersion.VERSION + "@"),
                "the stamp must name the version: " + stamp);
        String fingerprint = stamp.substring(MatchEngineVersion.VERSION.length() + 1);
        assertEquals(MatchEngineVersion.fingerprint(), fingerprint,
                "the stamp's fingerprint must be the fingerprint function, not a copy of it");
        assertEquals(12, fingerprint.length(), "six hex bytes, so it fits a log line and a column");
    }

    @Test
    void theFingerprintMovesWhenARuleMoves() {
        // The property the whole class exists for: a constant retuned without a version bump is
        // still detectable. Asserting only that fingerprint() is stable would pass against a
        // fingerprint that ignored the rules entirely.
        Map<String, String> before = new LinkedHashMap<>(Map.of(
                "decision.shotGate", "0.30",
                "ball.groundDecel", "0.08"));

        Map<String, String> retuned = new LinkedHashMap<>(before);
        retuned.put("decision.shotGate", "0.31");

        assertNotEquals(
                MatchEngineVersion.fingerprintOf("2026.10.18-1", before),
                MatchEngineVersion.fingerprintOf("2026.10.18-1", retuned),
                "a retuned gate must change the fingerprint even at the same VERSION");
    }

    @Test
    void theFingerprintMovesWhenTheVersionMoves() {
        Map<String, String> rules = Map.of("decision.shotGate", "0.30");
        assertNotEquals(
                MatchEngineVersion.fingerprintOf("2026.10.18-1", rules),
                MatchEngineVersion.fingerprintOf("2026.10.18-2", rules));
    }

    @Test
    void theDefendingRulesAreInTheFingerprint() {
        // Added 2026-10-18 after the two threat-override rules were rewritten and a match moved from
        // 41.0 shots to 34.6 without the fingerprint changing at all. The generic tests above would
        // all have stayed green, because they prove the hash FUNCTION is sensitive to rules, not
        // that the defending rules are among the rules it is fed.
        //
        // Named keys, deliberately. "assert the map is not empty" would pass against a fingerprint
        // that had forgotten every defence it ever had.
        Map<String, String> rules = MatchEngineVersion.rules();
        for (String key : new String[]{
                "defend.pressRangeA", "defend.pressRangeB", "defend.carrierSpace",
                "defend.dangerZone", "defend.offsideRetreatTicks", "defend.retreatBuffer",
                "defend.dropShare", "defend.widthKept", "defend.shiftTowardBall"}) {
            assertTrue(rules.containsKey(key),
                    "the fingerprint must know about " + key + "; it governs how a team defends "
                            + "and a match can change without the version moving");
        }
    }

    @Test
    void theOffsideRulesAreInTheFingerprint() {
        // Found 2026-10-19 (E-7). The offside rule decides whether a delivery is an offence and
        // whether a position is held for VAR, and NEITHER of its two cut-offs was in the
        // fingerprint - so a stored match could not say which offside rule produced it. The
        // decision layer's 0.5-cell hard limit was there, which is the limit on who may be AIMED
        // AT; the two numbers that decide what happens to the delivery were not.
        Map<String, String> rules = MatchEngineVersion.rules();
        for (String key : new String[]{"offside.whistleMargin", "offside.varHoldFloor"}) {
            assertTrue(rules.containsKey(key),
                    "the fingerprint must know about " + key + "; it decides whether a delivery is "
                            + "flagged and whether a position is held for VAR, and a match can change "
                            + "without the version moving");
        }
    }

    @Test
    void theOffsideNumbersInTheFingerprintAreTheLiveOnes() {
        // The fingerprint reads the constants rather than copying them. This asserts the copy did
        // not happen: a hand-written "0.30" in the rules map would drift from the constant the
        // first time anybody tuned it, and that is the failure the class exists to prevent.
        Map<String, String> rules = MatchEngineVersion.rules();
        assertEquals(String.valueOf(org.tifo.footballmanager.newLogic.sim.rules.OffsideService
                        .OFFSIDE_WHISTLE_MARGIN), rules.get("offside.whistleMargin"),
                "the fingerprint's whistle margin is not the constant");
        assertEquals(String.valueOf(org.tifo.footballmanager.newLogic.sim.rules.OffsideService
                        .OFFSIDE_VAR_HOLD_FLOOR), rules.get("offside.varHoldFloor"),
                "the fingerprint's VAR-hold floor is not the constant");
    }

    @Test
    void retuningTheDefensiveLineMovesTheFingerprint() {
        // The live version of the hole above. DROP_SHARE is the single number deciding how far a
        // back line sits from its own goal — measured, the centre-backs defend 37 m out and are
        // still 40 m from the ball when it is in their own box — so moving it moves a scoreline.
        Map<String, String> actual = MatchEngineVersion.rules();
        String realDropShare = actual.get("defend.dropShare");

        Map<String, String> retuned = new LinkedHashMap<>(actual);
        retuned.put("defend.dropShare", "0.24");

        assertNotEquals(MatchEngineVersion.fingerprint(), MatchEngineVersion.fingerprintOf(
                        MatchEngineVersion.VERSION, retuned),
                "moving the defensive drop must change the fingerprint even at the same VERSION");
        // Guard against the whole test passing for the wrong reason. Verified by deliberately
        // renaming the key in MatchEngineVersion: this assertion PASSED against a rules map that no
        // longer contained defend.dropShare at all, because a null read is not "0.0" either.
        assertNotNull(realDropShare,
                "defend.dropShare must be in the live rules map for this comparison to mean anything");
    }

    @Test
    void theFingerprintIsOrderIndependent() {
        // rules() returns a TreeMap precisely so a diff of two fingerprints is readable. If it were
        // order-dependent, adding one rule would change the hash of every rule after it.
        Map<String, String> forwards = new LinkedHashMap<>();
        forwards.put("a", "1");
        forwards.put("b", "2");
        Map<String, String> backwards = new LinkedHashMap<>();
        backwards.put("b", "2");
        backwards.put("a", "1");
        assertEquals(MatchEngineVersion.fingerprintOf("v", forwards),
                MatchEngineVersion.fingerprintOf("v", backwards));
    }

    @Test
    void theFingerprintIsStableWithinOneRun() {
        // Two matches in the same matchday must be comparable. A fingerprint that changed per call
        // would make every row its own ruleset.
        assertEquals(MatchEngineVersion.fingerprint(), MatchEngineVersion.fingerprint());
    }

    @Test
    void theRuleListHoldsTheGatesThatDecideTheFootball() {
        Map<String, String> rules = MatchEngineVersion.rules();
        assertTrue(rules.size() >= 20,
                "a ruleset of " + rules.size() + " entries cannot describe a season: " + rules.keySet());

        // These five are the numbers that decide how often each thing happens. If one of them is
        // missing, a change to it would not show up on any stored match.
        assertTrue(rules.containsKey("decision.shotGate"), rules.keySet().toString());
        assertTrue(rules.containsKey("decision.clearGate"), rules.keySet().toString());
        assertTrue(rules.containsKey("decision.thruGate"), rules.keySet().toString());
        assertTrue(rules.containsKey("decision.deliveryGate"), rules.keySet().toString());

        // And the values must be read live from the engine, not copied. If someone retunes the shot
        // gate and the copy is not updated, this fails — which is the only way a duplicated number is
        // ever caught.
        assertEquals(String.valueOf(CleanDecisionEngine.SHOT_FREQUENCY_GATE),
                rules.get("decision.shotGate"));
        assertEquals(String.valueOf(BallPhysicsEngine.GROUND_DECEL), rules.get("ball.groundDecel"));
    }

    @Test
    void aWholeNumberIsNotWrittenWithADecimalTail() {
        // Readability, not correctness: "3" instead of "3.0" keeps the log line legible and the
        // fingerprint stable if someone reformats a literal from 3.0 to 3.
        assertEquals("3", MatchEngineVersion.rules().get("home.advantageOvr"));
    }
}