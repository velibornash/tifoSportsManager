package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-CUPS-3 — 960 clubs were about to enter a cup with no players at all.
 *
 * <p>Qualification is one club per country per cup, so the field is 48 Champions + 96 Masters + 48
 * Challenge = <b>192 per tier</b>, and there are five tiers. **46 of the 48 countries are
 * {@code SIMULATED}**, and {@code PyramidBuilder.buildStatic()} writes their divisions, ratings and
 * standing tables and deliberately <b>no players and no fixtures</b> — that is the whole point of the
 * static world, and it keeps roughly 370,000 player rows off the database.
 *
 * <p>The cups are the first thing in this game that makes <b>two bot clubs play each other</b>.
 * {@code SimMatchService} only generates a squad when exactly one side is human, so a tie between two
 * simulated clubs would reach {@code loadRealSquad()}, get {@code null} for both sides, and be handed to
 * {@code SimTeamFactory.addTeam()} — <b>synthetic placeholder players</b>. The competition would be
 * decided by 22 unnamed stand-ins and the owner's ladder would be invisible, because a synthetic squad
 * has no rating to average.
 *
 * <p>These tests read the <b>generated players</b> and average their skills. Asserting against
 * {@code tierSkill(tier)} would be the trap this whole defect came out of: that number was already
 * correct, it simply was not being applied to anybody.
 */
class CupEntrantSquadTest extends BaseTest {

    @Autowired private LazySquadGenerator squads;
    @Autowired private PlayerRepository players;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CountryRepository countries;

    @Test
    @DisplayName("a simulated country's club is given a squad when it enters a cup")
    void anEmptyClubIsGivenASquad() {
        Team club = aClubIn("Botland", "BL", 1);

        assertTrue(players.findByTeamId(club.getId()).isEmpty(),
                "the simulated world seeds no players; this test no longer describes it if that changed");

        int given = squads.ensureSquadsForCupEntrants(List.of(club));

        assertEquals(1, given);
        List<Player> squad = players.findByTeamId(club.getId());
        assertTrue(squad.size() >= 11,
                "a club with fewer than 11 players has no lineup, and the match falls back to placeholders. Got "
                        + squad.size());
    }

    /**
     * The owner's ladder, on real players: <b>average 12 at tier 1 and one lower per tier below.</b>
     *
     * <p>Asserted over the whole squad's football skills rather than one player's, because
     * {@code skillsForTier} deliberately jitters per skill and per player — the squad averages the tier
     * number, an individual does not. A band of ±2 absorbs the jitter without hiding an off-by-one in the
     * ladder itself.
     */
    @Test
    @DisplayName("the squad averages 12 at tier 1 and one less at every tier below")
    void theTierLadderIsRealAndIsApplied() {
        int[] expected = {12, 11, 10, 9, 8};

        for (int tier = 1; tier <= 5; tier++) {
            Team club = aClubIn("Tierland " + tier, "T" + tier, tier);
            squads.ensureSquadsForCupEntrants(List.of(club));

            List<Player> squad = players.findByTeamId(club.getId());
            assertTrue(squad.size() >= 11, "tier " + tier + " club has no squad");

            double average = averageFootballSkill(squad);
            assertTrue(Math.abs(average - expected[tier - 1]) <= 2.0,
                    "tier " + tier + " should average about " + expected[tier - 1]
                            + " but its squad averages " + round1(average)
                            + " — the tier is read off the club's competition, so a club with no division "
                            + "would be generated at tier 1 no matter what this number says");
        }
    }

    /**
     * The ladder only holds because the tier comes from the club's own division.
     *
     * <p>A Champions Cup entrant is a club in a <b>league</b> division — the cup is not its competition —
     * so the tier has to survive that hop. This asserts it does, because the whole ladder depends on it.
     */
    @Test
    @DisplayName("a club keeps its league tier, even though the cup is not its competition")
    void theTierComesFromTheClubsOwnDivision() {
        Team tierFive = aClubIn("Deepfield", "DP", 5);
        squads.ensureSquadsForCupEntrants(List.of(tierFive));

        double average = averageFootballSkill(players.findByTeamId(tierFive.getId()));
        assertTrue(average < PyramidBuilder.TIER_1_SKILL - 3,
                "a fifth-tier club's squad should be several points below a top-flight one, but it averaged "
                        + round1(average));
    }

    /** Idempotent by squad size, so a repaired world does not accumulate a second set of 25. */
    @Test
    @DisplayName("a club that already has a squad is left alone")
    void anExistingSquadIsNotRebuilt() {
        Team club = aClubIn("Haspace", "HP", 1);
        squads.ensureSquadsForCupEntrants(List.of(club));
        int afterFirst = players.findByTeamId(club.getId()).size();

        assertEquals(0, squads.ensureSquadsForCupEntrants(List.of(club)), "nothing was missing");
        assertEquals(afterFirst, players.findByTeamId(club.getId()).size());
    }

    /**
     * An active country's club already has a squad from {@code PyramidBuilder.build()}.
     *
     * <p>This is why the code carries <b>no {@code CountryState} filter</b>: the owner's decision was
     * "only simulated countries' clubs", and it holds because the clubs that arrive here empty <i>are</i>
     * the simulated ones. A second {@code state == SIMULATED} test would be a second statement of the
     * same fact, free to disagree with the first.
     */
    @Test
    @DisplayName("a club from a simulated country is the one that needs a squad")
    void simulatedCountriesAreTheEmptyOnes() {
        Country simulated = aCountry("Empty State", "ES", CountryState.SIMULATED);
        Competition division = aDivision("ES Division 1", simulated, 1);
        Team club = new Team();
        club.setName("ES " + System.nanoTime());
        club.setCountry(simulated);
        club.setCompetition(division);
        club.setReputation(40.0);
        club.setHumanControlled(false);
        club = teams.save(club);

        int given = squads.ensureSquadsForCupEntrants(List.of(club));

        assertEquals(1, given);
        assertFalse(players.findByTeamId(club.getId()).isEmpty());
    }

    // ---------- helpers ----------

    /**
     * The mean of the squad's football skills — the skills a club is actually judged on.
     *
     * <p>Not {@code careerRating()}, because the rating is an 0-100 number derived from the same skills
     * and would be a second unit to keep in step. This is the ladder the owner stated, in the units he
     * stated it in.
     */
    private double averageFootballSkill(List<Player> squad) {
        List<SkillName> football = List.of(SkillName.PACE, SkillName.STAMINA, SkillName.DEFENDER,
                SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER);
        double total = 0.0;
        int counted = 0;
        for (Player player : squad) {
            if (player.getSkills() == null) {
                continue;
            }
            for (SkillName skill : football) {
                total += player.getSkills().visibleInt(skill);
                counted++;
            }
        }
        assertTrue(counted > 0, "the squad has no readable skills, so there is nothing to average");
        return total / counted;
    }

    private static String round1(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private Team aClubIn(String countryName, String iso, int tier) {
        Country country = aCountry(countryName, iso, CountryState.SIMULATED);
        Competition division = aDivision(iso + " Division " + tier, country, tier);
        Team club = new Team();
        club.setName(iso + " FC " + tier + " " + System.nanoTime());
        club.setCountry(country);
        club.setCompetition(division);
        club.setReputation(30.0 + tier);
        club.setBudget(1_000_000.0);
        club.setHumanControlled(false);
        return teams.save(club);
    }

    private Competition aDivision(String name, Country country, int tier) {
        Competition division = new Competition();
        division.setName(name + " " + System.nanoTime());
        division.setType(CompetitionType.LEAGUE);
        division.setScope(CompetitionScope.NATIONAL);
        division.setTeamType(CompetitionTeamType.CLUB);
        division.setCountry(country);
        division.setTier(tier);
        division.setDivisionLevel(1);
        return competitions.save(division);
    }

    private Country aCountry(String name, String iso, CountryState state) {
        Country country = new Country();
        country.setName(name + " " + System.nanoTime());
        country.setIsoCode(iso);
        country.setReputation(1500);
        country.setState(state);
        return countries.save(country);
    }
}