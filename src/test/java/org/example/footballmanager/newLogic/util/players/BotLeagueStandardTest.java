package org.example.footballmanager.newLogic.util.players;

import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The division standards the pyramid is built on (owner, 2026-09-30).
 *
 * <p>These exist because the world was measured, not because the code was read. The old generator drew
 * every skill uniformly from 1-17 with no reference to the division, and on the live database that
 * gave tier 1 → 8.61 and tier 5 → 8.57 — five divisions of one standard, with an identical 2.9-to-14.0
 * spread in each. The number in this class is the fix, and the ordering assertions below are the part
 * that matters: a tier system where a fifth-tier club is stronger than a top-flight one is worse than no
 * tier system at all.
 */
class BotLeagueStandardTest {

    private final BotLeagueStandard standard = new BotLeagueStandard();

    @Test
    @DisplayName("tier 1 is 12 and each tier down steps by one")
    void tierStandardsAreTheOwnersNumbers() {
        assertEquals(12, standard.skillAverageForTier(1));
        assertEquals(11, standard.skillAverageForTier(2));
        assertEquals(10, standard.skillAverageForTier(3));
        // Tiers 4 and 5 continue the step. Leaving them at 10 would have put a fifth-tier side in the
        // same band as a third-tier one, which is the flattening this class exists to remove.
        assertEquals(9, standard.skillAverageForTier(4));
        assertEquals(8, standard.skillAverageForTier(5));
    }

    @Test
    @DisplayName("an out-of-range tier is clamped, not rejected")
    void tierIsClamped() {
        assertEquals(12, standard.skillAverageForTier(null), "a league with no tier plays as top flight");
        assertEquals(12, standard.skillAverageForTier(0));
        assertEquals(12, standard.skillAverageForTier(-4));
        assertEquals(8, standard.skillAverageForTier(99));
    }

    @Test
    @DisplayName("the lower the tier, the weaker the squad — on the real generator, at size")
    void lowerTiersAreWeaker() {
        Map<Integer, Double> byTier = new HashMap<>();
        for (int tier = 1; tier <= BotLeagueStandard.LOWEST_TIER; tier++) {
            byTier.put(tier, averageOfAFullSquad(tier, 400));
        }

        for (int tier = 2; tier <= BotLeagueStandard.LOWEST_TIER; tier++) {
            double higher = byTier.get(tier - 1);
            double lower = byTier.get(tier);
            assertTrue(higher > lower,
                    "tier " + (tier - 1) + " (" + higher + ") must beat tier " + tier + " (" + lower + ")");
            // A full point apart, not a hair. The old world had tier 1 at 8.61 and tier 2 at 8.71 —
            // the second tier was stronger than the first.
            assertTrue(higher - lower >= 0.8,
                    "tier " + (tier - 1) + " → " + tier + " fell only " + (higher - lower)
                            + ", which a season of results could wash out");
        }
    }

    @Test
    @DisplayName("a generated squad lands on its tier's number")
    void squadsLandOnTheStandard() {
        // Not an exact equality: the shape and the depth are deliberate, so the club is a standard and
        // not a single figure. The band is what says the generator honours the number.
        for (int tier = 1; tier <= BotLeagueStandard.LOWEST_TIER; tier++) {
            double actual = averageOfAFullSquad(tier, 600);
            int expected = standard.skillAverageForTier(tier);
            assertTrue(Math.abs(actual - expected) <= 0.6,
                    "tier " + tier + " generated " + actual + ", expected " + expected + " ± 0.6");
        }
    }

    @Test
    @DisplayName("a keeper is a keeper, and the tier average survives it")
    void aKeeperIsNotAlsoAStriker() {
        Random random = new Random(7);
        for (int attempt = 0; attempt < 40; attempt++) {
            Skills keeper = standard.skillsForTier(1, Position.GK, 0, random);
            // The point of the class: an old keeper was a 12 at striker too, so the engine had no
            // reason to prefer a real keeper and every goalie in the world could dribble.
            assertTrue(keeper.visibleInt(SkillName.GOALKEEPER) > keeper.visibleInt(SkillName.PLAYMAKER),
                    "a keeper must be better at goalkeeping than at playmaking");
            assertTrue(keeper.visibleInt(SkillName.GOALKEEPER) >= standard.TIER_ONE_AVERAGE,
                    "the top-flight tier's keeper must be at least the tier standard at his own job");
        }
    }

    @Test
    @DisplayName("a striker, a defender and a winger each read as their own job")
    void everyPositionHasItsOwnAxis() {
        Random random = new Random(11);
        assertTrue(standard.skillsForTier(3, Position.ATT, 0, random)
                .visibleInt(SkillName.STRIKER) >= standard.skillAverageForTier(3));
        assertTrue(standard.skillsForTier(3, Position.DEF, 0, random)
                .visibleInt(SkillName.DEFENDER) >= standard.skillAverageForTier(3));
        assertTrue(standard.skillsForTier(3, Position.WNG, 0, random)
                .visibleInt(SkillName.PACE) >= standard.skillAverageForTier(3));
    }

    @Test
    @DisplayName("a squad has a spine — its best men are above the standard")
    void aSquadIsNotTwentyFiveIdenticalMen() {
        Random random = new Random(3);
        int tierAverage = standard.skillAverageForTier(2);
        int clubsWithAPlayerAboveTheirOwnStandard = 0;
        int weakestClubSpine = Integer.MAX_VALUE;

        for (int club = 0; club < 30; club++) {
            int best = 0;
            for (int player = 0; player < 25; player++) {
                Skills skills = standard.skillsForTier(2, Position.values()[1 + random.nextInt(4)],
                        standard.squadOffset(random), random);
                for (SkillName skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
                    best = Math.max(best, skills.visibleInt(skill));
                }
            }
            if (best > tierAverage) {
                clubsWithAPlayerAboveTheirOwnStandard++;
            }
            weakestClubSpine = Math.min(weakestClubSpine, best);
        }

        assertEquals(30, clubsWithAPlayerAboveTheirOwnStandard,
                "every club needs someone better than its own standard, or there is no reason to pick him");
        assertTrue(weakestClubSpine >= tierAverage + 2,
                "the thinnest squad in 30 peaked at " + weakestClubSpine + " against a standard of "
                        + tierAverage + ", so its best man is barely distinguishable from its average");
    }

    @Test
    @DisplayName("every skill stays on the 1-20 scale")
    void skillsStayInRange() {
        Random random = new Random(5);
        for (int tier = 1; tier <= BotLeagueStandard.LOWEST_TIER; tier++) {
            for (int player = 0; player < 25; player++) {
                Skills skills = standard.skillsForTier(tier, Position.values()[random.nextInt(Position.values().length)],
                        standard.squadOffset(random), random);
                assertNotNull(skills);
                for (SkillName skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
                    int value = skills.visibleInt(skill);
                    assertTrue(value >= BotLeagueStandard.MIN_SKILL && value <= BotLeagueStandard.MAX_SKILL,
                            "tier " + tier + " produced " + skill + " = " + value);
                }
            }
        }
    }

    @Test
    @DisplayName("the legacy and the exact columns never disagree")
    void bothSkillColumnsAreWritten() {
        // Skills are persisted twice: a legacy int column and an exact double. `setExact` fills only
        // the second one, and `getExact` prefers it — so a generator using setExact passes every other
        // test in this class while `getSkills()` returns 0 for all 4,620 players. It was only caught
        // by querying Postgres directly, which is exactly why it is a test now.
        Random random = new Random(13);
        for (int tier = 1; tier <= BotLeagueStandard.LOWEST_TIER; tier++) {
            for (int player = 0; player < 25; player++) {
                Skills skills = standard.skillsForTier(tier,
                        Position.values()[random.nextInt(Position.values().length)],
                        standard.squadOffset(random), random);
                for (SkillName skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
                    assertEquals(skills.visibleInt(skill), skills.getSkills().get(skill),
                            "tier " + tier + " " + skill + ": the stored column and the exact value "
                                    + "disagree, so this player reads as two different players");
                }
            }
        }
    }

    @Test
    @DisplayName("a squad is deterministic for the same seed")
    void squadsAreDeterministic() {
        Skills first = standard.skillsForTier(2, Position.ATT, 1, new Random(42));
        Skills second = standard.skillsForTier(2, Position.ATT, 1, new Random(42));

        for (SkillName skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
            assertEquals(first.visibleInt(skill), second.visibleInt(skill),
                    skill + " changed between two identical draws — a side that re-rolls on boot "
                            + "is impossible to debug");
        }
    }

    /** The mean of the eight football skills across a whole 25-man squad. */
    private double averageOfAFullSquad(int tier, int seed) {
        Random random = new Random(seed);
        double total = 0.0;
        for (int player = 0; player < 25; player++) {
            Skills skills = standard.skillsForTier(tier, Position.values()[random.nextInt(Position.values().length)],
                    standard.squadOffset(random), random);
            int sum = 0;
            for (SkillName skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
                sum += skills.visibleInt(skill);
            }
            total += sum / (double) BotLeagueStandard.FOOTBALL_SKILLS.size();
        }
        return Math.round(total / 25.0 * 100.0) / 100.0;
    }
}
