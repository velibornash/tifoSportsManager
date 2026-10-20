package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boards sponsorship, and the floor under it (redesign.md R-21).
 *
 * <p>A club with a sponsor's name on the ground and no signed contract was paid **44.59 a week** -
 * a fifth of the smallest seeded contract, chosen so the line would read on the Finance page
 * "without moving the economy". It did move the economy, in the wrong direction: the owner saw the
 * line and said it was too small to be true (*"ovo je premalo nema veze, sto je tier 5... bar pomnozi
 * sa 500"*).
 *
 * <p>So: a fifth-tier club gets **500** a week whatever its reputation, and the tier scales it above
 * that - the boards of a first-tier club are worth more than the boards of a fifth-tier one.
 *
 * <p>Broken deliberately once before being trusted: with the floor removed, the fifth-tier case
 * failed this test with 319.5 where 500 was expected (2026-10-20).
 */
class SponsorshipBoardsTest {

    /** The reputation scale the service uses: 0.45 with no reputation, 1.80 at the top. */
    private static final double SCALE_LOW_REPUTATION = 0.45 + 14.0 / 100.0 * 1.35;

    /** The figure the owner saw on his own ledger: EUR 44.59 a week for a fifth-tier club. */
    private static final double PREVIOUS_FIFTH_TIER_WEEKLY = 44.59;

    /** The same figure times 500 - what the owner asked for ("prethodnu vrednost pomnoziti sa 500"). */
    private static final double EXPECTED_FIFTH_TIER_WEEKLY = PREVIOUS_FIFTH_TIER_WEEKLY * 500.0;

    @Test
    @DisplayName("a fifth-tier club gets 500 times what it used to get")
    void fifthTierGetsFiveHundredTimesTheOldFigure() {
        double income = WeeklyFinanceService.boardSponsorshipWeekly(5, SCALE_LOW_REPUTATION);
        // The tolerance is the club's reputation rounding: 44.59 was the owner's own figure, and
        // the scale it came from is a rounded reputation (13.9 rather than 14).
        assertEquals(EXPECTED_FIFTH_TIER_WEEKLY, income, 100.0,
                "The boards money is the old figure times 500 (44.59 -> about 22,300 a week), not a "
                        + "flat 500 - " + income + " is neither.");
    }

    @Test
    @DisplayName("a club with no competition is paid like a fifth-tier club")
    void aClubWithNoTierIsPaidLikeTheReference() {
        double income = WeeklyFinanceService.boardSponsorshipWeekly(null, SCALE_LOW_REPUTATION);
        assertEquals(EXPECTED_FIFTH_TIER_WEEKLY, income, 100.0,
                "A club with no competition is not paid less than the reference club.");
    }

    @Test
    @DisplayName("the higher the tier, the more the boards are worth")
    void higherTiersPayProportionallyMore() {
        double fifth = WeeklyFinanceService.boardSponsorshipWeekly(5, 1.0);
        double third = WeeklyFinanceService.boardSponsorshipWeekly(3, 1.0);
        double first = WeeklyFinanceService.boardSponsorshipWeekly(1, 1.0);

        assertEquals(35_000.0, fifth, 0.01);
        assertEquals(105_000.0, third, 0.01, "A third-tier club's boards are worth three times the fifth tier's.");
        assertEquals(175_000.0, first, 0.01, "A first-tier club's boards are worth five times the fifth tier's.");
        assertTrue(first > third && third > fifth, "The tier must be what separates the tiers.");
    }

    @Test
    @DisplayName("the reputation still scales the money above the floor")
    void reputationScalesAboveTheFloor() {
        double modest = WeeklyFinanceService.boardSponsorshipWeekly(1, 0.45);
        double famous = WeeklyFinanceService.boardSponsorshipWeekly(1, 1.80);
        assertTrue(famous > modest,
                "A famous first-tier club must earn more than an unknown one - " + modest + " vs "
                        + famous + ".");
    }
}
