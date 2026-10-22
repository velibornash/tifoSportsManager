package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.UniqueIsoCodes;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionScope;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The form snapshot must not fetch the blob — and the query that fetches it must actually run.
 *
 * <p><b>What went wrong (found 2026-10-19, owner report).</b> {@code RecentMatchRow} was added with an
 * alias projection —
 *
 * <pre>
 *   SELECT m.id AS id, m.matchDate AS matchDate, m.homeTeam.id AS homeTeamId, ...
 * </pre>
 *
 * — and that form does not work. Spring Data runs an alias query as a <b>tuple</b> query and hands back a
 * {@code TupleBackedMap} per row, and it has no converter from that map to a record. Every call died with
 *
 * <pre>
 *   No converter found capable of converting from type
 *   [org.springframework.data.jpa.repository.query.AbstractJpaQuery$TupleConverter$TupleBackedMap]
 *   to type [org.tifo.footballmanager.newLogic.repository.RecentMatchRow]
 * </pre>
 *
 * and the dashboard's <b>Next Match</b> panel went down with it. The five {@code ScoredMatch} queries in
 * the same repository have always used {@code SELECT new ...}; this one was the only alias projection in
 * the codebase, and it was the only one that was broken.
 *
 * <p><b>Why this test runs the query and does not only read its shape.</b> The sibling test
 * ({@code ScoredMatchCarriesNoBlobTest}) is a reflection test: it checks that the record has no blob
 * field and that the JPQL does not mention one. That kind of test <b>passed against the broken query</b>,
 * because a query that cannot run is still a query that selects no blob. A shape test cannot see a
 * missing converter.
 *
 * <p>So the first test here actually calls the repository method against the database. It is the only
 * thing that would have caught this, and it is the thing that was missing.
 */
class RecentMatchRowCarriesNoBlobTest extends BaseTest {

    /** The three columns on {@code Match} that hold a whole match's worth of JSON. */
    private static final List<String> BLOB_PROPERTIES = List.of("eventJson", "lineupJson", "statsJson");

    private static final int SEASON = 1;

    @Autowired private MatchRepository matches;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("the form snapshot's query runs and hands back the columns it promises")
    void theQueryRunsAndReturnsTheRow() {
        Match played = aPlayedMatch("ZZ Form row", 3, 1, LocalDateTime.of(2026, 2, 1, 19, 0));
        Team other = aTeam("ZZ Form other " + System.nanoTime(), played.getHomeTeam().getCountry());

        List<RecentMatchRow> rows = matches.findPlayedResultRowsInvolvingAnyOf(
                List.of(played.getHomeTeam().getId(), other.getId()));

        RecentMatchRow row = rows.stream()
                .filter(r -> played.getId().equals(r.id()))
                .findFirst()
                .orElse(null);

        assertNotNull(row,
                "the form snapshot's query returned no row for a played match. The query was an alias "
                        + "projection, which Spring Data runs as a tuple query and cannot convert to a "
                        + "record, so every call threw instead of returning anything");
        assertEquals(3, row.homeGoals(), "the home score came back wrong");
        assertEquals(1, row.awayGoals(), "the away score came back wrong");
        assertEquals(played.getHomeTeam().getId(), row.homeTeamId(), "the home side's id came back wrong");
        assertEquals(played.getAwayTeam().getId(), row.awayTeamId(), "the away side's id came back wrong");
        assertEquals(LocalDateTime.of(2026, 2, 1, 19, 0), row.matchDate(), "the date came back wrong");
    }

    @Test
    @Transactional
    @DisplayName("a match the query does not cover is not returned")
    void theQueryOnlyReturnsTheTeamsItWasAskedAbout() {
        Match mine = aPlayedMatch("ZZ Form mine", 1, 0, LocalDateTime.of(2026, 2, 2, 19, 0));
        Match theirs = aPlayedMatch("ZZ Form theirs", 9, 9, LocalDateTime.of(2026, 2, 3, 19, 0));

        List<RecentMatchRow> rows = matches.findPlayedResultRowsInvolvingAnyOf(
                List.of(mine.getHomeTeam().getId()));

        assertTrue(rows.stream().noneMatch(r -> theirs.getId().equals(r.id())),
                "the query returned a match that does not involve the team it was asked about");
        assertTrue(rows.stream().anyMatch(r -> mine.getId().equals(r.id())),
                "the query did not return the match it was asked about");
    }

    @Test
    @DisplayName("the projection has no field that could hold the blob")
    void theProjectionCarriesNoBlobField() {
        List<String> components = Arrays.stream(RecentMatchRow.class.getRecordComponents())
                .map(c -> c.getName())
                .toList();

        assertEquals(List.of("id", "matchDate", "homeTeamId", "awayTeamId", "homeGoals", "awayGoals"),
                components,
                "RecentMatchRow changed shape. The form snapshot reads a date, two sides and a score; a "
                        + "field here is a field the snapshot will select, and the blob is 742 KB a match.");

        for (String blob : BLOB_PROPERTIES) {
            assertFalse(components.contains(blob),
                    "RecentMatchRow now has a " + blob + " field, which puts the blob back on the heap.");
        }
    }

    @Test
    @DisplayName("the query selects no blob column")
    void theQuerySelectsNoBlobColumn() throws Exception {
        Method method = find("findPlayedResultRowsInvolvingAnyOf");
        String jpql = method.getAnnotation(Query.class).value().toLowerCase(Locale.ROOT);

        for (String blob : BLOB_PROPERTIES) {
            assertFalse(jpql.contains(blob.toLowerCase(Locale.ROOT)),
                    "the form snapshot's query selects " + blob + ". It does not read it and it is 742 KB "
                            + "a match; see RecentMatchRow.");
        }
    }

    /**
     * The query has to be a constructor expression.
     *
     * <p>This is the assertion that would have caught the defect, and it is a shape assertion about the
     * JPQL rather than about the record: an alias projection ({@code SELECT m.id AS id, ...}) is run as a
     * tuple query and has no converter to a record, so the query cannot be executed at all.
     */
    @Test
    @DisplayName("the query is a constructor expression, not an alias projection")
    void theQueryIsAConstructorExpression() throws Exception {
        String jpql = find("findPlayedResultRowsInvolvingAnyOf").getAnnotation(Query.class).value();

        assertTrue(jpql.contains("SELECT new " + RecentMatchRow.class.getName()),
                "the form snapshot's query is not a constructor expression. An alias projection is run as a "
                        + "tuple query and Spring Data has no converter from a TupleBackedMap to a record, "
                        + "so the query throws instead of returning rows. Every other DTO projection in "
                        + "this repository uses SELECT new ... for exactly that reason.");
    }

    private Method find(String name) {
        return Arrays.stream(MatchRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is gone from MatchRepository"));
    }

    // --- fixtures ----------------------------------------------------------

    private Match aPlayedMatch(String name, int homeGoals, int awayGoals, LocalDateTime when) {
        Country country = new Country();
        country.setName(name + " country " + System.nanoTime());
        country.setIsoCode(UniqueIsoCodes.next());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition league = new Competition();
        league.setName(name + " league " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setTeamType(CompetitionTeamType.CLUB);
        league.setTier(1);
        league.setCountry(country);
        league = competitions.save(league);

        Team home = aTeam(name + " home " + System.nanoTime(), country);
        Team away = aTeam(name + " away " + System.nanoTime(), country);

        Match match = new Match();
        match.setCompetition(league);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setPlayed(true);
        match.setFinished(true);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setSeasonYear(SEASON);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setMatchDate(when);
        return matches.save(match);
    }

    private Team aTeam(String name, Country country) {
        Team team = new Team();
        team.setName(name);
        team.setCountry(country);
        return teams.save(team);
    }
}
