package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 4.4 — training facilities.
 *
 * <p>Two failure modes are being guarded against. The first is the one this sprint has hit three
 * times: a field that exists, costs nothing and does nothing. The second is its mirror — a field that
 * works and is free, which is a hole in the budget rather than a feature. A test that only checked
 * "level 20 grows the player faster" would pass against both.
 */
class TrainingFacilityServiceTest {

    private final StadiumRepository stadiums = mock(StadiumRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TrainingFacilityService service = new TrainingFacilityService(stadiums, teams);

    private static Stadium ground(Integer training, Integer gym, Integer tactical) {
        Stadium s = new Stadium();
        s.setId(1L);
        s.setName("Ground");
        s.setCapacity(20_000);
        s.setTrainingQuality(training);
        s.setGymLevel(gym);
        s.setTacticalLevel(tactical);
        return s;
    }

    private static Team clubWith(Stadium stadium, double budget) {
        Team t = new Team();
        t.setId(7L);
        t.setName("Club");
        t.setBudget(budget);
        t.setStadium(stadium);
        return t;
    }

    @Test
    @DisplayName("A club with nothing recorded has no facilities, not average ones")
    void unrecordedMeansLevelOne() {
        Stadium s = ground(null, null, null);
        assertEquals(1, service.levels(clubWith(s, 0)).get(TrainingFacilityService.Facility.GROUND));
        assertEquals(1, service.levels(clubWith(s, 0)).get(TrainingFacilityService.Facility.GYM));
        assertEquals(0.0, service.weeklyUpkeep(clubWith(s, 0)),
                "a club with no gym was billed for one");
    }

    @Test
    @DisplayName("The right facility answers for the right work")
    void theRelevantFacilityIsTheOneThatCounts() {
        Stadium s = ground(/*training*/ 20, /*gym*/ 1, /*tactical*/ 1);

        // Conditioning is a gym question, and this club has no gym.
        assertTrue(s.trainingFactorFor(SkillName.STAMINA) < s.trainingFactorFor(SkillName.STRIKER),
                "a club with a superb ground and no gym grew the striker and the stamina man alike");

        Stadium gymOnly = ground(1, 20, 1);
        assertTrue(gymOnly.trainingFactorFor(SkillName.STAMINA) > gymOnly.trainingFactorFor(SkillName.STRIKER),
                "a superb gym did nothing for stamina work");
    }

    @Test
    @DisplayName("Facilities help, and bad ones waste the week")
    void facilitiesAreSymmetricAroundNeutral() {
        assertTrue(ground(20, 20, 20).trainingFactorFor(SkillName.STRIKER) > 1.0,
                "elite facilities did not help");
        assertTrue(ground(1, 1, 1).trainingFactorFor(SkillName.STRIKER) < 1.0,
                "a bare field did not waste the week - a game where the floor is 1.0 would say it is free");
        assertEquals(1.0, ground(null, null, null).trainingFactorFor(SkillName.STRIKER), 1e-9,
                "a club with no recorded ground should be neither helped nor punished");
    }

    @Test
    @DisplayName("Facilities stay within their stated bound, so they cannot replace talent")
    void facilitiesAreBounded() {
        double best = ground(20, 20, 20).trainingFactorFor(SkillName.STRIKER);
        double worst = ground(1, 1, 1).trainingFactorFor(SkillName.STRIKER);
        assertTrue(best <= 1.10, "elite facilities are worth " + best + ", which is a balance change");
        assertTrue(worst >= 0.90, "a bare field took growth to " + worst);
    }

    @Test
    @DisplayName("The gym is the injury facility, and nothing else is")
    void onlyTheGymProtectsAgainstInjury() {
        assertTrue(ground(1, 20, 1).injuryProtection() > 0.35, "a superb gym did not protect anybody");
        assertEquals(0.0, ground(20, 1, 20).injuryProtection(), 1e-9,
                "a perfect ground and a perfect classroom protected against injury");
        // A maxed gym is worth a 40% cut, not immunity. If a good gym could remove the risk there
        // would be no reason ever to rest anybody, and S4.2's central decision would disappear.
        assertTrue(ground(1, 20, 1).injuryProtection() <= 0.4
                        && ground(1, 20, 1).injuryProtection() < 1.0,
                "a gym made training risk-free, which removes the decision entirely");
        assertEquals(0.0, ground(1, 1, 20).injuryProtection(), 1e-9,
                "a level-1 gym still gave protection");
    }

    @Test
    @DisplayName("The last levels cost far more than the first, so the curve is worth climbing")
    void capitalCostIsProgressive() {
        Stadium s = ground(1, 1, 1);
        double first = service.upgradeCost(s, TrainingFacilityService.Facility.GYM, 1);
        double last = service.upgradeCost(s, TrainingFacilityService.Facility.GYM, 19);
        assertTrue(last > first * 5,
                "level 20 is barely dearer than level 2, so every club would stop at 11");
    }

    @Test
    @DisplayName("Upkeep rises with what the club has actually built")
    void upkeepTracksInvestment() {
        assertTrue(service.weeklyUpkeep(clubWith(ground(20, 20, 20), 0))
                > service.weeklyUpkeep(clubWith(ground(1, 1, 1), 0)));
        assertEquals(0.0, service.weeklyUpkeep(null), 1e-9);
    }

    @Test
    @DisplayName("A club that cannot pay is refused, rather than left in the red")
    void cannotAffordMeansNo() {
        Stadium s = ground(5, 5, 5);
        Team broke = clubWith(s, 1.0);
        when(teams.findById(7L)).thenReturn(Optional.of(broke));

        assertTrue(service.upgrade(7L, TrainingFacilityService.Facility.GYM).isEmpty(),
                "a club with 1 in the bank bought a gym");
        assertEquals(1.0, broke.getBudget(), 1e-9, "a refused upgrade still charged the club");
        verify(teams, never()).save(any());
    }

    @Test
    @DisplayName("An affordable upgrade raises the level, charges the club and keeps both records")
    void affordableUpgradeHappens() {
        Stadium s = ground(5, 5, 5);
        Team rich = clubWith(s, 1_000_000.0);
        when(teams.findById(7L)).thenReturn(Optional.of(rich));

        assertTrue(service.upgrade(7L, TrainingFacilityService.Facility.GYM).isPresent());
        assertEquals(6, s.getGymLevel());
        assertEquals(5, s.getTrainingQuality(), "the wrong facility was upgraded");
        assertTrue(rich.getBudget() < 1_000_000.0, "the upgrade was free");
        verify(teams).save(rich);
        verify(stadiums).save(s);
    }

    @Test
    @DisplayName("A maxed-out facility cannot be upgraded again, and never for free")
    void maxedIsMaxed() {
        Stadium s = ground(20, 20, 20);
        Team rich = clubWith(s, 10_000_000.0);
        when(teams.findById(7L)).thenReturn(Optional.of(rich));

        assertTrue(service.upgrade(7L, TrainingFacilityService.Facility.GYM).isEmpty());
        assertEquals(0.0, service.upgradeCost(s, TrainingFacilityService.Facility.GYM, 20),
                "a full facility still quoted a price");
        assertEquals(10_000_000.0, rich.getBudget(), 1e-9);
    }

    @Test
    @DisplayName("A club with no stadium has nothing to upgrade")
    void noStadiumNoUpgrade() {
        when(teams.findById(7L)).thenReturn(Optional.of(clubWith(null, 1_000_000.0)));
        assertTrue(service.upgrade(7L, TrainingFacilityService.Facility.GYM).isEmpty());
        assertTrue(service.upgrade(null, TrainingFacilityService.Facility.GYM).isEmpty());
        assertTrue(service.upgrade(7L, null).isEmpty());
        assertEquals(0.0, service.upgradeCost(null, TrainingFacilityService.Facility.GYM, 1));
    }

    @Test
    @DisplayName("The bill for getting to a level is the sum of its steps")
    void wholeBillIsTheSum() {
        Stadium s = ground(1, 1, 1);
        double toThree = service.upgradeCostTo(s, TrainingFacilityService.Facility.GYM, 3);
        double step1 = service.upgradeCost(s, TrainingFacilityService.Facility.GYM, 1);
        double step2 = service.upgradeCost(s, TrainingFacilityService.Facility.GYM, 2);
        assertEquals(step1 + step2, toThree, 1e-6);
        assertFalse(toThree <= step1, "reaching level 3 cost less than reaching level 2");
    }
}
