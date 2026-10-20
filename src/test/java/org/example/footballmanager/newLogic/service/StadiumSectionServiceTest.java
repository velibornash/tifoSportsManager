package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.SeatingType;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.StadiumSection;
import org.example.footballmanager.newLogic.model.StandPosition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stadium is built section by section (owner, 2026-10-07).
 *
 * <p>Spec, verbatim: each of the four sides and each of the four corners takes the same three
 * choices — a seating type, a capacity to add, and a roof over that one section. The page returns a
 * <b>price</b> and <b>how long the section is unusable</b>. Each of the eight sections is priced
 * separately, with a recommended price, and the ground's total is their sum.
 */
class StadiumSectionServiceTest extends BaseTest {

    @Autowired StadiumSectionService sections;
    @Autowired StadiumRepository stadiums;
    @Autowired TeamRepository teams;

    @Test
    @Transactional
    @DisplayName("the first ask creates the eight sections, all empty and open")
    void eightSectionsExistPerLambda() {
        Stadium ground = aGround(0);
        List<StadiumSection> sectionsList = sections.sectionsOf(ground);

        assertEquals(8, sectionsList.size());
        assertTrue(sectionsList.stream().allMatch(s -> s.getCapacity() == 0),
                "and all eight start with zero seats before anything is built");
        assertTrue(sectionsList.stream().noneMatch(StadiumSection::isRoof),
                "and none of them has a roof before one is chosen for that section only");
    }

    @Test
    @Transactional
    @DisplayName("a ground built before this existed keeps every seat, split across the eight")
    void legacyGroundIsLaidOutNotEmptied() {
        Stadium ground = aGround(24_000);

        List<StadiumSection> laid = sections.sectionsOf(ground);

        assertEquals(8, laid.size());
        assertEquals(24_000, laid.stream().mapToInt(StadiumSection::getCapacity).sum(),
                "and they add up to the capacity the ground already had — nobody loses a seat");
        assertEquals(24_000, ground.getCapacity(),
                "which also means the ground's own total is unchanged by the migration");
        assertTrue(laid.stream().allMatch(s -> s.getCapacity() > 0),
                "every section carries its share rather than one standing empty");
    }

    @Test
    @Transactional
    @DisplayName("an awkward capacity divides into eight shares, and the sum is still exact")
    void legacyGroundWithAnAwkwardCapacityStillAddsUp() {
        Stadium ground = aGround(24_001);

        List<StadiumSection> laid = sections.sectionsOf(ground);

        assertEquals(24_001, laid.stream().mapToInt(StadiumSection::getCapacity).sum(),
                "remainders are handed out rather than rounded away");
        assertEquals(24_001, ground.getCapacity());
    }

    @Test
    @Transactional
    @DisplayName("building one section moves its capacity and leaves the others alone")
    void buildingOneSectionDoesNotTouchTheOthers() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);

        Map<String, Object> built = sections.build(club, StandPosition.NORTH,
                SeatingType.SEATS, 2000, false, 1, 1);

        assertTrue(Boolean.TRUE.equals(built.get("built")), String.valueOf(built));
        assertEquals(2000, ground.getCapacity(),
                "and the ground total is the sum of the eight, which is just this one so far");

        int north = capacityOf(ground, StandPosition.NORTH);
        int others = sections.sectionsOf(ground).stream()
                .filter(s -> s.getPosition() != StandPosition.NORTH).mapToInt(StadiumSection::getCapacity).sum();
        assertEquals(2000, north);
        assertEquals(0, others, "the other seven sections are untouched");
    }

    @Test
    @Transactional
    @DisplayName("total capacity is the sum of the eight sections you have actually built")
    void totalCapacityIsTheSumOfTheEight() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);

        sections.build(club, StandPosition.NORTH, SeatingType.SEATS, 4000, false, 1, 1);
        sections.build(club, StandPosition.SOUTH, SeatingType.SEATS, 3000, false, 1, 1);
        sections.build(club, StandPosition.NORTH_EAST, SeatingType.STANDING, 1500, true, 1, 1);
        sections.build(club, StandPosition.SOUTH_WEST, SeatingType.HEATED, 800, true, 1, 1);

        assertEquals(9300, ground.getCapacity(), "4000 + 3000 + 1500 + 800 across four sides/corners");
    }

    @Test
    @Transactional
    @DisplayName("heated seats buy a roof, and the roof is a section's own")
    void aRoofIsASectionProperty() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);

        sections.build(club, StandPosition.NORTH, SeatingType.HEATED, 1000, true, 1, 1);
        sections.build(club, StandPosition.SOUTH, SeatingType.STANDING, 1000, false, 1, 1);

        assertTrue(sections.section(ground, StandPosition.NORTH).isRoof(), "the north side bought the roof");
        assertFalse(sections.section(ground, StandPosition.SOUTH).isRoof(),
                "the south side did not, so it has no roof of its own");
        assertFalse(ground.isRoof(),
                "and the ground is not fully covered, because one roof is not eight");
    }

    @Test
    @Transactional
    @DisplayName("each section's price is its own, and heated costs more than standing")
    void eachSectionPricesItself() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);

        sections.build(club, StandPosition.SOUTH_EAST, SeatingType.STANDING, 2000, false, 1, 1);
        sections.build(club, StandPosition.SOUTH_WEST, SeatingType.HEATED, 2000, false, 1, 1);

        StadiumSection standing = sections.section(ground, StandPosition.SOUTH_EAST);
        StadiumSection heated = sections.section(ground, StandPosition.SOUTH_WEST);

        assertEquals(8.0, standing.getTicketPrice(), 0.01,
                "the standing corner opened at the standing recommendation");
        assertEquals(34.0, heated.getTicketPrice(), 0.01,
                "and the heated corner at the heated one");
        assertTrue(heated.getTicketPrice() > standing.getTicketPrice(),
                "heated is the dearer product: " + heated.getTicketPrice() + " vs " + standing.getTicketPrice());
    }

    @Test
    @Transactional
    @DisplayName("a manager sets a section's price to anything they like, and can read the recommendation")
    void aSectionCanBeRepricedOnItsOwn() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);
        sections.build(club, StandPosition.EAST, SeatingType.SEATS, 1000, false, 1, 1);

        sections.setPrice(club, StandPosition.EAST, 41.0);

        assertEquals(41.0, sections.section(ground, StandPosition.EAST).getTicketPrice(), 0.01);
        assertEquals(20.0, sections.section(ground, StandPosition.EAST).getRecommendedPrice(), 0.01,
                "and the recommendation still stands next to the manager's own number");

        assertFalse(Boolean.TRUE.equals(sections.setPrice(club, StandPosition.EAST, -1.0).get("ok")),
                "a negative price is refused rather than stored");
        assertEquals(41.0, sections.section(ground, StandPosition.EAST).getTicketPrice(), 0.01,
                "and the refusal left the price alone");
    }

    @Test
    @Transactional
    @DisplayName("the quote reports the price and how long the stand is unusable, before money moves")
    void theQuoteTellsBothBeforeSpending() {
        Stadium ground = aGround(5000);

        Map<String, Object> quote = sections.quote(ground, StandPosition.WEST, SeatingType.SEATS, 2000, true, 3);

        assertTrue(Boolean.TRUE.equals(quote.get("ok")), String.valueOf(quote));
        assertTrue(((Number) quote.get("cost")).doubleValue() > 0, "a price");
        int weeks = ((Number) quote.get("weeksClosed")).intValue();
        assertTrue(weeks >= 1, "there is no honest build of 2000 seats in zero weeks");
        assertEquals(3 + weeks, ((Number) quote.get("closedUntilWeek")).intValue(),
                "and it says which week the stand takes fans again: work starts in week 3 and lasts "
                        + weeks + " weeks");
        assertEquals(7000, ((Number) quote.get("totalCapacityAfter")).intValue(),
                "and the new ground total is reported, not just the section");

        assertEquals(5000, ground.getCapacity(), "and nothing moved yet, because this was only a quote");
    }

    @Test
    @Transactional
    @DisplayName("a roof is priced on the seats it ends up covering, not the ones already standing")
    void theRoofIsPricedOnWhatItCovers() {
        Stadium ground = aGround(0);

        Map<String, Object> fresh = sections.quote(ground, StandPosition.NORTH, SeatingType.SEATS, 2000, true, 1);

        assertEquals(120_000.0, ((Number) fresh.get("roofCost")).doubleValue(), 0.01,
                "2000 seats at the roof's per-seat price — a roof over brand new seats is a big roof");
        assertTrue(((Number) fresh.get("cost")).doubleValue()
                        > ((Number) fresh.get("workCost")).doubleValue(),
                "and it is added on top of the seats, not hidden inside them");
    }

    @Test
    @Transactional
    @DisplayName("a roof over heated seats keeps the stand closed longer than a bare one")
    void heatedRoofCostsMoreWeeks() {
        Stadium ground = aGround(0);

        Map<String, Object> bare = sections.quote(ground, StandPosition.NORTH, SeatingType.HEATED, 1500, false, 1);
        Map<String, Object> withRoof = sections.quote(ground, StandPosition.NORTH, SeatingType.HEATED, 1500, true, 1);

        int bareWeeks = ((Number) bare.get("weeksClosed")).intValue();
        int roofWeeks = ((Number) withRoof.get("weeksClosed")).intValue();
        assertTrue(roofWeeks > bareWeeks,
                "heated seats under a roof are wiring and a ceiling going up together, so the section "
                        + "holds no one for longer: " + roofWeeks + " weeks against " + bareWeeks);
    }

    @Test
    @Transactional
    @DisplayName("the quote asks what happens before the money moves")
    void aQuoteWithNothingToDoIsRefusedRatherThanCharged() {
        Stadium ground = aGround(10_000);

        assertEquals("Nothing to build: choose some seats to add, or a roof.",
                sections.quote(ground, StandPosition.NORTH, SeatingType.SEATS, 0, false, 1).get("reason"));
        assertEquals("Capacity cannot shrink. A ground only grows here.",
                sections.quote(ground, StandPosition.NORTH, SeatingType.SEATS, -500, false, 1).get("reason"));
        assertEquals("Pick one of the four sides or four corners.",
                sections.quote(ground, null, SeatingType.SEATS, 500, false, 1).get("reason"));
        assertEquals("Pick a seating type.",
                sections.quote(ground, StandPosition.NORTH, null, 500, false, 1).get("reason"));
    }

    @Test
    @Transactional
    @DisplayName("a ground cannot be built past its ceiling")
    void theCeilingStands() {
        Stadium ground = aGround(0);
        ground.setExpandableTo(3000);
        stadiums.save(ground);
        Team club = aTeamWithGround(ground);

        assertTrue(Boolean.TRUE.equals(
                sections.build(club, StandPosition.NORTH, SeatingType.SEATS, 2500, false, 1, 1).get("built")));

        Map<String, Object> tooFar = sections.quote(ground, StandPosition.SOUTH, SeatingType.SEATS, 1000, false, 1);
        assertFalse(Boolean.TRUE.equals(tooFar.get("ok")),
                "and the next step is refused rather than quietly exceeding the ceiling");
        assertEquals(2500, ground.getCapacity(), "with the ground left where it was");
    }

    @Test
    @Transactional
    @DisplayName("a section is not rebuilt in a different material")
    void aBuiltSectionKeepsItsSeatingType() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);
        sections.build(club, StandPosition.NORTH, SeatingType.STANDING, 1000, false, 1, 1);

        Map<String, Object> change = sections.quote(ground, StandPosition.NORTH, SeatingType.HEATED, 500, false, 1);

        assertFalse(Boolean.TRUE.equals(change.get("ok")),
                "there is no demolition model in this game, so the section stays a terrace");
    }

    @Test
    @Transactional
    @DisplayName("building consumes the club's budget and closes that one section")
    void buildingConsumesBudgetAndClosesTheSection() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);
        double before = club.getBudget();

        Map<String, Object> built = sections.build(club, StandPosition.EAST, SeatingType.SEATS, 1000, false, 1, 4);

        assertTrue(club.getBudget() < before, "the club paid for it");
        assertEquals(before - ((Number) built.get("spent")).doubleValue(), club.getBudget(), 0.01,
                "and paid exactly the quoted price");
        assertEquals(1000, ground.getCapacity());

        StadiumSection east = sections.section(ground, StandPosition.EAST);
        assertEquals(5, east.getClosedUntilWeek(),
                "the stand is shut until the week the quote promised: 4 + 1 week of work");
        assertTrue(sections.section(ground, StandPosition.WEST).getClosedUntilWeek() == null,
                "and no other section was closed by it");
    }

    @Test
    @Transactional
    @DisplayName("a club that cannot pay is told the price, and nothing is built")
    void anUnaffordableBuildIsRefused() {
        Stadium ground = aGround(0);
        Team club = aTeamWithGround(ground);
        club.setBudget(100.0);
        teams.save(club);

        Map<String, Object> refused = sections.build(club, StandPosition.NORTH, SeatingType.HEATED, 4000, true, 1, 1);

        assertFalse(Boolean.TRUE.equals(refused.get("ok")), String.valueOf(refused));
        assertTrue(String.valueOf(refused.get("reason")).contains("Not enough budget"));
        assertEquals(0, ground.getCapacity(), "and no seats appeared");
        assertEquals(100.0, club.getBudget(), 0.01, "nor was any money taken");
    }

    @Test
    @Transactional
    @DisplayName("seat quality reports what was built, and the ground roof flag only a full cover")
    void theGroundReadsWhatWasBuilt() {
        Stadium ground = aGround(0);
        ground.setSeatQuality(20);
        stadiums.save(ground);
        Team club = aTeamWithGround(ground);

        sections.build(club, StandPosition.NORTH, SeatingType.STANDING, 1000, true, 1, 1);
        sections.build(club, StandPosition.SOUTH, SeatingType.HEATED, 1000, true, 1, 1);
        assertFalse(ground.isRoof(), "two covered sections are not a covered ground");

        assertEquals(12, ground.getSeatQuality(),
                "a terrace and a heated block average to somewhere between the two, which is what was built");

        for (StandPosition position : StandPosition.values()) {
            if (position == StandPosition.NORTH || position == StandPosition.SOUTH) continue;
            Map<String, Object> built = sections.build(club, position, SeatingType.SEATS, 100, true, 1, 1);
            assertTrue(Boolean.TRUE.equals(built.get("built")), String.valueOf(built));
        }
        assertTrue(ground.isRoof(), "every section covered is a covered ground");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private Stadium aGround(int capacity) {
        Stadium s = new Stadium();
        s.setName("Ground " + UUID.randomUUID().toString().substring(0, 6));
        s.setCapacity(capacity);
        s.setExpandableTo(50_000);
        // 14 is ordinary seats, which is what a legacy ground of this size lays out as.
        s.setSeatQuality(14);
        return stadiums.save(s);
    }

    private int capacityOf(Stadium ground, StandPosition position) {
        return sections.section(ground, position).getCapacity();
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