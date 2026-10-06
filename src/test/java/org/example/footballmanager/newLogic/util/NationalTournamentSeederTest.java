package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-10 / P2-12 — the owner's qualifying draw: 48 nations, 8 groups of 6, week 6, day by day.
 *
 * <p>The numbers asserted here are the owner's, not convenient ones: eight groups of six, five
 * matchdays, one matchday a day on days 2 to 6, and the top two of each group through. A draw that
 * produced six groups of eight, or spread the five matchdays across weeks 5 to 7, would still be a
 * plausible-looking tournament and these are the assertions that would catch it.
 */
class NationalTournamentSeederTest extends BaseTest {

    @Autowired private NationalTeamCompetitions catalogue;
    @Autowired private NationalTournamentSeeder seeder;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchFixtureRepository fixtures;

    private static final int SEASON = 1;

    @Test
    @Transactional
    @DisplayName("48 nations become 8 groups of 6, five matchdays each, on days 2-6 of week 6")
    void eightGroupsOfSixOnFiveConsecutiveDays() {
        seedWorld(48);
        catalogue.ensureAll();

        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        NationalTournamentSeeder.DrawResult result = seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
        assertTrue(result.note().equals("drawn"), "the group stage drew: " + result.note());

        List<MatchFixture> all = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(qualifying.getId(), SEASON);

        // 8 groups of 6, single round robin: 8 x (6 choose 2) = 120 ties.
        assertEquals(120, all.size(), "8 groups of 6 play 15 ties each");

        Map<String, List<MatchFixture>> byGroup = new LinkedHashMap<>();
        for (MatchFixture f : all) {
            byGroup.computeIfAbsent(f.getGroupCode(), key -> new ArrayList<>()).add(f);
        }
        assertEquals(8, byGroup.size(), "eight groups");
        byGroup.forEach((code, groupFixtures) -> assertEquals(15, groupFixtures.size(),
                "group " + code + " plays fifteen ties"));

        for (MatchFixture f : all) {
            assertEquals(NationalTournamentSchedule.QUALIFYING_WEEK, f.getWeekNumber(),
                    "qualifying is week 6 and nothing else");
            assertTrue(f.getDayNumber() >= 2 && f.getDayNumber() <= 6,
                    "a qualifying tie falls on days 2-6, not " + f.getDayNumber());
            assertEquals(SEASON, f.getSeasonYear(), "a season is a number counted from 1, not a year");
        }

        // Five matchdays, and each one is a single day.
        Set<Integer> matchdays = new LinkedHashSet<>();
        for (MatchFixture f : all) {
            matchdays.add(f.getRoundNumber());
            assertEquals(NationalTournamentSchedule.qualifyingDay(f.getRoundNumber()), f.getDayNumber(),
                    "matchday " + f.getRoundNumber() + " has exactly one day");
        }
        assertEquals(Set.of(1, 2, 3, 4, 5), matchdays, "five matchdays");
    }

    @Test
    @Transactional
    @DisplayName("every group takes one nation from each pot, so no group collects two of the top eight")
    void oneNationPerPotInEveryGroup() {
        seedWorld(48);
        catalogue.ensureAll();

        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);

        Map<Long, Integer> ratingOf = new LinkedHashMap<>();
        for (NationalTournamentSeeder.Entrant entrant : seeder.rankedField(NationalTeamLevel.SENIOR)) {
            ratingOf.put(entrant.team().getId(), entrant.rating());
        }
        List<Integer> rankedRatings = ratingOf.values().stream().sorted((a, b) -> b - a).toList();

        Map<String, Set<Integer>> potsPerGroup = new LinkedHashMap<>();
        for (MatchFixture f : fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                qualifying.getId(), SEASON)) {
            Set<Integer> pots = potsPerGroup.computeIfAbsent(f.getGroupCode(), key -> new LinkedHashSet<>());
            pots.add(potOf(ratingOf.get(f.getHomeTeam().getId()), rankedRatings));
            pots.add(potOf(ratingOf.get(f.getAwayTeam().getId()), rankedRatings));
        }
        potsPerGroup.forEach((code, pots) -> assertEquals(6, pots.size(),
                "group " + code + " must hold one nation from each of the six pots, not " + pots));
    }

    @Test
    @Transactional
    @DisplayName("the worse-rated nation hosts every tie, so it is always the weaker team's stadium")
    void theWorseRatedSideHosts() {
        seedWorld(48);
        catalogue.ensureAll();

        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);

        Map<Long, Integer> ratingOf = new LinkedHashMap<>();
        for (NationalTournamentSeeder.Entrant entrant : seeder.rankedField(NationalTeamLevel.SENIOR)) {
            ratingOf.put(entrant.team().getId(), entrant.rating());
        }

        for (MatchFixture f : fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                qualifying.getId(), SEASON)) {
            int home = ratingOf.get(f.getHomeTeam().getId());
            int away = ratingOf.get(f.getAwayTeam().getId());
            assertTrue(home <= away,
                    f.getHomeTeam().getName() + " (" + home + ") hosts " + f.getAwayTeam().getName()
                            + " (" + away + "): the worse-rated side must be at home");
        }
    }

    @Test
    @Transactional
    @DisplayName("a second pass draws nothing further - the fixtures are the record of the draw")
    void drawingTwiceAddsNothing() {
        seedWorld(48);
        catalogue.ensureAll();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        long after = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                qualifying.getId(), SEASON).size();

        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);

        assertEquals(after, fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                qualifying.getId(), SEASON).size(), "a re-run must not add a second set of ties");
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 are separate competitions, each with its own field")
    void seniorAndU21AreSeparateCompetitions() {
        seedWorld(48);
        List<Competition> created = catalogue.ensureAll();

        assertEquals(4, created.size(), "qualifiers and a tournament for each of the two levels");
        assertNotNull(catalogue.qualifiers(NationalTeamLevel.SENIOR).orElse(null));
        assertNotNull(catalogue.tournament(NationalTeamLevel.SENIOR).orElse(null));
        assertNotNull(catalogue.qualifiers(NationalTeamLevel.U21).orElse(null));
        assertNotNull(catalogue.tournament(NationalTeamLevel.U21).orElse(null));

        for (Competition c : created) {
            assertEquals(CompetitionTeamType.NATIONAL_TEAM, c.getTeamType());
            assertNotNull(c.getNationalLevel(), "the level is a column, never read off the name");
            assertNotNull(c.getNationalStage(), "the stage is a column, never read off the name");
        }
    }

    /** Which pot a rating falls in, for the one-per-pot assertion. */
    private int potOf(int rating, List<Integer> rankedRatings) {
        return rankedRatings.indexOf(rating) / 8;
    }

    // ---------- world ----------

    private void seedWorld(int nations) {
        for (int i = 0; i < nations; i++) {
            Country country = new Country();
            country.setName(String.format("Nation %02d", i));
            country.setIsoCode(String.format("N%02d", i));
            country.setReputation(1000 + i);
            country.setYouthRating(1000 + i);
            country.setState(org.example.footballmanager.newLogic.model.CountryState.SIMULATED);
            Country saved = countries.save(country);

            saved.setSeniorNationalTeam(side(saved, "Senior"));
            saved.setU21NationalTeam(side(saved, "U-21"));
            countries.save(saved);
        }
    }

    private Team side(Country country, String label) {
        Team team = new Team();
        team.setName(country.getName() + " " + label);
        team.setType(CompetitionTeamType.NATIONAL_TEAM);
        team.setCountry(country);
        team.setReputation((double) country.getReputation());
        team.setHumanControlled(false);
        Team saved = teams.save(team);
        for (int p = 1; p <= 25; p++) {
            players.save(player(label + " " + p, saved));
        }
        return saved;
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
}