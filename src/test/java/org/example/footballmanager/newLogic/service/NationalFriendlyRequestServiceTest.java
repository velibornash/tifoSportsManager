package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalFriendlySlots;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-10 — "NT friendly can only be in week 6 day 1" (owner, 2026-10-06).
 *
 * <p>One rule, and it is easy to widen by accident: every other week in the calendar has a league day or
 * a tournament day on it, and a friendly placed there would collide with real football. So the rule is
 * asserted over <b>every week of a season</b> rather than at the week that works.
 */
class NationalFriendlyRequestServiceTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired private NationalFriendlyRequestService service;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchFixtureRepository fixtures;

    private Team seniorA;
    private Team seniorB;
    private Team youthA;
    private Team club;

    @BeforeEach
    void setUp() {
        Country country = new Country();
        country.setName("Warm-up Republic " + System.nanoTime());
        country.setIsoCode(isoCode());
        country.setReputation(1500);
        country.setYouthRating(1500);
        Country saved = countries.save(country);

        seniorA = side(saved, "Senior A");
        seniorB = side(saved, "Senior B");
        youthA = side(saved, "Youth A");
        club = club(saved, "A Local Club");
    }

    private Team side(Country country, String name) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.NATIONAL_TEAM);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(false);
        Team saved = teams.save(t);
        for (int i = 0; i < 25; i++) {
            players.save(player(name + " P" + i, saved));
        }
        return saved;
    }

    private Team club(Country country, String name) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.CLUB);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(false);
        return teams.save(t);
    }

    private static Player player(String name, Team team) {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 12);
        skills.setSkill(SkillName.STAMINA, 12);
        skills.setSkill(SkillName.GOALKEEPER, 12);
        skills.setSkill(SkillName.DEFENDER, 12);
        skills.setSkill(SkillName.TECHNIQUE, 12);
        skills.setSkill(SkillName.PLAYMAKER, 12);
        skills.setSkill(SkillName.PASSING, 12);
        skills.setSkill(SkillName.STRIKER, 12);
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName(name);
        p.setPosition(Position.MID);
        p.setSkills(skills);
        p.setAge(21);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return p;
    }

    /**
     * A three-character ISO code that will not collide.
     *
     * <p>An earlier version used {@code "W" + nanoTime % 90}, which is ninety possibilities for a table
     * whose ISO code is unique and a {@code @BeforeEach} that runs once per test. It collided on the
     * second test and the failure was a constraint violation in a fixture, which says nothing about the
     * thing being tested. Base-36 over 1296 values instead.
     */
    private static String isoCode() {
        String tail = Long.toString(Math.abs(System.nanoTime()) % 1296, 36);
        // Zero-padded: a value under 36 is one character in base 36, and substring(0, 3) on "W7" throws.
        while (tail.length() < 2) {
            tail = "0" + tail;
        }
        return "W" + tail;
    }

    @Test
    @DisplayName("week 6 day 1 is the only week and day a national side may play a friendly")
    void weekSixDayOneAndNothingElse() {
        for (int week = 1; week <= 12; week++) {
            assertEquals(week == NationalFriendlySlots.WEEK, NationalFriendlySlots.availableIn(week),
                    "week " + week + ": the owner said week 6 and only week 6");
        }
        assertEquals(6, NationalFriendlySlots.WEEK);
        assertEquals(1, NationalFriendlySlots.DAY);
        assertEquals(1, NationalFriendlySlots.SLOT);
    }

    @Test
    @Transactional
    @DisplayName("an accepted request creates the fixture on week 6 day 1")
    void anAcceptedRequestCreatesTheFixture() {
        FriendlyRequest created = service.requestFriendly(
                        seniorA.getId(), seniorB.getId(), SEASON, NationalFriendlySlots.WEEK)
                .orElseThrow();

        assertEquals(FriendlyStatus.PENDING, created.getStatus());
        service.respond(created.getId(), seniorB.getId(), true);

        MatchFixture fixture = fixtures.findById(created.getAcceptedFixtureId()).orElseThrow();
        assertEquals(NationalFriendlySlots.WEEK, fixture.getWeekNumber());
        assertEquals(NationalFriendlySlots.DAY, fixture.getDayNumber());
        assertEquals(org.example.footballmanager.newLogic.model.MatchType.FRIENDLY, fixture.getMatchType(),
                "a warm-up is a friendly: it decides nothing and is labelled as one");
        assertFalse(fixture.isPlayed());
    }

    @Test
    @Transactional
    @DisplayName("a club is not a national opponent, whichever way round it is asked")
    void aClubCannotBeTheOpponent() {
        assertTrue(service.requestFriendly(seniorA.getId(), club.getId(), SEASON,
                NationalFriendlySlots.WEEK).isEmpty(), "a national side may not ask a club");
        assertTrue(service.requestFriendly(club.getId(), seniorA.getId(), SEASON,
                NationalFriendlySlots.WEEK).isEmpty(), "a club is not on this lane at all");
    }

    @Test
    @Transactional
    @DisplayName("no other week may be booked, whatever is free in it")
    void noOtherWeekMayBeBooked() {
        for (int week = 1; week <= 12; week++) {
            if (week == NationalFriendlySlots.WEEK) continue;
            assertTrue(service.requestFriendly(seniorA.getId(), seniorB.getId(), SEASON, week).isEmpty(),
                    "week " + week + " must refuse: a national friendly is week 6 day 1 and nothing else");
        }
    }

    @Test
    @Transactional
    @DisplayName("the same pair cannot stack two warm-ups on the same day")
    void oneWarmUpPerSidePerDay() {
        assertTrue(service.requestFriendly(seniorA.getId(), seniorB.getId(), SEASON,
                NationalFriendlySlots.WEEK).isPresent());
        assertTrue(service.requestFriendly(seniorA.getId(), seniorB.getId(), SEASON,
                        NationalFriendlySlots.WEEK).isEmpty(),
                "the second ask must be refused, or one day carries two warm-ups");
    }

    @Test
    @Transactional
    @DisplayName("a side already playing that day is busy")
    void aSideWithAFixtureIsBusy() {
        MatchFixture existing = new MatchFixture();
        existing.setHomeTeam(seniorA);
        existing.setAwayTeam(youthA);
        existing.setSeasonYear(SEASON);
        existing.setWeekNumber(NationalFriendlySlots.WEEK);
        existing.setDayNumber(NationalFriendlySlots.DAY);
        existing.setRoundNumber(1);
        existing.setPlayed(false);
        fixtures.save(existing);

        assertTrue(service.requestFriendly(seniorB.getId(), seniorA.getId(), SEASON,
                NationalFriendlySlots.WEEK).isEmpty(),
                "a side that already plays on the only available day is busy");
    }

    @Test
    @Transactional
    @DisplayName("a request is answerable only by the side it was addressed to")
    void onlyTheAddresseeMayAnswer() {
        FriendlyRequest created = service.requestFriendly(seniorA.getId(), seniorB.getId(), SEASON,
                NationalFriendlySlots.WEEK).orElseThrow();
        try {
            service.respond(created.getId(), youthA.getId(), true);
            org.junit.jupiter.api.Assertions.fail("a third side answered a request addressed to someone else");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}