package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A country's rating, from the internationals it has played (owner, 2026-09-30).
 *
 * <p><b>These build their own match history instead of reading the seeded one</b>, and the reason is
 * worth recording. Postgres holds 24 played internationals and that is the real bug report — 48
 * countries all reading exactly 1500. H2's seeded world draws its international fixtures but does not
 * play them, so a test written against the seeded world would find an empty table and pass by
 * asserting nothing. {@code recompute()} joins the caller's transaction, so a {@code @Transactional}
 * test can build a history the replay will actually see — the opposite of the {@code REQUIRES_NEW}
 * backfill in {@code BotLeagueStandardBackfillTest}, where the same trick silently proves nothing.
 *
 * <p>The seeded world is still asserted on, in {@link #theWorldIsLevelUntilSomethingIsPlayed()}, so
 * the flat column cannot come back unnoticed.
 */
class NationalRatingServiceTest extends BaseTest {

    @Autowired private NationalRatingService ratings;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private MatchRepository matches;
    @Autowired private CompetitionRepository competitions;

    // --- the two properties the owner asked for ---

    @Test
    @Transactional
    @DisplayName("the rating column stops being flat")
    void ratingsActuallyMove() {
        seedInternationalHistory();
        countries.flush();
        assertEquals(1, countDistinctRatings(), "the world is expected to start level, so there is something to move");

        NationalRatingService.Result result = ratings.recompute();
        countries.flush();

        assertTrue(countDistinctRatings() > 1,
                "after a replay every country still reads the same rating — the World page column is "
                        + "still flat, which is the bug this task exists to fix");
        assertEquals(4, result.matchesReplayed(), "not every seeded international was replayed");
        assertTrue(result.highest() > result.lowest(),
                "the replayed range is inverted: highest " + result.highest() + " is not above lowest "
                        + result.lowest());
    }

    @Test
    @Transactional
    @DisplayName("the results say so in the column: a winner rises, a loser falls, a draw does not")
    void theResultsSaySoInTheColumn() {
        seedInternationalHistory();
        countries.flush();
        ratings.recompute();
        countries.flush();

        // Every country in this history played exactly one match, so each number is decided by that
        // result and nothing else. Two drew first and then won; the other four are a clean set of
        // winners and losers.
        for (String winner : List.of("ZES", "ZEH", "ZEC")) {
            assertTrue(countryByIso(winner).getReputation() > 1500,
                    winner + " won its only match but is rated " + countryByIso(winner).getReputation());
        }
        for (String loser : List.of("ZEP", "ZEF", "ZEE")) {
            assertTrue(countryByIso(loser).getReputation() < 1500,
                    loser + " lost its only match but is rated " + countryByIso(loser).getReputation());
        }
    }

    @Test
    @Transactional
    @DisplayName("one drawn match between two level countries moves neither")
    void aLoneDrawMovesNobody() {
        // A draw against an equally-rated side is worth nothing to either country, and this is the one
        // case where a correct rating system produces no change at all. It is worth pinning because a
        // system that gave everyone rating for playing would drift the whole world upward on a calendar
        // of friendlies.
        Country denmark = countries.save(countryWithSides("ZZ Denmark", "ZED"));
        Country norway = countries.save(countryWithSides("ZZ Norway", "ZEN"));
        Competition competition = new Competition();
        competition.setName("ZZ Friendly Internationals");
        competition.setType(CompetitionType.INTERNATIONAL);
        competition = competitions.save(competition);
        playedInternational(competition, denmark.getSeniorNationalTeam(), norway.getSeniorNationalTeam(), 2, 2, 1);
        countries.flush();

        NationalRatingService.Result result = ratings.recompute();
        countries.flush();

        assertEquals(1, result.matchesReplayed());
        assertEquals(1500, denmark.getReputation(), "a level draw moved Denmark to " + denmark.getReputation());
        assertEquals(1500, norway.getReputation(), "a level draw moved Norway to " + norway.getReputation());
    }

    // --- the properties the replay depends on ---

    @Test
    @Transactional
    @DisplayName("the replay is idempotent — a second run changes nothing")
    void replayIsIdempotent() {
        seedInternationalHistory();
        countries.flush();

        NationalRatingService.Result first = ratings.recompute();
        String afterFirst = ratingColumn();
        NationalRatingService.Result second = ratings.recompute();

        assertEquals(afterFirst, ratingColumn(),
                "a second replay moved the ratings. This runs on every boot and after every "
                        + "international, so a drifting replay would walk the world away from 1500");
        assertEquals(first.matchesReplayed(), second.matchesReplayed(),
                "the two runs replayed a different number of matches, so they cannot be the same computation");
        assertEquals(first.highest(), second.highest(), 0.000001, "the replayed range moved on an identical rerun");
    }

    @Test
    @Transactional
    @DisplayName("the replay always starts from 1500, never from the stored number")
    void theReplayIsNotARatchet() {
        seedInternationalHistory();
        countries.flush();
        ratings.recompute();
        countries.flush();

        // A replay that began from the stored column could only ever move ratings further apart, and
        // running it on every boot would compound. Two runs have to be enough to prove it is a pure
        // function of the results.
        String afterOne = ratingColumn();
        ratings.recompute();
        ratings.recompute();
        countries.flush();

        assertEquals(afterOne, ratingColumn(), "the column moved on repeats — this is a ratchet, not a replay");
    }

    @Test
    @DisplayName("an upset is worth more than a routine win")
    void upsetsAreWorthMore() {
        double upset = RatingEngine.delta(1400, 1600, 1.0, RatingEngine.NATIONAL_BASE_K);
        double routine = RatingEngine.delta(1600, 1400, 1.0, RatingEngine.NATIONAL_BASE_K);

        assertTrue(upset > routine,
                "an upset (" + upset + ") must beat a routine win (" + routine + ") — this is the whole "
                        + "reason the system is Elo and not a flat points table");
    }

    @Test
    @DisplayName("a draw moves a favourite down and an underdog up, and is zero-sum")
    void aDrawConverges() {
        double favourite = RatingEngine.delta(1600, 1400, 0.5, RatingEngine.NATIONAL_BASE_K);
        double underdog = RatingEngine.delta(1400, 1600, 0.5, RatingEngine.NATIONAL_BASE_K);

        assertTrue(favourite < 0, "drawing with a much stronger side should cost rating, got " + favourite);
        assertTrue(underdog > 0, "drawing against a much stronger side should earn rating, got " + underdog);
        // Zero-sum to within float error: the points one side gains are the points the other loses, so
        // a replay cannot manufacture rating out of nothing.
        assertEquals(0.0, favourite + underdog, 0.000001, "a match created or destroyed rating");
    }

    @Test
    @Transactional
    @DisplayName("a senior result never moves the under-21s, and the other way round")
    void seniorAndYouthAreSeparateRatings() {
        seedInternationalHistory();
        countries.flush();
        ratings.recompute();
        countries.flush();

        for (Country country : countries.findAll()) {
            if (country.getU21NationalTeam() != null && country.getYouthRating() != null) {
                assertEquals(1500, country.getYouthRating(),
                        country.getName() + " moved its under-21 rating without playing an under-21 "
                                + "match — a twenty-year-old's result is not evidence about the senior side");
            }
        }
    }

    @Test
    @Transactional
    @DisplayName("a club in an international competition is skipped, not rated")
    void aClubSideIsNotRated() {
        // A national competition with a club in it is a data problem. Rating it would let a club's
        // record rewrite a country's standing, and every other country in the world is scored against
        // the contaminated one.
        Country country = countries.save(country("ZZ Elo", "ZZE"));
        Team club = teams.save(club("ZZ Wanderers", country));

        Competition competition = new Competition();
        competition.setName("ZZ Internationals");
        competition.setType(CompetitionType.INTERNATIONAL);
        competition.setCountry(country);
        competition = competitions.save(competition);

        Match bad = new Match();
        bad.setCompetition(competition);
        bad.setHomeTeam(club);
        bad.setAwayTeam(country.getSeniorNationalTeam());
        bad.setPlayed(true);
        bad.setHomeGoals(5);
        bad.setAwayGoals(0);
        bad.setSeasonYear(1);
        bad.setMatchDate(LocalDateTime.now().minusDays(1));
        bad.setEventJson("[]");
        matches.save(bad);

        ratings.recompute();
        countries.flush();

        assertEquals(1500, countries.findByIsoCode("ZZE").orElseThrow().getReputation(),
                "a club beating a national side moved the country's rating — every other country in the "
                        + "world is scored against this one");
    }

    @Test
    @DisplayName("the seeded world is still level until something is played")
    void theWorldIsLevelUntilSomethingIsPlayed() {
        // The regression guard for the actual bug report. H2 draws its internationals but does not play
        // them, so this asserts the honest starting state rather than a moved column: the point is
        // that this number can never be "48 countries all reading one value with internationals played".
        long played = matches
                .findPlayedByCompetitionTypeOrderByMatchDateAscIdAsc(CompetitionType.INTERNATIONAL)
                .size();
        long distinct = countDistinctRatings();
        if (played == 0) {
            assertEquals(1L, distinct,
                    "no internationals are played, so the column is level by definition — if it is not, "
                            + "something is writing ratings without a result to justify them");
        } else {
            assertTrue(distinct > 1, played + " internationals are played but every country reads one rating");
        }
    }

    // --- helpers ---

    /**
     * Four internationals over six fresh countries, so every assertion can name a result.
     *
     * <p>Each country appears exactly once, which is what makes the column assertions checkable: a
     * country that played one match and lost it can only be below 1500. My first version had Poland in
     * two of them and then asserted it had fallen, which is not a thing one match and a win can do.
     *
     * <p><b>The national sides have to be created here.</b> The service identifies a side by membership
     * — {@code Country.seniorNationalTeam} — not by its name, so a country saved without one has no
     * side to play with and every match built from it has a null team. That produced a replay of
     * nothing, and the first version of this file asserted the column had not moved and called it a
     * bug in the service rather than in the fixture.
     */
    private void seedInternationalHistory() {
        Country canada = countries.save(countryWithSides("ZZ Canada", "ZEC"));
        Country hungary = countries.save(countryWithSides("ZZ Hungary", "ZEH"));
        Country slovakia = countries.save(countryWithSides("ZZ Slovakia", "ZES"));
        Country poland = countries.save(countryWithSides("ZZ Poland", "ZEP"));
        Country france = countries.save(countryWithSides("ZZ France", "ZEF"));
        Country spain = countries.save(countryWithSides("ZZ Spain", "ZEE"));

        Competition competition = new Competition();
        competition.setName("ZZ Internationals");
        competition.setType(CompetitionType.INTERNATIONAL);
        competition.setTier(1);
        competition = competitions.save(competition);

        int day = 1;
        playedInternational(competition, canada.getSeniorNationalTeam(), hungary.getSeniorNationalTeam(), 0, 0, day++);
        playedInternational(competition, slovakia.getSeniorNationalTeam(), poland.getSeniorNationalTeam(), 3, 0, day++);
        playedInternational(competition, hungary.getSeniorNationalTeam(), france.getSeniorNationalTeam(), 2, 0, day++);
        playedInternational(competition, canada.getSeniorNationalTeam(), spain.getSeniorNationalTeam(), 1, 0, day++);
    }

    private void playedInternational(Competition competition, Team home, Team away,
                                     int homeGoals, int awayGoals, int day) {
        Match match = new Match();
        match.setCompetition(competition);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setPlayed(true);
        match.setFinished(true);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setMatchDate(LocalDateTime.of(2026, 1, day, 20, 45));
        match.setEventJson("[]");
        match.setStatsJson("{}");
        matches.save(match);
    }

    private long countDistinctRatings() {
        return countries.findAll().stream()
                .map(Country::getReputation)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }

    private String ratingColumn() {
        return countries.findAll().stream()
                .sorted(java.util.Comparator.comparing(Country::getName))
                .map(country -> country.getName() + "=" + country.getReputation())
                .toList()
                .toString();
    }

    private Country countryByIso(String iso) {
        return countries.findByIsoCode(iso).orElseThrow();
    }

    /** A country with both national sides, which is what makes it playable. */
    private Country countryWithSides(String name, String iso) {
        Country country = country(name, iso);
        country.setSeniorNationalTeam(teams.save(nationalSide(name, "Senior")));
        country.setU21NationalTeam(teams.save(nationalSide(name, "U-21")));
        return country;
    }

    private Team nationalSide(String countryName, String level) {
        Team side = new Team();
        side.setName(countryName + " " + level + " National Team");
        return side;
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

    private Team club(String name, Country country) {
        Team team = new Team();
        team.setName(name);
        team.setCountry(country);
        team.setHumanControlled(false);
        return team;
    }
}
