package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is in the three international club cups (owner, 2026-09-30).
 *
 * <p>The World page listed Champions, Masters and Challenge as "Not created yet" since the page was
 * written. The competitions were missing; so was the rule that says who may enter them, which is the part
 * that would have turned "a competition exists" into "a competition means something".
 */
class InternationalClubCupsTest extends BaseTest {

    @Autowired private InternationalClubCups cups;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository seasonCompetitions;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private CountryRepository countries;

    @BeforeEach
    void create() {
        cups.ensureCompetitionsDurably();
    }

    @Test
    @DisplayName("the three cups exist, and are international club competitions")
    void theCupsExist() {
        for (InternationalClubCups.Cup cup : InternationalClubCups.CUPS) {
            Competition competition = find(cup.name());
            assertNotNull(competition, cup.name() + " was not created");
            assertEquals(CompetitionType.CUP, competition.getType());
            // INTERNATIONAL is the whole difference from a national cup: the entrants come from many
            // countries, and this is the only column that says so.
            assertEquals(CompetitionScope.INTERNATIONAL, competition.getScope(),
                    cup.name() + " is scoped as a national cup, so nothing distinguishes it from one");
        }
    }

    @Test
    @DisplayName("the owner's bands: winners, second and third, fourth")
    void theBandsAreTheOwners() {
        assertEquals(1, bandOf(InternationalClubCups.CHAMPIONS).placesFrom());
        assertEquals(1, bandOf(InternationalClubCups.CHAMPIONS).placesTo(), "Champions is the winners");
        assertEquals(2, bandOf(InternationalClubCups.MASTERS).placesFrom());
        assertEquals(3, bandOf(InternationalClubCups.MASTERS).placesTo(), "Masters is the second and third");
        assertEquals(4, bandOf(InternationalClubCups.CHALLENGE).placesFrom());
        assertEquals(4, bandOf(InternationalClubCups.CHALLENGE).placesTo(), "Challenge is the fourth");
    }

    @Test
    @DisplayName("a division of ten puts one club in each of the three cups")
    void oneDivisionContributesToEveryCup() {
        Country country = countries.save(country("ZZ Cup", "ZZC"));
        Competition league = competitions.save(league("ZZ Cup League", country, 10));
        fillTable(league, country, 10);

        List<Team> champions = cups.qualifiedFor(bandOf(InternationalClubCups.CHAMPIONS), 1);
        List<Team> masters = cups.qualifiedFor(bandOf(InternationalClubCups.MASTERS), 1);
        List<Team> challenge = cups.qualifiedFor(bandOf(InternationalClubCups.CHALLENGE), 1);

        // Filtered by the country, not by a name prefix: the qualifier is about a club belonging to this
        // division's country, and a test that depended on how the name is built would be testing the
        // naming rather than the rule.
        assertEquals(1, from(champions, country), "a ten-club division should send exactly one champion");
        assertEquals(2, from(masters, country),
                "a ten-club division should send its second and third to the Masters Cup");
        assertEquals(1, from(challenge, country),
                "a ten-club division should send its fourth to the Challenge Cup");
    }

    @Test
    @DisplayName("the clubs in the three cups do not overlap")
    void noClubIsInTwoCups() {
        Country country = countries.save(country("ZZ Overlap", "ZZO"));
        Competition league = competitions.save(league("ZZ Overlap League", country, 10));
        fillTable(league, country, 10);

        Set<Long> all = new HashSet<>();
        for (InternationalClubCups.Cup cup : InternationalClubCups.CUPS) {
            for (Team team : cups.qualifiedFor(cup, 1)) {
                assertTrue(all.add(team.getId()),
                        team.getName() + " qualifies for two cups, so it would have to be drawn twice");
            }
        }
    }

    @Test
    @DisplayName("entry is decided by the table as it stands, not by the stored position column")
    void entryUsesTheRealTableOrder() {
        // The stored `position` column is written by the table's own read path and can be stale. Reading
        // entry through it would qualify a club that finished fourth for the Champions Cup.
        Country country = countries.save(country("ZZ Stale", "ZZS"));
        Competition league = competitions.save(league("ZZ Stale League", country, 4));
        List<Team> table = fillTable(league, country, 4);

        // Deliberately wrong: the column says the last-placed club won.
        CompetitionEntry lastEntry = entries.findBySeasonCompetitionAndTeam(
                seasonCompetitionOf(league), table.get(3)).orElseThrow();
        lastEntry.setPosition(1);
        entries.save(lastEntry);

        List<Team> champions = cups.qualifiedFor(bandOf(InternationalClubCups.CHAMPIONS), 1);
        assertTrue(champions.stream().noneMatch(t -> t.getId().equals(table.get(3).getId())),
                "a club whose stored position says first was entered for the Champions Cup on the "
                        + "strength of a column that does not mean what it says");
        assertTrue(champions.stream().anyMatch(t -> t.getId().equals(table.get(0).getId())),
                "the actual table winner is not in the Champions Cup");
    }

    @Test
    @DisplayName("a season nobody has finished yet qualifies nobody, and says so")
    void anUnfinishedSeasonQualifiesNobody() {
        List<InternationalClubCups.CupSummary> summaries = cups.summarise(99);
        for (InternationalClubCups.CupSummary summary : summaries) {
            assertEquals(0, summary.qualified(),
                    summary.name() + " found entrants in a season that has not been played");
        }
    }

    @Test
    @DisplayName("creating them twice does not create them twice")
    void creationIsIdempotent() {
        long before = competitions.findAll().stream()
                .filter(c -> InternationalClubCups.CUPS.stream()
                        .anyMatch(cup -> cup.name().equals(c.getName())))
                .count();

        cups.ensureCompetitionsDurably();
        cups.ensureCompetitionsDurably();

        long after = competitions.findAll().stream()
                .filter(c -> InternationalClubCups.CUPS.stream()
                        .anyMatch(cup -> cup.name().equals(c.getName())))
                .count();
        assertEquals(before, after,
                "a second run created more cups; this runs on every boot, so a non-idempotent seeder "
                        + "would slowly fill the world with Champions Cups");
    }

    @Test
    @DisplayName("reading the cups does not create anything")
    void readingIsNotWriting() {
        // A GET on the world page issued an INSERT, and the endpoint is transactional read-only, so the
        // whole page failed with "cannot execute INSERT in a read-only transaction". A read that writes
        // is wrong twice: it breaks, and it makes the page work only if it happens to run first.
        long before = competitions.count();
        cups.summarise(1);
        assertEquals(before, competitions.count(), "reading the cups created a competition");
    }

    // --- helpers ---

    private long from(List<Team> qualified, Country country) {
        return qualified.stream()
                .filter(team -> team.getCountry() != null
                        && country.getId().equals(team.getCountry().getId()))
                .count();
    }

    private Competition find(String name) {
        return competitions.findAll().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElse(null);
    }

    private InternationalClubCups.Cup bandOf(String name) {
        return InternationalClubCups.CUPS.stream()
                .filter(cup -> cup.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private SeasonCompetition seasonCompetitionOf(Competition league) {
        return seasonCompetitions.findByCompetitionAndSeasonYear(league, 1).orElseThrow();
    }

    /**
     * Clubs in a division with a season-one table, ordered by points.
     *
     * <p>The points are the point: a table is ordered by what the clubs actually did, and the test that
     * matters is the one where the stored position column disagrees with it.
     */
    private List<Team> fillTable(Competition league, Country country, int size) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(league);
        sc.setSeasonYear(1);
        sc.setFinished(false);
        sc = seasonCompetitions.save(sc);

        List<Team> table = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            Team team = new Team();
            team.setName(league.getName().replace(" League", "") + " FC" + (i + 1));
            team.setCountry(country);
            team.setCompetition(league);
            team.setHumanControlled(false);
            team = teams.save(team);
            table.add(team);

            CompetitionEntry entry = new CompetitionEntry();
            entry.setSeasonCompetition(sc);
            entry.setTeam(team);
            // Descending, so the first row is the champion and the last is the one in the Challenge Cup.
            entry.setPoints(size - i);
            entry.setWins(0);
            entry.setDraws(0);
            entry.setLosses(0);
            entry.setGoalsScored(0);
            entry.setGoalsConceded(0);
            entry.setPosition(0);
            entries.save(entry);
        }
        return table;
    }

    private Competition league(String name, Country country, int teamsPerDivision) {
        Competition competition = new Competition();
        competition.setName(name);
        competition.setType(CompetitionType.LEAGUE);
        competition.setScope(CompetitionScope.NATIONAL);
        competition.setCountry(country);
        competition.setTier(1);
        competition.setDivisionLevel(1);
        competition.setTeamsPerCompetition(teamsPerDivision);
        return competition;
    }

    private Country country(String name, String iso) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode(iso);
        country.setReputation(1500);
        country.setYouthRating(1500);
        country.setState(CountryState.SIMULATED);
        return country;
    }
}
