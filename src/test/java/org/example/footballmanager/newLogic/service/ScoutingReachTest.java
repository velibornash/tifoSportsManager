package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 5.1 — the reach model.
 *
 * <p>Only the arithmetic is unit-tested here. The validation rules need the repositories and belong
 * in a Spring test; what is worth pinning exactly here is the shape of the curve, because "reach" is
 * the only thing a manager reads on this screen and a change to it would be invisible until somebody
 * complained that scouting got stronger or weaker.
 */
class ScoutingReachTest {

    private static StaffMember scout(int scouting) {
        StaffMember s = new StaffMember();
        s.setRole(StaffRole.SCOUT);
        s.setScouting(scouting);
        return s;
    }

    private static Country country(int youthRating) {
        Country c = new Country();
        c.setYouthRating(youthRating);
        return c;
    }

    @Test
    @DisplayName("reach is the product of scout quality and country pipeline, not their sum")
    void reachIsMultiplicative() {
        int brilliantScoutWeakCountry = ScoutingService.reach(scout(20), country(1417));
        int poorScoutRichCountry = ScoutingService.reach(scout(1), country(1583));
        int bothGood = ScoutingService.reach(scout(20), country(1583));

        // A sum would let either extreme reach roughly the same place as a balanced pairing.
        // A product says neither factor rescues the other, which is the whole point.
        assertTrue(bothGood > brilliantScoutWeakCountry,
                "a good scout in a weak country must beat a brilliant scout there");
        assertTrue(bothGood > poorScoutRichCountry,
                "a good scout in a weak country must beat a poor scout in a rich one");
        assertTrue(bothGood > brilliantScoutWeakCountry + poorScoutRichCountry - 10,
                "the product must punish a lopsided pairing, got "
                        + brilliantScoutWeakCountry + " and " + poorScoutRichCountry + " vs " + bothGood);
    }

    @Test
    @DisplayName("the best possible posting lands in the high nineties, not at 100")
    void bestCaseIsStrongButNotMaximal() {
        int best = ScoutingService.reach(scout(20), country(1583));
        // youthRating 95 against a floor of 40 over a span of 60 is 0.917, so the ceiling is ~92.
        // Pinning it stops someone "fixing" the scale to reach exactly 100 and flattening the top end.
        assertEquals(92, best, "the maximum posting should sit just below 100");
        assertTrue(best < 100, "no posting should be a guaranteed certainty");
    }

    @Test
    @DisplayName("a worthless posting is worth nothing")
    void worstCaseIsZero() {
        assertEquals(0, ScoutingService.reach(scout(1), country(1400)));
    }

    @Test
    @DisplayName("reach is monotonic in both inputs")
    void reachRisesWithBothFactors() {
        int previous = -1;
        for (int scouting = 1; scouting <= 20; scouting++) {
            int reach = ScoutingService.reach(scout(scouting), country(1500));
            assertTrue(reach >= previous,
                    "a better scout must never produce less reach: " + scouting + " gave " + reach);
            previous = reach;
        }

        previous = -1;
        for (int youthRating = 40; youthRating <= 100; youthRating++) {
            int reach = ScoutingService.reach(scout(12), country(youthRating));
            assertTrue(reach >= previous,
                    "a richer pipeline must never produce less reach: " + youthRating + " gave " + reach);
            previous = reach;
        }
    }

    @Test
    @DisplayName("reach stays inside 0..100 for the whole plausible input space")
    void reachIsBounded() {
        for (int scouting = 0; scouting <= 40; scouting++) {
            for (int youthRating = 0; youthRating <= 120; youthRating++) {
                int reach = ScoutingService.reach(scout(scouting), country(youthRating));
                assertTrue(reach >= 0 && reach <= 100,
                        "reach escaped 0..100 at scouting=" + scouting + " youthRating=" + youthRating
                                + " -> " + reach);
            }
        }
    }

    @Test
    @DisplayName("an unrated scout is worth almost nothing rather than nothing at all")
    void unratedScoutDefaultsToTheFloorNotZero() {
        StaffMember unrated = scout(0);
        unrated.setScouting(null);
        int reach = ScoutingService.reach(unrated, country(1567));
        // Defaulting to 0 would make an unrated scout literally blind. The honest reading of
        // "nobody has rated him" is "barely better than blind".
        assertTrue(reach >= 1, "an unrated scout should still see something, got " + reach);
        assertTrue(reach < 20, "an unrated scout should stay near the floor, got " + reach);
    }

    @Test
    @DisplayName("a missing country rating is treated as no pipeline, not as a crash")
    void missingInputsDegradeInsteadOfThrowing() {
        assertEquals(0, ScoutingService.reach(scout(20), country(1400)));
        Country noRating = new Country();
        noRating.setYouthRating(null);
        assertTrue(ScoutingService.reach(scout(20), noRating) >= 0);
        assertTrue(ScoutingService.reach(scout(20), null) >= 0);
    }

    @Test
    @DisplayName("a mid-table posting is readable rather than a rounding error")
    void ordinaryPostingIsMeaningful() {
        // A 12-attribute scout on a 70-rated country is the median case a seeded club will actually
        // produce. If this reads as noise on the screen, the scale is wrong.
        int reach = ScoutingService.reach(scout(12), country(1500));
        assertTrue(reach >= 25 && reach <= 45,
                "an ordinary posting should land in the middle of the scale, got " + reach);
    }

    @Test
    @DisplayName("the team reference is not required to compute reach")
    void reachIgnoresTeam() {
        Team team = new Team();
        team.setName("Sremac Berkasovo");
        StaffMember s = scout(15);
        s.setTeam(team);
        assertEquals(ScoutingService.reach(scout(15), country(1533)),
                ScoutingService.reach(s, country(1533)),
                "posting the same scout with or without his club must not change his reach");
    }
}
