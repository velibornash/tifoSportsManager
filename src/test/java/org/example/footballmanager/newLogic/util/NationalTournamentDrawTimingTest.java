package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.jobs.impl.NationalTournamentDrawJob;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalStage;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The qualifying draw happens at the start of the season, and "Re-draw" really re-draws (owner,
 * 2026-10-06).
 *
 * <p>Two claims that look alike and are not. The owner wants the groups drawn at season start even
 * though the ties are played in week 6, so the manager can see his opponents on the calendar rather
 * than learning them the day before. And the admin button has to produce a genuinely different draw,
 * not re-run an idempotent seeder that correctly reports "already drawn" and changes nothing - which
 * is exactly what it did before this test existed.
 */
class NationalTournamentDrawTimingTest extends BaseTest {

    @Autowired private NationalTournamentDrawJob drawJob;
    @Autowired private NationalTournamentWorldService world;
    @Autowired private NationalTeamCompetitions catalogue;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchFixtureRepository fixtures;

    @Test
    @Transactional
    @DisplayName("week 1 day 1 draws the qualifying groups for both levels, and the ties are still week 6")
    void weekOneDayOneDrawsTheGroups() {
        seedWorld(48);

        drawJob.run(new JobContext(1, 1, 1, 0));

        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            Competition qualifying = catalogue.qualifiers(level).orElseThrow();
            List<MatchFixture> all = fixtures
                    .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(qualifying.getId(), 1);

            assertEquals(120, all.size(), level + ": 8 groups of 6 is 120 ties");
            for (MatchFixture f : all) {
                assertEquals(NationalTournamentSchedule.QUALIFYING_WEEK, f.getWeekNumber(),
                        "drawn in week 1, but played in week 6");
            }
        }
    }

    @Test
    @Transactional
    @DisplayName("week 6 day 1 draws nothing - it used to, which is after the fact for anyone watching")
    void weekSixDayOneDrawsNothing() {
        seedWorld(48);

        drawJob.run(new JobContext(1, 6, 1, 0));

        assertFalse(catalogue.exists(NationalTeamLevel.SENIOR, NationalStage.QUALIFYING),
                "the group draw is a week-1 job, so week 6 creates nothing at all");
        assertTrue(qualifyingFixtures(NationalTeamLevel.SENIOR).isEmpty(),
                "and draws no ties");
    }

    @Test
    @Transactional
    @DisplayName("week 1 day 2 draws nothing either - the draw is one day, not the whole week")
    void weekOneDayTwoDrawsNothing() {
        seedWorld(48);

        drawJob.run(new JobContext(1, 1, 2, 0));

        assertTrue(qualifyingFixtures(NationalTeamLevel.SENIOR).isEmpty(),
                "the draw is on day 1, not on any day of week 1");
    }

    @Test
    @Transactional
    @DisplayName("Re-draw creates the competitions and the groups when nothing was ever drawn")
    void redrawDrawsFromAnEmptyWorld() {
        seedWorld(48);

        NationalTournamentWorldService.Result result = world.forceRedraw();

        assertEquals(2, result.draws().size(), "one draw per level");
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            Competition qualifying = catalogue.qualifiers(level).orElseThrow();
            assertEquals(120, fixtures
                    .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(qualifying.getId(), 1).size(),
                    level + ": the button drew the groups");
        }
    }

    @Test
    @Transactional
    @DisplayName("Re-draw rebuilds the ties it cleared, and deals the same groups because the deal is derived")
    void redrawReplacesAnExistingDraw() {
        seedWorld(48);
        drawJob.run(new JobContext(1, 1, 1, 0));

        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        Set<String> before = groupsOf(qualifying);

        NationalTournamentWorldService.Result result = world.forceRedraw();

        assertEquals(120, fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(qualifying.getId(), 1).size(),
                "still 120 ties, not 240 - the old draw is gone");

        assertEquals(before, groupsOf(qualifying),
                "the deal is derived from the competition, season and pot, so a re-draw reproduces it");
        for (NationalTournamentSeeder.DrawResult draw : result.draws()) {
            assertEquals(120, draw.groupFixtures(), "both levels were drawn again");
        }
    }

    @Test
    @Transactional
    @DisplayName("Re-draw is refused once a qualifying tie is played, and deletes nothing when it refuses")
    void redrawIsRefusedOnceQualifyingHasStarted() {
        seedWorld(48);
        drawJob.run(new JobContext(1, 1, 1, 0));

        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        List<MatchFixture> drawn = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(qualifying.getId(), 1);
        MatchFixture played = drawn.get(0);
        played.setPlayed(true);
        fixtures.save(played);

        IllegalStateException refusal =
                assertThrows(IllegalStateException.class, () -> world.forceRedraw());

        assertTrue(refusal.getMessage().contains("played qualifying tie"),
                "the refusal says why in the terms the owner would use: " + refusal.getMessage());

        // The refusal has to come before the deletion, not after it: a partial re-draw would leave the
        // campaign with one result and no fixtures to finish it.
        List<MatchFixture> after = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(qualifying.getId(), 1);
        assertEquals(120, after.size(), "a refused re-draw deleted nothing - not even the 119 unplayed");
        assertTrue(after.stream().anyMatch(f -> f.getId().equals(played.getId()) && f.isPlayed()),
                "and the result the owner has already seen is still there");
    }

    /** The unplayed group ties of one level, or an empty list when nothing was ever drawn. */
    private List<MatchFixture> qualifyingFixtures(NationalTeamLevel level) {
        return catalogue.qualifiers(level)
                .map(c -> fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(c.getId(), 1))
                .orElse(List.of());
    }

    /** The group membership of one competition, as "group|home-away" strings. */
    private Set<String> groupsOf(Competition qualifying) {
        Set<String> groups = new LinkedHashSet<>();
        for (MatchFixture f : fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                qualifying.getId(), 1)) {
            groups.add(f.getGroupCode() + "|" + f.getHomeTeam().getId() + "-" + f.getAwayTeam().getId());
        }
        return groups;
    }

    // ---------- world ----------

    private void seedWorld(int nations) {
        for (int i = 0; i < nations; i++) {
            Country country = new Country();
            country.setName(String.format("Nation %02d", i));
            country.setIsoCode(String.format("N%02d", i));
            country.setReputation(1000 + i);
            country.setYouthRating(1000 + i);
            country.setState(CountryState.SIMULATED);
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