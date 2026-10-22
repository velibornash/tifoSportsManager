package org.tifo.footballmanager.newLogic.sim.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injury is never longer than three weeks, and the record says how it happened.
 *
 * <p><b>Owner, 2026-10-22:</b> *"34 dana povreda, sa 2 ligaske utakmice nedeljno plus potencijalno
 * internationl / cup ili friendly to je skoro 20 utakmica... koliko god to verovatno bilo realno, ljude
 * koji vole igrice to smara... ok imamo medical center but still"* — and *"bilo bi dobro ako mozemo da
 * saznamo kako i zasto bas ovaj igrac (jel imao fatigue, jel bio duel, jel izvrsena izmena?)"*.
 *
 * <p>The match that started it was Omladinac's league game on Oracle, match 516, where **Nenad Kačar
 * went down in minute 14 with a KNEE and was written down as *"INJURY Nenad Kačar(DR) - KNEE, 34 days,
 * at 14'"*** — the same sentence every injury in the game has ever had.
 */
class InjuryLengthAndCauseTest {

    @Test
    @DisplayName("no injury lasts longer than three weeks")
    void noInjuryLastsLongerThanThreeWeeks() {
        assertEquals(23, InjuryService.MAX_INJURY_DAYS,
                "three weeks. A month was a third of a season, and a week is two league games.");

        for (InjuryService.InjuryType type : InjuryService.InjuryType.values()) {
            for (int rolled = type.minDays(); rolled <= type.maxDays(); rolled++) {
                int days = InjuryService.daysOutFor(type, rolled);
                assertTrue(days <= InjuryService.MAX_INJURY_DAYS,
                        type + " rolled " + rolled + " days and kept him out " + days
                                + " days, which is longer than the cap");
                assertTrue(days >= 1,
                        type + " rolled " + rolled + " days and kept him out " + days
                                + " days, which is not an absence at all");
            }
        }
    }

    @Test
    @DisplayName("the worst injury the game can roll is three weeks")
    void theWorstInjuryIsThreeWeeks() {
        assertEquals(InjuryService.MAX_INJURY_DAYS,
                InjuryService.daysOutFor(InjuryService.InjuryType.FRACTURE, 90),
                "a fracture ran to ninety days, which is a quarter of a season on its own");
        assertEquals(InjuryService.MAX_INJURY_DAYS,
                InjuryService.daysOutFor(InjuryService.InjuryType.KNEE, 35),
                "and a knee to thirty-five");
    }

    @Test
    @DisplayName("a short injury is still short")
    void aShortInjuryIsStillShort() {
        assertEquals(2, InjuryService.daysOutFor(InjuryService.InjuryType.KNOCK, 2),
                "a knock is a dead leg and is gone by the weekend");
        assertEquals(9, InjuryService.daysOutFor(InjuryService.InjuryType.HAMSTRING, 9),
                "and a hamstring is a fortnight, not a month");
    }

    @Test
    @DisplayName("the cause reaches the medical page")
    void theCauseReachesTheMedicalPage() {
        org.tifo.footballmanager.newLogic.model.Player player =
                new org.tifo.footballmanager.newLogic.model.Player();
        org.tifo.footballmanager.newLogic.model.Skills skills =
                new org.tifo.footballmanager.newLogic.model.Skills();
        for (org.tifo.footballmanager.newLogic.model.SkillName skill
                : org.tifo.footballmanager.newLogic.model.SkillName.values()) {
            skills.setSkill(skill, 12);
        }
        skills.initializeExactFromVisibleIfNeeded();
        player.setName("Nenad Kačar");
        player.setSkills(skills);
        player.setInjured(true);
        player.setInjuryDaysRemaining(23);
        player.setInjuryCause("in a duel, and he was already exhausted");

        org.tifo.footballmanager.newLogic.dto.PlayerDTO row =
                org.tifo.footballmanager.newLogic.dto.PlayerDTO.from(player);

        assertEquals("in a duel, and he was already exhausted", row.getInjuryCause(),
                "the medical page has to be able to answer how and why, and it can only answer what "
                        + "was written down");
    }
}
