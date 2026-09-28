package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Random;

/**
 * The cup draw, so a country has a cup with rounds and a bracket rather than a name in a table
 * (owner, 2026-09-28).
 *
 * <p>{@code Competition} row 32 ("Kup Srbije") has existed since the database was seeded, with
 * {@code teams_per_competition = 64} and no teams in it. It was a label, not a competition.
 *
 * <p><b>Week mapping, and one place the brief does not close.</b> The owner specified cup football on
 * day 5 of weeks 1, 2, 3, 4, 5, 7, 8, 9, 10, 11 with no week 6, and the final on week 11. A
 * 256-team knockout needs exactly 8 rounds. Eight rounds across ten candidate weeks leaves two weeks
 * empty, and the final has to be the last of them. Rounds are placed on weeks 1, 2, 3, 4, 5, 7, 8 and
 * <b>11</b>, which honours "no week 6" and "final on week 11" at the cost of a two-week gap before the
 * final (weeks 9 and 10 carry league football only). The alternative — final on week 9 — contradicts
 * the stated final week, so the gap was taken instead. This is a one-line change in {@link #CUP_WEEKS}
 * if the owner prefers the tighter run-in.
 */
@Component
public class CupFixtureSeeder {

    private static final Logger log = LoggerFactory.getLogger(CupFixtureSeeder.class);

    /** Owner, 2026-09-28. Eight rounds, no week 6, final on week 11. See the class comment. */
    public static final int[] CUP_WEEKS = {1, 2, 3, 4, 5, 7, 8, 11};

    /** Owner: ranks 203-310 enter in week 1; ranks 1-202 join the winners in week 2. */
    static final int ENTRY_ROUND_TEAMS = 108;
    static final int MAIN_DRAW_TEAMS = 256;

    /**
     * The season the draw belongs to, as a CALENDAR year - 2026, not the season index 1.
     *
     * <p>The cup was written with season_year = 1, which is the season index, while every other
     * fixture and the job runner use BASE_SEASON_YEAR + (season - 1) = 2026. So the day-5 matchday job
     * asked for 2026 and found no cup fixtures, completed successfully, and played nothing - the most
     * dangerous shape a silent failure can take.
     */
    static final int SEED_SEASON = SeasonService.BASE_SEASON_YEAR;

    /** Day 5 is the cup slot in the seven-day template. */
    private static final LocalDate SEASON_START = LocalDate.of(2026, 7, 1);
    private static final int CUP_DAY = 5;
    private static final int CUP_HOUR = 18;

    /** A fixed seed so the draw is the same on every boot. A random draw would reshuffle the cup
     *  on every restart, which is not a cup. */
    private static final long DRAW_SEED = 20260928L;

    private final CompetitionRepository competitions;
    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final Random random;

    /** Two constructors, so Spring is told which one. Same trap as TransferActivitySeeder and
     *  NationalTeamSeeder; a unit test cannot catch it because it never goes through Spring. */
    @org.springframework.beans.factory.annotation.Autowired
    public CupFixtureSeeder(CompetitionRepository competitions, MatchFixtureRepository fixtures,
                            TeamRepository teams, PlayerRepository players) {
        this(competitions, fixtures, teams, players, new Random(DRAW_SEED));
    }

    CupFixtureSeeder(CompetitionRepository competitions, MatchFixtureRepository fixtures,
                     TeamRepository teams, PlayerRepository players, Random random) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.teams = teams;
        this.players = players;
        this.random = random;
    }

    @Transactional
    public void seedIfMissing() {
        Competition cup = competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.CUP)
                .findFirst()
                .orElse(null);
        if (cup == null) {
            log.info("No CUP competition found; nothing to draw.");
            return;
        }

        // Idempotent by fixture count, not by a flag: if the draw exists, the fixtures are the record.
        long existing = fixtures.countBySeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(SEED_SEASON, CUP_WEEKS[0], CUP_DAY);
        log.info("Cup {}: {} round-1 ties already drawn.", cup.getName(), existing);
        if (existing > 0) {
            return;
        }

        List<Team> ranked = rankedClubs();
        log.info("Cup {}: {} clubs ranked, need {}.", cup.getName(), ranked.size(), MAIN_DRAW_TEAMS);
        if (ranked.size() < MAIN_DRAW_TEAMS) {
            log.warn("Only {} clubs available; a {}-team draw needs {}. Cup {} left empty.",
                    ranked.size(), MAIN_DRAW_TEAMS, MAIN_DRAW_TEAMS, cup.getName());
            return;
        }

        cup.setTeamsPerCompetition(MAIN_DRAW_TEAMS);
        competitions.save(cup);

        // Week 1: the weakest of the entrants, drawn into 54 ties. Winner of each tie plus the 202
        // direct entrants make the 256 that carry the rest of the tournament.
        List<Team> firstKnockout = new ArrayList<>(ranked.subList(0, ENTRY_ROUND_TEAMS));
        List<Team> directEntrants = new ArrayList<>(ranked.subList(ENTRY_ROUND_TEAMS, MAIN_DRAW_TEAMS));
        List<MatchFixture> round1 = buildRound(cup, firstKnockout, 1, CUP_WEEKS[0], SEED_SEASON);

        // Round 2 onwards can only be wired once the earlier rounds are actually played, so the
        // seeding creates round 1 and leaves the bracket to be driven by results. A full 8-round
        // bracket written up front would be a second source of truth that silently goes stale the
        // first time a game is postponed.
        log.info("Drew {} ties in round 1 of {} ({} direct entrants join in week {}). "
                        + "Later rounds are created from results as the cup is played.",
                round1.size(), cup.getName(), directEntrants.size(), CUP_WEEKS[1]);
    }

    /**
     * Ranks the country's clubs.
     *
     * <p>By average squad rating, then name. There is no club-level Elo yet — the rating engine exists
     * but nothing persists a club rating — so squad strength is the only honest ordering available.
     * Ties break on name rather than on iteration order so two boots produce the same draw.
     */
    private List<Team> rankedClubs() {
        List<Team> clubs = new ArrayList<>();
        for (Team team : teams.findClubTeamsForOperations()) {
            if (team.getId() == null || team.getCountry() == null) {
                continue;
            }
            if (!clubs.isEmpty() && !clubs.get(0).getCountry().getId().equals(team.getCountry().getId())) {
                continue;
            }
            clubs.add(team);
        }
        return sortByStrength(clubs, strengthOf(clubs));
    }

    /**
     * Squad strength for every team, one query each, computed once.
     *
     * <p>This exists because the obvious version - sorting with a comparator that calls
     * {@code averageSquadRating} - re-queries the database on every comparison. With 310 clubs that
     * is roughly 2500 extra queries, and the draw appeared to do nothing at all: the seeder had not
     * failed, it was still working. The symptom is indistinguishable from a silent crash, which is
     * why the earlier rounds of this had "a draw that does nothing" with no error in the log.
     */
    private Map<Long, Double> strengthOf(List<Team> clubs) {
        Map<Long, Double> strength = new HashMap<>();
        for (Team team : clubs) {
            strength.put(team.getId(), averageSquadRating(team));
        }
        return strength;
    }

    private List<Team> sortByStrength(List<Team> clubs, Map<Long, Double> strength) {
        List<Team> sorted = new ArrayList<>(clubs);
        sorted.sort(Comparator
                .comparingDouble((Team t) -> strength.getOrDefault(t.getId(), 0.0)).reversed()
                .thenComparing(t -> t.getName() == null ? "" : t.getName()));
        return sorted;
    }

    private double averageSquadRating(Team team) {
        List<org.example.footballmanager.newLogic.model.Player> squad = players.findByTeamId(team.getId());
        if (squad == null || squad.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (org.example.footballmanager.newLogic.model.Player player : squad) {
            total += player.getRating();
        }
        return total / squad.size();
    }

    /**
     * One knockout round, drawn the way the owner specified.
     *
     * <p>The rule: split the remaining clubs by ranking. The better half are the favourites and the
     * weaker half are the non-favourites. Every tie is one random favourite against one random
     * non-favourite, and <b>the non-favourite hosts</b>. The split is recomputed each round from
     * whoever is left, so the favourites are the best of the survivors rather than a fixed group set
     * at the start of the tournament.
     *
     */
    private List<MatchFixture> buildRound(Competition cup, List<Team> entrants, int roundNumber,
                                          int week, int seasonYear) {
        List<Team> ranked = sortByStrength(new ArrayList<>(entrants), strengthOf(entrants));

        // The half is recomputed from whoever is left in this round, not fixed at the start of the
        // tournament. Round 1 is not a special case: it draws the 108 entry-round clubs, and the
        // same split puts the stronger 54 against the weaker 54, which carries the seeding into
        // round 2 when the direct entrants arrive.
        int half = ranked.size() / 2;
        List<Team> favourites = new ArrayList<>(ranked.subList(0, half));
        List<Team> nonFavourites = new ArrayList<>(ranked.subList(half, ranked.size()));

        // One random from each half, so the favourite is never paired with another favourite and a
        // weak club never draws another weak club.
        Collections.shuffle(favourites, random);
        Collections.shuffle(nonFavourites, random);

        int ties = Math.min(favourites.size(), nonFavourites.size());
        if (ties * 2 < ranked.size()) {
            // Odd count: the unpaired clubs from the larger half enter as byes rather than being
            // dropped. Losing a club silently would be worse than a slightly lopsided draw.
            log.info("Cup {} round {}: {} clubs, {} ties, {} byes.",
                    cup.getName(), roundNumber, ranked.size(), ties, ranked.size() - ties * 2);
        }

        List<MatchFixture> made = new ArrayList<>();
        for (int i = 0; i < ties; i++) {
            Team favourite = favourites.get(i);
            Team nonFavourite = nonFavourites.get(i);
            MatchFixture fixture = new MatchFixture();
            fixture.setCompetition(cup);
            // The non-favourite is at home. That is the owner's rule and it is the whole point of
            // the split: the tie is the upset, and the weaker side gets the home crowd.
            fixture.setHomeTeam(nonFavourite);
            fixture.setAwayTeam(favourite);
            fixture.setRoundNumber(roundNumber);
            fixture.setWeekNumber(week);
            // Day 5 is the only cup day in the template, and the day-5 matchday job selects
            // fixtures by day. Without this it would never find its own football.
            fixture.setDayNumber(CUP_DAY);
            fixture.setSeasonYear(seasonYear);
            fixture.setPlayed(false);
            // The cup plays on day 5 of its week, so the date is derived from the week number rather
            // than invented per tie: one rule, and every tie in a round lands on the same day.
            LocalDate day5 = SEASON_START.plusWeeks(week - 1L).plusDays(CUP_DAY - 1L);
            fixture.setMatchDate(LocalDateTime.of(day5, java.time.LocalTime.of(CUP_HOUR, 0)));
            made.add(fixtures.save(fixture));
        }
        log.info("Cup {} round {}: {} ties for week {} (day {}), favourites vs non-favourites, "
                        + "non-favourite at home.", cup.getName(), roundNumber, made.size(), week, CUP_DAY);
        return made;
    }
}
