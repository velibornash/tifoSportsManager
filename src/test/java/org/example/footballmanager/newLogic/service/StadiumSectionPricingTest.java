package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.SeatingType;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.StandPosition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A price per section has to reach the money, or it is decoration (owner, 2026-10-07).
 *
 * <p>The owner asked for eight sections priced apart, with a recommendation next to each. That is only
 * a feature if the eight prices are what the crowd responds to and what the gate takes — so this proves
 * the two places money is decided, and then deliberately breaks the pricing to watch them change.
 */
class StadiumSectionPricingTest extends BaseTest {

    @Autowired StadiumSectionService sections;
    @Autowired AdmissionService admission;
    @Autowired StadiumRepository stadiums;
    @Autowired TeamRepository teams;

    @Test
    @Transactional
    @DisplayName("the crowd reacts to the average of the eight section prices, not one headline price")
    void demandFollowsTheSectionPrices() {
        Stadium ground = aGround();
        Team club = aTeamWithGround(ground);

        sections.build(club, StandPosition.NORTH,
                SeatingType.SEATS, 8000, false, 1, 1);
        double dearPrice = admission.demandPrice(ground);

        sections.setPrice(club, StandPosition.NORTH, 60.0);
        double dearerPrice = admission.demandPrice(ground);

        assertEquals(20.0, dearPrice, 0.01, "one section at the SEATS recommendation of 20");
        assertEquals(60.0, dearerPrice, 0.01,
                "and the price the manager actually sets, which is what the crowd is being asked");
        assertTrue(dearerPrice > dearPrice,
                "so the club's own price, not a constant, is the price elasticity is run against");
    }

    @Test
    @Transactional
    @DisplayName("a ground is weighted: a cheap terrace does not speak for a premium block")
    void theAverageIsWeightedByHowManySeatsThereAre() {
        Stadium ground = aGround();
        Team club = aTeamWithGround(ground);

        sections.build(club, StandPosition.NORTH,
                SeatingType.SEATS, 9000, false, 1, 1);
        sections.build(club, StandPosition.SOUTH,
                SeatingType.HEATED, 1000, false, 1, 1);

        assertEquals(21.4, admission.demandPrice(ground), 0.01,
                "9000 at 20 and 1000 at 34 is 21, not the 34 the premium block asks for");
    }

    @Test
    @Transactional
    @DisplayName("the gate fills the cheap sections first, so a nearly empty ground is not a rich one")
    void theGateFillsTheCheapSectionsFirst() {
        Stadium ground = aGround();
        Team club = aTeamWithGround(ground);
        sections.build(club, StandPosition.NORTH,
                SeatingType.STANDING, 5000, false, 1, 1);
        sections.build(club, StandPosition.SOUTH,
                SeatingType.HEATED, 5000, false, 1, 1);

        int homeCap = admission.homeSectorCapacity(ground);

        double nearlyEmpty = admission.realisedGateRevenue(ground, (int) (homeCap * 0.10), 0);
        double full = admission.realisedGateRevenue(ground, homeCap, 0);

        assertTrue(nearlyEmpty < full, "a fuller ground takes more money");
        assertTrue(nearlyEmpty / (homeCap * 0.10) < 8.0 + 0.01,
                "and the small crowd was the 8-euro terrace, not the 34-euro heated block");
        // 80% of the ground is sellable to the home crowd: 4,000 terrace seats at 8 and 4,000 heated at 34.
        assertEquals(full, 4_000 * 8.0 + 4_000 * 34.0, 1.0,
                "a full ground takes the terraces first and then the premium seats");
    }

    @Test
    @Transactional
    @DisplayName("a section nobody has priced still sells, at the ground's own price")
    void anUnpricedSectionIsNotAClosedSection() {
        // A legacy ground: eight sections laid out from its old single capacity, none of them priced,
        // because a table appeared and nothing asked anyone to fill it in. Found on the real database.
        Stadium ground = aGround(10_000);
        Team club = aTeamWithGround(ground);
        sections.setPrice(club, StandPosition.NORTH, 40.0);

        int homeCap = admission.homeSectorCapacity(ground);
        double full = admission.realisedGateRevenue(ground, homeCap, 0);

        assertEquals(7000 * 15.0 + 1000 * 40.0, full, 1.0,
                "the seven sections nobody priced still sell, at the ground's standard price of 15 — "
                        + "nobody's seats vanish because they were left alone");
        assertEquals(18.125, admission.demandPrice(ground), 0.01,
                "and the crowd is charged the weighted average of what the eight sections ask");
    }

    @Test
    @Transactional
    @DisplayName("a ground whose sections were never built still sells tickets, on its three tiers")
    void aGroundWithNoSectionsFallsBackToItsTiers() {
        // Deliberately not laid out: this is the legacy ground, before anyone opens its stadium page.
        Stadium ground = aGround(10_000);
        ground.setTicketPrice(20.0);
        stadiums.save(ground);

        int homeCap = admission.homeSectorCapacity(ground);

        assertEquals(20.0, admission.demandPrice(ground), 0.01,
                "with no priced section the ground's own standard price is the demand price");
        assertTrue(admission.realisedGateRevenue(ground, homeCap, 0) > 0,
                "and the tiers still sell, so no ground is ever unsellable");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private Stadium aGround() {
        return aGround(0);
    }

    private Stadium aGround(int capacity) {
        Stadium s = new Stadium();
        s.setName("Ground " + UUID.randomUUID().toString().substring(0, 6));
        s.setCapacity(capacity);
        s.setExpandableTo(50_000);
        s.setSeatQuality(10);
        s.setTicketPrice(15.0);
        return stadiums.save(s);
    }

    private Team aTeamWithGround(Stadium ground) {
        Team team = new Team();
        team.setName("Club " + UUID.randomUUID().toString().substring(0, 6));
        team.setFormation("4-4-2");
        team = teams.save(team);
        team.setStadium(ground);
        team.setBudget(5_000_000.0);
        return teams.save(team);
    }
}