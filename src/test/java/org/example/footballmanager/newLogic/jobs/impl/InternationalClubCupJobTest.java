package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.InternationalClubCupDraw;
import org.example.footballmanager.newLogic.util.InternationalClubCups;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-CUPS-4 — the fifteen international club cups had a draw and nobody called it.
 *
 * <p>{@code InternationalClubCupDraw} is 631 lines of group stage and bracket with twelve green tests, and
 * <b>zero callers in {@code src/main}</b> until this job existed. Fifteen competitions, a correct
 * qualification rule, and no club had ever entered a group or played a tie.
 *
 * <h2>Why this class is shaped the way it is</h2>
 *
 * <p><b>Not {@code @Transactional}.</b> The job's draws commit in their own transaction, which is how they
 * work in the running app, so the test has to see committed data and the job has to see the test's. Making
 * the test transactional would roll back the world the job is about to qualify from.
 *
 * <p>That costs three things, and each is handled rather than ignored:
 * <ul>
 *   <li>rows persist between tests, so {@code Country.isoCode} — unique, three characters — would collide.
 *       Codes come from a JVM-wide counter instead;</li>
 *   <li>a lazy {@code Team} read outside a session throws, so team names are read inside a
 *       transaction;</li>
 *   <li>fixtures drawn by an earlier test are still there, so <b>every test works in its own season</b>.
 *       Without that, one test's group stage is another test's knockout.</li>
 * </ul>
 *
 * <p>"Which season's tables decide entry" is not a separate test: every test here builds a finished table
 * in {@code season - 1} and runs the job in {@code season}, so a job that read the season in progress
 * would qualify nobody and fail all of them.
 */
class InternationalClubCupJobTest extends BaseTest {

    /**
     * Twelve, so the field makes <b>groups of six</b> and therefore five matchdays.
     *
     * <p>Eight is the smallest field that draws groups at all ({@code MIN_FIELD_FOR_GROUPS}), but eight
     * clubs become two groups of <b>four</b>, which is three matchdays — so a test of "five matchdays in
     * weeks 1-5" would have to assert three weeks and stop describing the format the owner wrote. Twelve
     * is the smallest field that produces the real shape.
     *
     * <p>The eight-club case is not dropped: {@code InternationalClubCupDrawTest} asserts that a field
     * which does not divide by six still gets groups of at most six, which is the defect that made this
     * number matter.
     */
    private static final int COUNTRIES = 12;
    private static final int CLUBS_PER_DIVISION = 3;

    /** Each test gets its own season, so no test can read another test's fixtures. */
    private static final AtomicInteger SEASONS = new AtomicInteger(20);
    private static final AtomicInteger ISO_CODES = new AtomicInteger((int) (System.nanoTime() % 4096));

    @Autowired private InternationalClubCupJob job;
    @Autowired private InternationalClubCups cups;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private SeasonCompetitionRepository seasonCompetitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private TransactionTemplate transactions;

    /** A season, and the season whose finished tables decide entry into it. */
    private record Worlds(int season, int qualifying, List<Team> champions) { }

    // ------------------------------------------------------------------ the job

    @Test
    @DisplayName("week 1 draws every qualified champion into the Champions Cup")
    void weekOneDrawsTheGroupStage() {
        Worlds world = aWorldWithEightChampions();

        job.run(context(world.season(), 1));

        Set<String> inTheCup = clubNamesIn(world.season());
        assertEquals(world.champions().size(), world.champions().stream()
                        .map(Team::getName).filter(inTheCup::contains).count(),
                "every division winner should be in the Champions Cup. Drawn: " + inTheCup.size() + " club(s).");
    }

    @Test
    @DisplayName("the group stage is drawn on day 1, the international slot")
    void theGroupStageIsOnDayOne() {
        Worlds world = aWorldWithEightChampions();

        job.run(context(world.season(), 1));

        List<MatchFixture> drawn = fixturesIn(world.season());
        assertFalse(drawn.isEmpty(), "the Champions Cup drew nothing, so this test proves nothing");
        for (MatchFixture fixture : drawn) {
            assertEquals(1, fixture.getDayNumber(),
                    "the cups play on day 1 at 20:45; day 5 is the domestic cup and nothing else");
        }
    }

    @Test
    @DisplayName("the five group matchdays sit in weeks 1-5, and week 6 is not one of them")
    void theGroupStageIsFiveWeeks() {
        Worlds world = aWorldWithEightChampions();

        job.run(context(world.season(), 1));

        Set<Integer> weeks = new TreeSet<>();
        for (MatchFixture fixture : fixturesIn(world.season())) {
            weeks.add(fixture.getWeekNumber());
        }
        assertEquals(Set.of(1, 2, 3, 4, 5), weeks,
                "5 meceva izmedju week 1 i week 5, and week 6 is the national-team week");
    }

    @Test
    @DisplayName("running the job twice in one week draws nothing twice")
    void theJobIsIdempotent() {
        Worlds world = aWorldWithEightChampions();

        job.run(context(world.season(), 1));
        int afterFirst = fixturesIn(world.season()).size();
        assertTrue(afterFirst > 0, "the first run drew nothing, so this test proves nothing");

        job.run(context(world.season(), 1));
        job.run(context(world.season(), 1));

        assertEquals(afterFirst, fixturesIn(world.season()).size(),
                "the done-flag is not the only guard — a second run in the same week must find the draw "
                        + "already there and leave it alone");
    }

    /** Weeks 6, 11 and 12 belong to national teams and the league play-off, so the job does nothing. */
    @Test
    @DisplayName("weeks 6, 11 and 12 draw nothing")
    void nationalTeamWeeksAreLeftAlone() {
        Worlds world = aWorldWithEightChampions();

        job.run(context(world.season(), 1));
        int afterWeek1 = fixturesIn(world.season()).size();

        for (int week : new int[]{6, 11, 12}) {
            job.run(context(world.season(), week));
            assertEquals(afterWeek1, fixturesIn(world.season()).size(),
                    "week " + week + " is not a cup week and must not add a fixture");
        }
    }

    @Test
    @DisplayName("the final and the third place share week 10")
    void theFinalAndThirdPlaceShareWeekTen() {
        // Nine weeks: five group matchdays, then 7, 8, 9 and 10. A tenth stage resolves to the ninth week,
        // which is what puts both last ties in week 10 rather than pushing one into week 11 — where the
        // league promotion play-off lives.
        int[] weeks = InternationalClubCupDraw.CUP_WEEKS;
        assertEquals(9, weeks.length, "5 group matchdays and 4 knockout weeks");
        assertEquals(7, weeks[InternationalClubCupDraw.GROUP_MATCHDAYS], "the last sixteen is week 7");
        assertEquals(10, weekForStage(InternationalClubCupDraw.ROUND_THIRD_PLACE));
        assertEquals(10, weekForStage(InternationalClubCupDraw.ROUND_FINAL),
                "the final is week 10, the same evening as the third-place play-off");
        assertEquals(10, weeks[weeks.length - 1]);
    }

    // ---------- the world ----------

    /**
     * Twelve countries, one tier-1 division each, and a <b>finished</b> season of tables behind them.
     *
     * <p>Small enough to build in a test, and large enough to make the format the owner specified: two
     * groups of six, five matchdays.
     */
    private Worlds aWorldWithEightChampions() {
        int season = SEASONS.incrementAndGet();
        int qualifying = season - 1;
        cups.ensureCompetitionsDurably();

        List<Team> champions = new ArrayList<>();
        for (int index = 1; index <= COUNTRIES; index++) {
            Country country = aCountry("Cupjobia " + index, index);
            Competition division = aTierOneDivision(country, index);
            List<Team> clubs = new ArrayList<>();
            for (int seat = 1; seat <= CLUBS_PER_DIVISION; seat++) {
                clubs.add(aClub(country, division, index, seat));
            }
            // A finished table, points descending, so the champion is decided by the table rather than by
            // which row happened to come first.
            writeTable(aFinishedSeason(division, qualifying), clubs);
            champions.add(clubs.get(0));
        }
        return new Worlds(season, qualifying, champions);
    }

    private void writeTable(SeasonCompetition sc, List<Team> clubs) {
        for (int index = 0; index < clubs.size(); index++) {
            CompetitionEntry entry = new CompetitionEntry();
            entry.setSeasonCompetition(sc);
            entry.setTeam(clubs.get(index));
            entry.setPosition(index + 1);
            entry.setPoints((clubs.size() - index) * 3);
            entry.setWins(clubs.size() - index);
            entry.setDraws(0);
            entry.setLosses(0);
            entry.setGoalsScored((clubs.size() - index) * 2);
            entry.setGoalsConceded(index);
            entries.save(entry);
        }
    }

    // ---------- reads ----------

    /**
     * The Champions Cup row the job itself picks.
     *
     * <p>The same call the job makes, in the same order — {@code findByTypeAndTier} then filter by scope
     * and name — so the two cannot disagree about which row is the Champions Cup. Many tests in this
     * repository create a competition named {@code "Champions Cup"}, so resolving it any other way would
     * read a different cup from the one the job drew.
     */
    private Competition theChampionsCup(int tier) {
        Competition found = competitions.findByTypeAndTier(CompetitionType.CUP, tier).stream()
                .filter(c -> c.getScope() == CompetitionScope.INTERNATIONAL)
                .filter(c -> InternationalClubCups.CHAMPIONS.equals(c.getName())
                        || ("Tier " + tier + " " + InternationalClubCups.CHAMPIONS).equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(found, "no Champions Cup row for tier " + tier + "; the job cannot draw without one");
        return found;
    }

    private List<MatchFixture> fixturesIn(int season) {
        return fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                theChampionsCup(1).getId(), season);
    }

    /** Team names, read inside a transaction because a lazy {@code Team} outside a session throws. */
    private Set<String> clubNamesIn(int season) {
        return transactions.execute(status -> {
            Set<String> names = new TreeSet<>();
            for (MatchFixture fixture : fixturesIn(season)) {
                if (fixture.getHomeTeam() != null) {
                    names.add(fixture.getHomeTeam().getName());
                }
                if (fixture.getAwayTeam() != null) {
                    names.add(fixture.getAwayTeam().getName());
                }
            }
            return names;
        });
    }

    // ---------- plumbing ----------

    /** Mirrors {@code InternationalClubCupDraw.weekFor}, which is private. */
    private int weekForStage(int stage) {
        int[] weeks = InternationalClubCupDraw.CUP_WEEKS;
        return weeks[Math.min(stage, weeks.length) - 1];
    }

    private JobContext context(int season, int week) {
        return new JobContext(season, week, InternationalClubCupDraw.CUP_DAY, 8);
    }

    private SeasonCompetition aFinishedSeason(Competition competition, int season) {
        SeasonCompetition sc = seasonCompetitions
                .findByCompetitionAndSeasonYear(competition, season)
                .orElseGet(SeasonCompetition::new);
        sc.setCompetition(competition);
        sc.setSeasonYear(season);
        sc.setFinished(true);
        return seasonCompetitions.save(sc);
    }

    private Competition aTierOneDivision(Country country, int index) {
        Competition division = new Competition();
        division.setName("Cupjobia " + index + " Premier");
        division.setType(CompetitionType.LEAGUE);
        division.setScope(CompetitionScope.NATIONAL);
        division.setTeamType(CompetitionTeamType.CLUB);
        division.setCountry(country);
        division.setTier(1);
        division.setDivisionLevel(1);
        return competitions.save(division);
    }

    /**
     * A country whose three-character ISO code is unique for the life of this JVM.
     *
     * <p>{@code Country.isoCode} is unique and three characters, and the rows survive between tests because
     * the class is not transactional. A fixed {@code "J01"} therefore collides with the previous test and
     * the whole class dies on a constraint violation before it asserts anything — which is what the first
     * version of this did.
     */
    private Country aCountry(String name, int index) {
        int n = ISO_CODES.incrementAndGet();
        Country country = new Country();
        country.setName(name + " " + System.nanoTime());
        country.setIsoCode(String.format("%c%c%c",
                (char) ('A' + n % 26), (char) ('A' + (n / 26) % 26), (char) ('A' + (n / 676) % 26)));
        country.setReputation(1500);
        return countries.save(country);
    }

    private Team aClub(Country country, Competition division, int countryIndex, int seat) {
        Team club = new Team();
        club.setName(String.format("CJF%02d%02d %d", countryIndex, seat, System.nanoTime() % 100000));
        club.setCountry(country);
        club.setCompetition(division);
        club.setReputation(60.0);
        club.setBudget(5_000_000.0);
        club.setHumanControlled(false);
        return teams.save(club);
    }
}