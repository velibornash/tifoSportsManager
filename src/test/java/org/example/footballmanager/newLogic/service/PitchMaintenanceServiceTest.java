package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Stadium;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 2.2 — pitch wear and the maintenance programme.
 *
 * <p>The point of the system is the failure case: a club that stops paying for its pitch watches it
 * go bad. That is only a real decision if money actually fixes it and absence actually costs.
 */
class PitchMaintenanceServiceTest {

    private final PitchMaintenanceService service = new PitchMaintenanceService(null, null);

    private static Stadium pitch(int condition, double quality) {
        Stadium s = new Stadium();
        s.setName("Test Ground");
        s.setCapacity(20_000);
        s.setPitchCondition(condition);
        s.setPitchQuality(quality);
        s.setMaintenanceRemaining(0);
        return s;
    }

    @Test
    @DisplayName("playing a match wears the pitch")
    void matchesWearThePitch() {
        Stadium s = pitch(90, 80);
        service.registerMatchPlayed(s);
        assertTrue(s.getPitchCondition() < 90, "a match must cost condition");
    }

    @Test
    @DisplayName("no budget means no recovery - the failure case is the whole point")
    void noBudgetMeansNoRecovery() {
        Stadium s = pitch(70, 85);
        PitchMaintenanceService.MaintenanceResult r = service.applyWeeklyMaintenance(s, 0);
        assertTrue(r.unfunded());
        assertEquals(70, r.after(), "an unfunded week must not improve the pitch");
    }

    @Test
    @DisplayName("money fixes the pitch")
    void moneyRestoresCondition() {
        Stadium s = pitch(60, 90);
        PitchMaintenanceService.MaintenanceResult r = service.applyWeeklyMaintenance(s, 20_000);
        assertTrue(r.restored() > 0, "a funded week must improve the pitch");
        assertTrue(r.after() > r.before());
    }

    @Test
    @DisplayName("maintenance cannot lift the pitch above the quality of the surface underneath")
    void cannotExceedLongTermQuality() {
        Stadium s = pitch(95, 80);
        PitchMaintenanceService.MaintenanceResult r = service.applyWeeklyMaintenance(s, 50_000);
        assertTrue(r.after() <= 80,
                "spending cannot conjure a better surface than the ground has: " + r.after());
        assertTrue(r.wasted() > 0, "the overspend should be reported, not silently absorbed");
    }

    @Test
    @DisplayName("unspent budget carries forward, so a club can save up for a resurfacing")
    void unusedBudgetCarriesForward() {
        Stadium s = pitch(100, 100);
        service.applyWeeklyMaintenance(s, 10_000);
        int afterFirst = s.getMaintenanceRemaining();
        assertTrue(afterFirst > 0, "money not needed now is not lost");
    }

    @Test
    @DisplayName("an abandoned pitch eventually becomes critical")
    void abandonedPitchDeteriorates() {
        Stadium s = pitch(100, 90);
        for (int week = 0; week < 40; week++) {
            service.applyWeeklyMaintenance(s, 0);
            for (int m = 0; m < 2; m++) service.registerMatchPlayed(s);
        }
        assertTrue(s.getPitchCondition() <= PitchMaintenanceService.CRITICAL_PITCH_THRESHOLD,
                "40 unfunded weeks with matches on it must leave a critical pitch, got "
                        + s.getPitchCondition());
    }

    @Test
    @DisplayName("a funded club holds its surface over a season")
    void fundedClubKeepsItsSurface() {
        Stadium s = pitch(80, 88);
        for (int week = 0; week < 40; week++) {
            for (int m = 0; m < 2; m++) service.registerMatchPlayed(s);
            service.applyWeeklyMaintenance(s, 12_000);
        }
        assertTrue(s.getPitchCondition() >= 70,
                "a club paying for its pitch should not end the season on a bad surface, got "
                        + s.getPitchCondition());
    }

    @Test
    @DisplayName("a bad pitch costs the club, and that cost is reported")
    void badPitchHasACost() {
        Stadium good = pitch(90, 90);
        Stadium bad = pitch(30, 30);
        assertEquals(0.0, service.conditionPenalty(good), 0.0001, "a good pitch costs nothing");
        assertTrue(service.conditionPenalty(bad) > 0, "a bad pitch must cost something");
    }

    @Test
    @DisplayName("the absentees are told why")
    void describesThePitch() {
        assertTrue(service.describe(pitch(90, 90)).toLowerCase().contains("excellent"));
        assertTrue(service.describe(pitch(30, 30)).toLowerCase().contains("poor"));
        assertFalse(service.describe(pitch(75, 80)).isBlank());
    }
}
