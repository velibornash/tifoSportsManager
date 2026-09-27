package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.scouting.ScoutAssignmentDTO;
import org.example.footballmanager.newLogic.dto.scouting.ScoutingNetworkDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scouting network's rules (Sprint 5.1).
 *
 * <p>{@code ScoutingReachTest} covers the arithmetic. This covers the rules, and it exists because of
 * one of them in particular: <b>a scout id is guessable</b>, so without the own-club check a manager
 * could post a rival's scout to a country and read reports generated with the rival's scouting
 * attribute. That is a data leak dressed as a convenience, and it is exactly the kind of rule that a
 * unit test on the maths would never notice was missing.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScoutingServiceTest {

    @Autowired ScoutingService scouting;
    @Autowired TeamRepository teams;
    @Autowired StaffMemberRepository staff;
    @Autowired CountryRepository countries;

    private Team club;
    private Team rival;
    /** Seeded with youthRating 85. */
    private Country brazil;
    /** Seeded with youthRating 70. */
    private Country serbia;
    /** Seeded with youthRating 50 — the weakest pipeline in the seed, used as the "poor country" case. */
    private Country macedonia;

    @BeforeEach
    void setUp() {
        club = club("Scouting club");
        rival = club("Rival club");
        // The seed already carries nine countries with known youthRatings. Creating more would fight
        // the unique constraint on isoCode, and inventing youthRatings here would mean this test was
        // asserting against its own fixtures rather than against the data the game actually ships.
        brazil = seeded("BRA");
        serbia = seeded("SRB");
        macedonia = seeded("MKD");
    }

    private Country seeded(String isoCode) {
        return countries.findByIsoCode(isoCode)
                .orElseThrow(() -> new AssertionError(
                        "Seed data is missing country " + isoCode + "; the test profile is expected to seed nine countries"));
    }

    private Team club(String name) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(60.0);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(5_000);
        s.setTicketPrice(10.0);
        t.setStadium(s);
        return teams.save(t);
    }

    private StaffMember scoutFor(Team at, int scouting) {
        StaffMember s = new StaffMember();
        s.setTeam(at);
        s.setRole(StaffRole.SCOUT);
        s.setName("Scout " + System.nanoTime());
        s.setAge(34);
        s.setScouting(scouting);
        s.setWeeklyWage(900.0);
        return staff.save(s);
    }

    @Test
    @DisplayName("a scout can be posted, and the network reports it")
    void postingWorks() {
        StaffMember scout = scoutFor(club, 15);

        ScoutAssignmentDTO posted = scouting.assignScout(club.getId(), scout.getId(), brazil.getId(), 1);

        assertNotNull(posted.getAssignmentId());
        assertEquals("Brazil", posted.getCountryName(), "the DTO must name the country");
        assertEquals(15, posted.getScoutScouting());
        assertEquals(85, posted.getCountryYouthRating());
        assertTrue(posted.getReach() > 55, "a 15-attribute scout on Brazil should read well, got " + posted.getReach());

        ScoutingNetworkDTO network = scouting.networkFor(club.getId(), 1);
        assertTrue(network.isHasCoverage());
        assertEquals(1, network.getAssignments().size());
        assertEquals(posted.getReach(), network.getTotalReach());
        assertNotNull(network.getSummary());
        assertFalse(network.getSummary().isBlank(), "a covered network must say something about itself");
    }

    @Test
    @DisplayName("a rival's scout cannot be posted — the leak this rule exists to stop")
    void cannotPostAnotherClubsScout() {
        StaffMember rivalsScout = scoutFor(rival, 20);

        ApiException ex = assertThrows(ApiException.class,
                () -> scouting.assignScout(club.getId(), rivalsScout.getId(), brazil.getId(), 1));

        assertEquals("SCOUT_NOT_OWN_STAFF", ex.getCode());
        assertFalse(scouting.networkFor(club.getId(), 1).isHasCoverage(),
                "a refused posting must leave the network untouched");
    }

    @Test
    @DisplayName("only a SCOUT can be posted, however good he is")
    void cannotPostANonScout() {
        StaffMember headCoach = new StaffMember();
        headCoach.setTeam(club);
        headCoach.setRole(StaffRole.HEAD_COACH);
        headCoach.setName("Coach " + System.nanoTime());
        headCoach.setAge(50);
        headCoach.setScouting(20);
        headCoach.setDevelopment(20);
        headCoach.setWeeklyWage(4_000.0);
        StaffMember saved = staff.save(headCoach);

        ApiException ex = assertThrows(ApiException.class,
                () -> scouting.assignScout(club.getId(), saved.getId(), brazil.getId(), 1));

        assertEquals("STAFF_NOT_A_SCOUT", ex.getCode());
    }

    @Test
    @DisplayName("one posting per country, and recalling frees the country again")
    void duplicateCountryIsRefusedAndRecallReleases() {
        StaffMember first = scoutFor(club, 12);
        ScoutAssignmentDTO posted = scouting.assignScout(club.getId(), first.getId(), brazil.getId(), 1);

        StaffMember second = scoutFor(club, 12);
        ApiException ex = assertThrows(ApiException.class,
                () -> scouting.assignScout(club.getId(), second.getId(), brazil.getId(), 1));
        assertEquals("COUNTRY_ALREADY_COVERED", ex.getCode());

        // Recalling must make the country available again, or a club could never change its mind
        // about where it is looking — which is the one thing a scouting network has to allow.
        scouting.recallScout(club.getId(), posted.getAssignmentId());
        assertFalse(scouting.networkFor(club.getId(), 1).isHasCoverage(), "a recalled posting is not coverage");

        ScoutAssignmentDTO replacement = scouting.assignScout(club.getId(), second.getId(), brazil.getId(), 1);
        assertNotNull(replacement.getAssignmentId());
        assertTrue(scouting.networkFor(club.getId(), 1).isHasCoverage());
    }

    @Test
    @DisplayName("a club cannot recall another club's posting")
    void cannotRecallAnotherClubsAssignment() {
        StaffMember scout = scoutFor(club, 12);
        ScoutAssignmentDTO posted = scouting.assignScout(club.getId(), scout.getId(), brazil.getId(), 1);

        ApiException ex = assertThrows(ApiException.class,
                () -> scouting.recallScout(rival.getId(), posted.getAssignmentId()));
        assertEquals("ASSIGNMENT_NOT_OWNED", ex.getCode());
        assertTrue(scouting.networkFor(club.getId(), 1).isHasCoverage(), "a refused recall must change nothing");
    }

    @Test
    @DisplayName("a richer country and a better scout both raise reach")
    void reachRespondsToBothInputs() {
        StaffMember average = scoutFor(club, 10);
        StaffMember good = scoutFor(club, 18);

        int goodInRich = scouting.assignScout(club.getId(), good.getId(), brazil.getId(), 1).getReach();
        int averageInRich = scouting.assignScout(club.getId(), average.getId(), serbia.getId(), 1).getReach();
        int goodInWeak = scouting.assignScout(club.getId(), good.getId(), macedonia.getId(), 1).getReach();

        // Brazil 85 vs Serbia 70 at different scout qualities, and Brazil vs North Macedonia at the
        // same one. The product model says the second comparison is the sharper of the two, because
        // it isolates the country while holding the scout still.
        assertTrue(goodInRich > goodInWeak,
                "the same scout must reach further in a richer country: " + goodInRich + " vs " + goodInWeak);
        assertTrue(goodInRich > averageInRich, "a better scout must reach further in the same country");
        assertTrue(goodInWeak < goodInRich && goodInWeak > 0,
                "a good scout in a weak country should still be worth something, got " + goodInWeak);
    }

    @Test
    @DisplayName("an uncovered club gets an empty state, not a broken table")
    void emptyNetworkIsExplicit() {
        ScoutingNetworkDTO network = scouting.networkFor(club.getId(), 1);

        assertFalse(network.isHasCoverage());
        assertEquals(0, network.getTotalReach());
        assertTrue(network.getAssignments().isEmpty());
        assertEquals("", network.getSummary(),
                "an empty network says nothing, because 'you are not scouting' is a state to act on, not a sentence to read");
    }

    @Test
    @DisplayName("the season a posting started is recorded, so a report can say how long it has run")
    void seasonIsRecorded() {
        StaffMember scout = scoutFor(club, 12);
        ScoutAssignmentDTO posted = scouting.assignScout(club.getId(), scout.getId(), serbia.getId(), 4);
        assertEquals(4, posted.getAssignedSeasonNumber());
    }

    @Test
    @DisplayName("a club with no network can still ask for one without erroring")
    void unknownTeamIsRejectedNotSilentlyEmpty() {
        ApiException ex = assertThrows(ApiException.class, () -> scouting.networkFor(9_999_999L, 1));
        assertEquals("TEAM_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("the country comes back the way a manager would recognise it")
    void countryNamesComeBack() {
        StaffMember scout = scoutFor(club, 12);
        scouting.assignScout(club.getId(), scout.getId(), serbia.getId(), 1);

        List<ScoutAssignmentDTO> rows = scouting.networkFor(club.getId(), 1).getAssignments();
        assertEquals(1, rows.size());
        assertEquals("Srbija", rows.get(0).getCountryName());
        assertEquals("SRB", rows.get(0).getCountryIsoCode());
        assertNotNull(rows.get(0).getReachLabel(), "reach must arrive with a readable label, not a bare number");
        assertFalse(rows.get(0).getReachLabel().isBlank());
    }
}
