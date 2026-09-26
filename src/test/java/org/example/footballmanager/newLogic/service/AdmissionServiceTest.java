package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.service.AdmissionService.TicketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 2.2 — the away sector and the ticket tiers.
 *
 * <p>Both of these are owner rules with exact numbers, so they are pinned exactly rather than
 * approximately.
 */
class AdmissionServiceTest {

    private final AdmissionService admission = new AdmissionService(null);

    private static Stadium stadium(int capacity, double standardPrice) {
        Stadium s = new Stadium();
        s.setName("Test Ground");
        s.setCapacity(capacity);
        s.setTicketPrice(standardPrice);
        return s;
    }

    @Test
    @DisplayName("visiting supporters always get 20% of the ground")
    void awaySectorIsTwentyPercent() {
        for (int capacity : new int[] { 10_000, 25_000, 43_210, 1_000 }) {
            Stadium s = stadium(capacity, 15);
            int away = admission.awaySectorCapacity(s);
            assertEquals(Math.floor(capacity * 0.20), away,
                    "away sector for a " + capacity + " ground");
            assertEquals(capacity, admission.awaySectorCapacity(s) + admission.homeSectorCapacity(s),
                    "home and away sectors must partition the ground exactly");
        }
    }

    @Test
    @DisplayName("premium is always dearer than standard, and standard than economy")
    void tiersAreOrdered() {
        Stadium s = stadium(20_000, 20);
        assertTrue(admission.priceOf(s, TicketType.PREMIUM) > admission.priceOf(s, TicketType.STANDARD));
        assertTrue(admission.priceOf(s, TicketType.STANDARD) > admission.priceOf(s, TicketType.ECONOMY));
    }

    @Test
    @DisplayName("setting one tier re-derives the standard price so the spread stays coherent")
    void settingATierKeepsTheSpread() {
        Stadium s = stadium(20_000, 20);
        // Ask for a premium ticket at 60 by setting the premium tier directly.
        admission.setTicketPrice(teamWith(s), TicketType.PREMIUM, 60);
        // The standard price must have moved so that premium now costs 60.
        assertEquals(60, admission.priceOf(s, TicketType.PREMIUM), 0.01,
                "the requested tier price must be what is charged");
        assertTrue(admission.priceOf(s, TicketType.STANDARD) < admission.priceOf(s, TicketType.PREMIUM),
                "standard must still be cheaper than premium after the change");
    }

    @Test
    @DisplayName("an absurd price is clamped rather than accepted")
    void pricesAreClamped() {
        Stadium s = stadium(20_000, 20);
        admission.setTicketPrice(teamWith(s), TicketType.STANDARD, -50);
        assertTrue(admission.priceOf(s, TicketType.STANDARD) >= 1.0, "a negative price is not a price");

        admission.setTicketPrice(teamWith(s), TicketType.STANDARD, 100_000);
        assertTrue(admission.priceOf(s, TicketType.STANDARD) <= 250.0, "no ground sells at 100k");
    }

    @Test
    @DisplayName("a full ground beats the headline price per head, because premium sells too")
    void fullGroundBeatsTheHeadlinePrice() {
        Stadium s = stadium(10_000, 20);
        int home = admission.homeSectorCapacity(s);
        int away = admission.awaySectorCapacity(s);
        double revenue = admission.realisedGateRevenue(s, home, away);
        double perHead = revenue / (home + away);

        assertTrue(perHead > admission.priceOf(s, TicketType.STANDARD),
                "a sold-out ground includes premium seats, so the average realised price must beat "
                        + "the standard price: " + perHead);
        assertTrue(perHead < admission.priceOf(s, TicketType.PREMIUM),
                "and it cannot beat the top tier either, or the cheap seats are free");
    }

    @Test
    @DisplayName("a crowd made of economy seats is worth less per head than a full one")
    void economyOnlyCrowdIsWorthLess() {
        Stadium s = stadium(10_000, 20);
        int economyOnly = (int) Math.round(admission.homeSectorCapacity(s) * 0.30);
        double cheapCrowd = admission.realisedGateRevenue(s, economyOnly, 0)
                / Math.max(1, economyOnly);
        double fullCrowd = admission.realisedGateRevenue(s,
                admission.homeSectorCapacity(s), 0)
                / admission.homeSectorCapacity(s);
        assertTrue(cheapCrowd < fullCrowd,
                "filling the premium end must be worth more per head than filling the cheap end");
    }

    @Test
    @DisplayName("a half-empty ground earns less than a full one")
    void fullGroundEarnsMore() {
        Stadium s = stadium(10_000, 20);
        double full = admission.realisedGateRevenue(s,
                admission.homeSectorCapacity(s), admission.awaySectorCapacity(s));
        double empty = admission.realisedGateRevenue(s, 0, 0);
        assertTrue(full > empty);
        assertEquals(0.0, empty, 0.001, "nobody in the stand means no gate income");
    }

    @Test
    @DisplayName("the away end pays the home club")
    void awayEndPaysTheHomeClub() {
        Stadium s = stadium(10_000, 20);
        double withAway = admission.realisedGateRevenue(s, 0, 1_000);
        double without = admission.realisedGateRevenue(s, 0, 0);
        assertTrue(withAway > without, "visiting supporters still pay the home club");
    }

    private org.example.footballmanager.newLogic.model.Team teamWith(Stadium s) {
        org.example.footballmanager.newLogic.model.Team t =
                new org.example.footballmanager.newLogic.model.Team();
        t.setId(1L);
        t.setName("Test FC");
        t.setStadium(s);
        return t;
    }
}
