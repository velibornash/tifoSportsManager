package org.example.footballmanager.newLogic.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The World page's cost must not grow with the size of the world.
 *
 * <p>The owner reported that clicking World was very slow on the Oracle instance, and it was not the
 * network. {@code InternationalClubCups.summarise} asked fifteen cups to read the finished season's
 * tables when there are only three distinct sets of them — three cups per tier, all off the same
 * divisions. It then read each division with two queries of its own, and tier 5 has sixteen divisions
 * in each of forty-eight countries.
 *
 * <p>That is roughly <b>nine thousand queries for one page view</b>, which is invisible in the source
 * and obvious on the wire. Nothing about the code said "slow"; the arithmetic said it.
 *
 * <p>So this asserts the shape rather than a number: adding divisions to a tier must not add queries.
 * A count would rot the moment the repository grows one more lookup; the invariant is that the work is
 * per <em>tier</em> and not per division, and that is what this locks.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class InternationalClubCupsQueryBudgetTest {

    @Autowired private InternationalClubCups cups;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private MatchRepository matches;
    @Autowired private CompetitionRepository competitions;
    @Autowired private SeasonCompetitionRepository seasonRows;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private static final int TIER = 3;

    @Test
    @DisplayName("adding divisions to a tier costs no extra queries on the World page")
    void theCostIsPerTierAndNotPerDivision() {
        Country country = transactions.execute(status -> aCountry("CLB"));

long withOneDivision = measure(country);
        assertTrue(withOneDivision > 0,
                "no query was recorded at all, so the statistics are not measuring this test's work");

        // Two steps, and the second one is the one that matters. The first only says a small world and a
        // slightly bigger one are the same size; the second says the cost does not move at all as the
        // world grows, which is the property the Oracle instance broke. Measured here: 1 division 20
        // queries, 16 divisions 22, 64 divisions 22.
        transactions.executeWithoutResult(status -> divisions(country, 15));
        long withSixteenDivisions = measure(country);
        transactions.executeWithoutResult(status -> divisions(country, 48));
        long withSixtyFourDivisions = measure(country);

        assertTrue(withSixteenDivisions <= withOneDivision + 3,
                "sixteen divisions in one tier cost " + (withSixteenDivisions - withOneDivision)
                        + " more queries than one did, so the entry replay is still asking the database "
                        + "per division");
        assertTrue(withSixtyFourDivisions <= withSixteenDivisions + 3,
                "sixty-four divisions cost " + (withSixtyFourDivisions - withSixteenDivisions)
                        + " more queries than sixteen did. The cost has to be flat as the world grows - "
                        + "that is what made the World page crawl on the Oracle instance with its 1,557 "
                        + "competitions and 14,570 clubs, and it would come back the moment the world did");
    }

    @Test
    @DisplayName("the fifteen cups still report their qualified counts")
    void theCountsAreStillRight() {
        Country country = transactions.execute(status -> aCountry("CLC"));
        transactions.executeWithoutResult(status -> divisions(country, 3));

        var summaries = cups.summarise(1);

        assertTrue(summaries.size() >= 3,
                "expected the club cups to be summarised, got " + summaries.size());
        long tierThree = summaries.stream().filter(s -> s.tier() == TIER).count();
        assertTrue(tierThree >= 3,
                "tier " + TIER + " should report its Champions, Masters and Challenge cups, got " + tierThree);
        // The optimisation must not have emptied anything: this country's three divisions each have a
        // winner, so every tier-" + TIER + " cup has somebody to name.
        summaries.stream()
                .filter(s -> s.tier() == TIER)
                .forEach(s -> assertTrue(s.qualified() > 0,
                        s.name() + " reports nobody qualified, so reading a tier once broke the selection"));
    }

    /** How many statements one {@code summarise} issued. */
    private long measure(Country country) {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        cups.summarise(1);
        return statistics.getQueryExecutionCount();
    }

    private Country aCountry(String iso) {
        Country country = new Country();
        country.setName("ZZ Budget " + iso);
        country.setIsoCode(iso);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    /**
     * N divisions of one tier in one country, each with a played table of four clubs.
     *
     * <p>Four, not two, and that is the fixture earning its keep rather than being fussy: the Challenge
     * Cup takes the best of the fourth-placed clubs, so a two-club table has nobody for it and the cup
     * would report zero qualified for a reason that has nothing to do with what is being tested.
     */
    private void divisions(Country country, int howMany) {
        for (int i = 0; i < howMany; i++) {
            Competition division = new Competition();
            division.setName("ZZ Budget Div " + i + " " + country.getId());
            division.setType(CompetitionType.LEAGUE);
            division.setScope(CompetitionScope.NATIONAL);
            division.setTeamType(CompetitionTeamType.CLUB);
            division.setTier(TIER);
            division.setDivisionLevel(i + 1);
            division.setTeamsPerCompetition(10);
            division.setCountry(country);
            division = competitions.save(division);

            Team home = club(country, "ZZ Budget Home " + i + " " + division.getId());
            Team away = club(country, "ZZ Budget Away " + i + " " + division.getId());
            Team third = club(country, "ZZ Budget Third " + i + " " + division.getId());
            Team fourth = club(country, "ZZ Budget Fourth " + i + " " + division.getId());

            Match match = new Match();
            match.setCompetition(division);
            match.setHomeTeam(home);
            match.setAwayTeam(away);
            match.setPlayed(true);
            match.setFinished(true);
            match.setHomeGoals(2);
            match.setAwayGoals(0);
            match.setSeasonYear(1);
            match.setWeekNumber(1);
            match.setMatchDate(LocalDateTime.of(2026, 4, 1, 20, 45));
            match.setEventJson("[]");
            match.setStatsJson("{}");
            matches.save(match);

            // A season row and a played table, because entry is decided by the FINISHED season. Without
            // one the tier has no tables at all and the whole measurement is of nothing - which is the
            // trap this file exists because of.
            SeasonCompetition seasonRow = new SeasonCompetition();
            seasonRow.setCompetition(division);
            seasonRow.setSeasonYear(1);
            seasonRow = seasonRows.save(seasonRow);

            tableRow(seasonRow, home, 3, 2, 0, 1);
            tableRow(seasonRow, away, 1, 1, 1, 2);
            tableRow(seasonRow, third, 1, 1, 1, 3);
            tableRow(seasonRow, fourth, 0, 0, 2, 4);
        }
    }

    private void tableRow(SeasonCompetition seasonRow, Team team, int points, int forGoals,
                          int againstGoals, int position) {
        CompetitionEntry row = new CompetitionEntry();
        row.setSeasonCompetition(seasonRow);
        row.setTeam(team);
        row.setPoints(points);
        row.setGoalsScored(forGoals);
        row.setGoalsConceded(againstGoals);
        row.setPosition(position);
        entries.save(row);
    }

    private Team club(Country country, String name) {
        Team club = new Team();
        club.setName(name + " FC");
        club.setCountry(country);
        club.setHumanControlled(false);
        club.setReputation(50.0);
        return teams.save(club);
    }
}
