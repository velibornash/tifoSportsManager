package org.example.footballmanager.newLogic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Do the club Elo ratings actually reach the database?
 *
 * <p>Everything in {@code ClubRatingServiceTest} asserts on entities inside the test's own transaction,
 * where Hibernate's dirty checking makes a missing {@code save()} completely invisible. This class is
 * the one that can catch it: {@code recomputeDurably()} is called outside any test transaction, and
 * every read afterwards happens in a fresh transaction, so the only way a number can differ is if the
 * write was committed.
 *
 * <p>The same reasoning settled {@code dataFixSuggestions.md} §1.1, where a source-reading audit
 * reported a missing save as a lost write and the database disagreed.
 */
/**
 * The last property is not optional. Without {@code generate_statistics} Hibernate returns a no-op
 * statistics object, so {@link #countWrites()} reads zero, the comparison below is 0 == 0, and the test
 * is green while measuring nothing — which is the exact failure this class was written to catch, and
 * which it caught in itself on the first attempt.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class ClubRatingPersistenceTest {

    @Autowired private ClubRatingService ratings;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private MatchRepository matches;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TransactionTemplate transactions;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("the ratings, the previous value and the delta all survive the write")
    void clubEloIsCommitted() {
        MatchPlay play = transactions.execute(status -> buildAHomeWin("CLP", "ZZ Persist"));

        ClubRatingService.Result result = ratings.recomputeDurably();
        assertTrue(result.matchesReplayed() >= 1,
                "no match was replayed, so this test measured nothing — it needs a played club match "
                        + "that the replay can see");

        // A fresh transaction each time, so nothing comes back out of a first-level cache.
        Double winnerRating = readRating(play.winnerId());
        Double winnerPrevious = readPrevious(play.winnerId());
        Double winnerDelta = readDelta(play.winnerId());
        Double loserRating = readRating(play.loserId());

        assertNotNull(winnerRating, "the winner's rating is null after a re-read from the database, so "
                + "recompute() mutated an entity and never committed it");
        assertNotNull(winnerPrevious, "elo_previous_rating is null after a re-read");
        assertNotNull(winnerDelta, "elo_delta is null after a re-read");
        assertTrue(winnerRating > 1500.0, "the winner is rated " + winnerRating + " in the database");
        assertTrue(loserRating < 1500.0, "the loser is rated " + loserRating + " in the database");
        assertEquals(winnerRating - winnerPrevious, winnerDelta, 0.0001,
                "the stored delta is not rating - previous, so the ranking tables would print a number "
                        + "that means nothing");
    }

    @Test
    @DisplayName("a settled replay issues no UPDATE at all")
    void aSettledReplayIssuesNoUpdates() {
        MatchPlay play = transactions.execute(status -> buildAHomeWin("CLR", "ZZ Quiet"));

        clearStatistics();
        ratings.recomputeDurably();
        int firstRun = countUpdates();

        clearStatistics();
        ratings.recomputeDurably();
        int secondRun = countUpdates();

        assertTrue(firstRun > 0,
                "the first replay wrote nothing at all, so there is no baseline and this test would pass "
                        + "by comparing two zeroes");
        assertEquals(0, secondRun,
                "a replay that changes no rating still issued " + secondRun + " UPDATE(s). It runs after "
                        + "every matchday, so this is the difference between touching the clubs that "
                        + "actually played and touching every club in the world");

        // Not an assertion, a note on why this test exists and what it does NOT prove.
        //
        // The obvious way to read a failure here is "the `changed` guard is broken". It is not what
        // protects the database: the clubs are loaded inside the replay's own transaction, so they are
        // managed, `save()` on a managed entity is a merge that does nothing, and Hibernate skips an
        // UPDATE whose columns are unchanged. Reinstating the boxed-`Double` comparison this guard used
        // to have — `stored == round(computed)` with a boxed `round`, which compared references — still
        // issues zero UPDATEs. The audit that reported that as ~14,880 rewrites per matchday was wrong
        // about the consequence, though right that the comparison itself was broken.
    }

    /**
     * How many UPDATEs the replay actually issued.
     *
     * <p>Hibernate statistics, so this counts statements rather than inferring them from values.
     * Asserting on the numbers alone cannot see a wasted write: rewriting a row with an identical value
     * changes nothing a reader can observe.
     */
    private int countUpdates() {
        return (int) statistics().getEntityUpdateCount();
    }

    private void clearStatistics() {
        statistics().clear();
    }

    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("a club that has never played still carries its tier's rung in the database")
    void theSeedIsCommittedToo() {
        MatchPlay play = transactions.execute(status -> buildAHomeWin("CLQ", "ZZ Seed"));

        ratings.recomputeDurably();

        Double idle = transactions.execute(status -> teams.findById(play.idleId())
                .map(Team::getEloRating)
                .orElse(null));
        assertNotNull(idle, "a club that has never played has no rating in the database, so a freshly "
                + "built world cannot be ranked until every club happens to play something");
        assertEquals(1300.0, idle, 0.0001, "tier 3 is not on the owner's ladder in the database");
    }

    private Double readRating(Long teamId) {
        return transactions.execute(status -> teams.findById(teamId).map(Team::getEloRating).orElse(null));
    }

    private Double readPrevious(Long teamId) {
        return transactions.execute(status -> teams.findById(teamId).map(Team::getEloPreviousRating).orElse(null));
    }

    private Double readDelta(Long teamId) {
        return transactions.execute(status -> teams.findById(teamId).map(Team::getEloDelta).orElse(null));
    }

    /** A tier-1 pair playing one league match, plus a tier-3 club that never plays. */
    private record MatchPlay(Long winnerId, Long loserId, Long idleId) {
    }

    private MatchPlay buildAHomeWin(String iso, String prefix) {
        Country country = new Country();
        country.setName(prefix);
        country.setIsoCode(iso);
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team winner = club(country, prefix + " Winner", division(country, prefix + " Div", 1));
        Team loser = club(country, prefix + " Loser", division(country, prefix + " Div", 1));
        Team idle = club(country, prefix + " Idle", division(country, prefix + " Idle Div", 3));

        Competition league = competitions.findByCountryIsoCodeAndType(country.getIsoCode(),
                CompetitionType.LEAGUE).get(0);
        Match match = new Match();
        match.setCompetition(league);
        match.setHomeTeam(winner);
        match.setAwayTeam(loser);
        match.setPlayed(true);
        match.setFinished(true);
        match.setHomeGoals(2);
        match.setAwayGoals(0);
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setMatchDate(LocalDateTime.of(2026, 3, 1, 20, 45));
        match.setEventJson("[]");
        match.setStatsJson("{}");
        matches.save(match);

        return new MatchPlay(winner.getId(), loser.getId(), idle.getId());
    }

    private Competition division(Country country, String name, int tier) {
        Competition division = new Competition();
        division.setName(name);
        division.setType(CompetitionType.LEAGUE);
        division.setScope(CompetitionScope.NATIONAL);
        division.setTeamType(CompetitionTeamType.CLUB);
        division.setTier(tier);
        division.setDivisionLevel(1);
        division.setTeamsPerCompetition(10);
        division.setCountry(country);
        return competitions.save(division);
    }

    private Team club(Country country, String name, Competition division) {
        Team club = new Team();
        club.setName(name + " FC");
        club.setCountry(country);
        club.setCompetition(division);
        club.setHumanControlled(false);
        club.setReputation(50.0);
        return teams.save(club);
    }
}
