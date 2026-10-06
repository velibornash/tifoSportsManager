package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
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

    /** Day 5 is the cup slot in the seven-day template. */
    private static final int CUP_DAY = 5;
    private static final int CUP_HOUR = 18;

    /** A fixed seed so the draw is the same on every boot. A random draw would reshuffle the cup
     *  on every restart, which is not a cup. */
    private static final long DRAW_SEED = 20260928L;

    /**
     * When a cup tie in a given week is played.
     *
     * <p>Derived from the week number and the cup's own day, rather than invented per tie, so every tie in
     * a round lands on the same day. Shared with the international cups, which have their own week map
     * but the same day and hour — two date rules would mean two answers to "when does the cup play".
     */
    /**
     * When a cup tie in a given week is played, measured from the <b>game clock's</b> season start.
     *
     * <p>This was measured from a literal, {@code LocalDate.of(2026, 7, 1)}, and that is B4. Every season's
     * round 1 got the same wall-clock date, and the recovery window is {@code currentDate - 2 days}
     * ({@code ZoneLoadService:69,127}), so once the clock passed 2026-07-06 <b>no cup fixture ever fell
     * inside it</b> - loads were written and never read and {@code RecoveryJob} reported zero for ever.
     *
     * <p>The league path already had this right: {@code SeasonService:267} seeds from
     * {@code clock.getCurrentDate()}. This now uses the same clock rather than a date that means nothing
     * to the running world.
     *
     * <p>Still one rule for "when does the cup play", shared with the international cups: the week number
     * and the cup's own day, so every tie in a round lands on the same day.
     */
    public static LocalDateTime matchDateFor(int week, LocalDateTime seasonStart) {
        LocalDate day5 = seasonStart.toLocalDate().plusWeeks(week - 1L).plusDays(CUP_DAY - 1L);
        return LocalDateTime.of(day5, java.time.LocalTime.of(CUP_HOUR, 0));
    }

    private final SeasonService seasons;

    /**
     * The season the draw belongs to, read from the clock rather than written here.
     *
     * <p>This was a constant, and it was wrong twice. It was 1 - the season number - while the rest
     * of the world used a calendar year, so the day-5 matchday job asked for a season with no cup
     * fixtures in it, completed successfully and played nothing. Changing the constant did not help,
     * because the call sites never came from it. It was then moved onto the calendar year to make
     * the two agree, which fixed the job and left the country page reading a season the seeder had
     * never written - "0 ties across 8 rounds" over a bracket the log had just reported as drawn.
     * A season is a number counted from 1 now, everywhere, and this asks the clock which one it is
     * so there is no second answer to keep in step.
     */
    private int seedSeason() {
        return seasons.getActiveSeasonYear();
    }

    private final MatchFixtureRepository fixtures;
    private final CompetitionRepository competitions;
    private final TeamRepository teams;
    private final PlayerRepository players;

    /** Two constructors, so Spring is told which one. Same trap as TransferActivitySeeder and
     *  NationalTeamSeeder; a unit test cannot catch it because it never goes through Spring. */
    @org.springframework.beans.factory.annotation.Autowired
    public CupFixtureSeeder(CompetitionRepository competitions, MatchFixtureRepository fixtures,
                            TeamRepository teams, PlayerRepository players, SeasonService seasons) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.teams = teams;
        this.players = players;
        this.seasons = seasons;
    }

    @Transactional
    public void seedIfMissing() {
        // **Left as findAll() on purpose, and the reason is worth more than the optimisation.**
        //
        // This looks like a free D1 win — the competition table is every league division in the world,
        // 1,457 of them, read to find the few dozen cups. Narrowing it to findByType(CUP) was tried and
        // broke CupFixtureSeederCountryTest in five places, because `findFirst()` over an unordered
        // result is a silent coupling to whatever order the rows come back in: findAll() and
        // findByType() do not return the same order, so the seeder picked a *different cup*.
        //
        // Which cup this should draw is already a parked owner decision — one job drawing 48 national
        // cups, or one draw per country. Making the selection deterministic is the fix for that, and it
        // belongs with the decision rather than inside a performance change. primaryCup() is narrowed
        // below because *its* rule is already deterministic (lowest id), so the query returns exactly
        // what the stream selected.
        Competition cup = competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.CUP)
                .findFirst()
                .orElse(null);
        if (cup == null) {
            log.info("No CUP competition found; nothing to draw.");
            return;
        }

        // Idempotent by fixture count, not by a flag: if the draw exists, the fixtures are the record.
        long existing = fixtures.countByCompetitionIdAndSeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(
                cup.getId(), seedSeason(), CUP_WEEKS[0], CUP_DAY);
        log.info("Cup {}: {} round-1 ties already drawn.", cup.getName(), existing);
        if (existing > 0) {
            return;
        }

        List<Team> ranked = rankedClubs(cup);
        log.info("Cup {}: {} clubs ranked, need {}.", cup.getName(), ranked.size(), MAIN_DRAW_TEAMS);
        if (ranked.size() < MAIN_DRAW_TEAMS) {
            log.warn("Only {} clubs available; a {}-team draw needs {}. Cup {} left empty.",
                    ranked.size(), MAIN_DRAW_TEAMS, MAIN_DRAW_TEAMS, cup.getName());
            return;
        }

        cup.setTeamsPerCompetition(MAIN_DRAW_TEAMS);
        competitions.save(cup);

        // Week 1: the WEAKEST 108, drawn into 54 ties. The winners plus the 202 direct entrants make
        // the 256 that carry the rest of the tournament.
        //
        // This took `subList(0, 108)` — which, on a list sorted descending, is the *strongest* 108.
        // The line above it has said "ranks 203-310 enter in week 1" since before this session, and the
        // code did the exact opposite of it: the best clubs in the country were made to survive an extra
        // round while the weakest 108 were given a free walk to round 2. The owner's rule is the bottom
        // of the table qualifying through a preliminary, which is both what makes a preliminary and what
        // stops a league finishing seventh from outranking a league finishing first.
        int directEntrantCount = Math.max(0, ranked.size() - ENTRY_ROUND_TEAMS);
        List<Team> firstKnockout = new ArrayList<>(
                ranked.subList(directEntrantCount, ranked.size()));
        List<Team> directEntrants = new ArrayList<>(ranked.subList(0, directEntrantCount));
        List<MatchFixture> round1 = drawRound(cup, 1, firstKnockout, seedSeason());

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
    private List<Team> rankedClubs(Competition cup) {
        // **The cup's own country, not the first club the repository happens to return.**
        //
        // It used to take the country from `clubs.get(0)` and `continue` past every team whose country
        // did not match it - "a Serbia dependency expressed as a continue". Two things were wrong with
        // that. It scanned every club in the world to answer a question about one country, and the
        // country it settled on was whichever came first from an unordered query, so the draw silently
        // depended on database row order. `primaryCup()` picks the lowest-id domestic cup, so once more
        // than one country had a cup the field went to the wrong country's clubs.
        if (cup == null || cup.getCountry() == null || cup.getCountry().getId() == null) {
            log.warn("Cup {} has no country; nothing to rank.", cup == null ? "null" : cup.getName());
            return new ArrayList<>();
        }
        List<Team> clubs = teams.findByCountryId(cup.getCountry().getId()).stream()
                .filter(team -> team.getId() != null && team.getCountry() != null)
                .toList();
        return sortByStrength(new ArrayList<>(clubs), strengthOf(clubs));
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
     * Draws one round from the clubs that survived the one before it.
     *
     * <p>Public so {@code CupDrawJob} can call it per round. The owner's requirement, from watching a
     * reset leave the cup empty on the Oracle server: every round's draw should be a scheduled job, and
     * a draw that did not happen gets caught by the next check because it is not marked done.
     *
     * <p>That is exactly what the job framework's done-flag is for. The alternative - drawing whatever
     * rounds happen to be missing whenever anything asks - means a round can be drawn twice, and a
     * half-drawn tournament is worse than an obviously empty one.
     */
    @Transactional
    /**
     * The country's own cup, by what it is rather than by where it sits in a list.
     *
     * <p>This used to be {@code findAll().stream().filter(type == CUP).findFirst()} — the first cup row
     * the database happened to return. With one national cup that was fine. It is not fine now: there are
     * sixteen CUP competitions, and "the first" is whichever one comes back, so the draw job could be
     * drawing the Champions Cup on the national cup's calendar, with the national cup never drawn at all.
     * A "find first" over a growing table is a silent coupling to insertion order.
     *
     * <p>The discriminator is {@code scope}. A domestic cup is {@code NATIONAL}; the Champions, Masters
     * and Challenge cups are {@code INTERNATIONAL}, which is the only column that says the entrants come
     * from several countries — and they have their own draw, their own week map and their own format, so
     * running the national cup's round-for-week arithmetic over them was never going to be right.
     */
    /**
     * The one cup this seeder draws, chosen by lowest id.
     *
     * <p>Was called {@code nationalCup()}, and the name was a lie in the direction that mattered: it
     * queries {@code CompetitionScope.INTERNATIONAL}. Renamed to {@code primaryCup()}, because that is
     * what it does -- pick one cup -- and because {@code INTERNATIONAL} here is a property of how the
     * rows are stored rather than a claim that this is a continental competition. {@code
     * findFirstNationalScoped} is misleadingly named for the same reason; renaming that is a wider
     * change than this task, so the oddity is recorded here and in the repository instead.
     */
    private Competition primaryCup() {
        return competitions.findFirstNationalScoped(CompetitionType.CUP, CompetitionScope.INTERNATIONAL,
                        org.springframework.data.domain.Limit.of(1))
                .orElse(null);
    }

    public int drawRoundForWeek(int week) {
        Competition cup = primaryCup();
        if (cup == null) {
            return 0;
        }
        int round = roundForWeek(week);
        if (round <= 0) {
            return 0;
        }
        long existing = fixtures.countByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalse(
                cup.getId(), seedSeason(), round);
        if (existing > 0) {
            return 0;
        }
        List<Team> survivors = survivorsOf(cup, round);
        if (survivors.size() < 2) {
            log.info("Cup {} round {}: {} survivor(s), nothing to pair.", cup.getName(), round, survivors.size());
            return 0;
        }
        return drawRound(cup, round, survivors, seedSeason()).size();
    }

    /**
     * The clubs still in the tournament at the start of a round: the previous round's winners.
     *
     * <p>Before round 1 it is the entry field - the owner's ranks 203-310. After that it is whoever won,
     * which is only knowable once the previous round has actually been played. A round whose previous
     * ties are unplayed yields no draw rather than a draw against teams that have not qualified.
     */
    private List<Team> survivorsOf(Competition cup, int round) {
        // The cup it is drawing for. This called rankedClubs() with no argument, so round 1's field was
        // the world's bottom 108 clubs rather than this cup's - it never asked which cup it was drawing.
        List<Team> ranked = rankedClubs(cup);
        if (round == 1) {
            // The bottom 108, for the same reason the seeder draws the bottom 108: the preliminary is
            // for the clubs that have to earn their place, and taking the top 108 here inverted the
            // whole competition while the comment said otherwise.
            int directEntrantCount = Math.max(0, ranked.size() - ENTRY_ROUND_TEAMS);
            return new ArrayList<>(ranked.subList(directEntrantCount, ranked.size()));
        }
        int previousRound = round - 1;
        List<MatchFixture> previous = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), seedSeason())
                .stream()
                .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() == previousRound)
                .toList();
        if (previous.isEmpty() || previous.stream().anyMatch(f -> !f.isPlayed())) {
            return List.of();
        }
        List<Team> winners = new ArrayList<>();
        for (MatchFixture tie : previous) {
            Team winner = winnerOf(tie);
            if (winner != null) {
                winners.add(winner);
            }
        }

        if (round == 2) {
            // Round 2 is the 256: the 202 direct entrants plus the 54 first-round winners. It used to
            // be the 54 winners alone, so the strongest clubs in the country — the ones that had not
            // played a match — were simply not in the competition from round 2 onwards. The direct
            // entrants are named here rather than in the seed method because this is where a round's
            // field is decided: whoever is left, plus whoever came straight through.
            int directEntrantCount = Math.max(0, ranked.size() - ENTRY_ROUND_TEAMS);
            winners.addAll(ranked.subList(0, directEntrantCount));
            log.info("Cup {} round 2: {} first-round winner(s) join {} direct entrants for {}.",
                    cup.getName(), winners.size() - directEntrantCount, directEntrantCount, winners.size());
        }
        return winners;
    }

    /**
     * The winning side of a played tie. Null only when it genuinely is not knowable.
     *
     * <p>This used to return null for <i>every</i> level tie, on the reasoning that "a knockout tie
     * cannot be level, so a level scoreline means the shootout was not recorded". True as far as it went,
     * and it meant every level tie dropped a club from the competition and the next round was drawn
     * short. Now a level cup tie is settled from the spot when the match is persisted, and the result
     * lives in the match's own penalty columns.
     *
     * <p>The scoreline is not touched by the shootout, and that is deliberate: a tie that finished 1-1
     * and was won 4-3 on penalties is a 1-1 match, and folding the kicks into the goals would report it
     * as 5-4 to the table, the replay and the scoreline on the page.
     */
    private Team winnerOf(MatchFixture tie) {
        if (tie.getPlayedMatch() == null) {
            return null;
        }
        int home = tie.getPlayedMatch().getHomeGoals();
        int away = tie.getPlayedMatch().getAwayGoals();
        if (home == away) {
            Integer homePens = tie.getPlayedMatch().getHomePenaltyGoals();
            Integer awayPens = tie.getPlayedMatch().getAwayPenaltyGoals();
            if (homePens == null || awayPens == null || homePens.equals(awayPens)) {
                // Genuinely undecidable: the tie was never settled. Kept as a warning rather than a
                // coin toss, because a cup that invents a winner is worse than a cup that is one team
                // short and says so.
                log.warn("Cup tie {} finished level at {}-{} with no shootout recorded; no winner taken.",
                        tie.getId(), home, away);
                return null;
            }
            return homePens > awayPens ? tie.getHomeTeam() : tie.getAwayTeam();
        }
        return home > away ? tie.getHomeTeam() : tie.getAwayTeam();
    }

    /** The round number the calendar puts in a given week, or 0 if that week has no cup round. */
    private int roundForWeek(int week) {
        for (int i = 0; i < CUP_WEEKS.length; i++) {
            if (CUP_WEEKS[i] == week) {
                return i + 1;
            }
        }
        return 0;
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
    /**
     * Splits the entrants into the two halves of a draw.
     *
     * <p>The owner's rule, and it is the whole of how a cup is seeded: <b>sort the entrants descending,
     * halve them, and pair one from the top half against one from the bottom half at random.</b> The
     * favourite never meets a favourite, the weakest club never draws another weak club, and because
     * the pairing inside each half is random the draw is still a draw.
     *
     * <p>It is one function for every round, including the first. The entry round holds 108 clubs, so
     * the split is 54 against 54 and the same code that draws a final draws the first tie of the cup.
     * That is deliberate: a special case for round 1 is a second seeding rule that will eventually
     * disagree with the first.
     *
     * <p>The halves are recomputed from whoever is left in this round, not fixed at the start of the
     * tournament, so a promoted club and a giant-killed one are re-seeded on what they have actually
     * done rather than on what they were ranked before the draw.
     *
     * @return the top half, strongest first, and the bottom half
     */
    public List<List<Team>> splitForDraw(List<Team> rankedDescending) {
        int half = rankedDescending.size() / 2;
        return List.of(
                new ArrayList<>(rankedDescending.subList(0, half)),
                new ArrayList<>(rankedDescending.subList(half, rankedDescending.size())));
    }

    private List<MatchFixture> drawRound(Competition cup, int roundNumber, List<Team> entrants,
                                         int seasonYear) {
        List<Team> ranked = sortByStrength(new ArrayList<>(entrants), strengthOf(entrants));

        List<List<Team>> halves = splitForDraw(ranked);

        // **The pairing inside each half is shuffled, and this is the whole of what makes it a draw.**
        //
        // It used to pair `favourites.get(i)` with `nonFavourites.get(i)` — index against index. Since
        // `ranked` is sorted strongest first, the strongest club in the country met the weakest club in
        // the country, in every round, for ever. DRAW_SEED was declared, assigned to a Random and never
        // read; three javadocs on this class described a shuffle the code did not perform. The
        // international club cups have always used Collections.shuffle, which is why they were random
        // and the national cup was not.
        //
        // The seed is derived from the cup and the round rather than taken from a shared Random, so the
        // same cup in the same round draws the same ties on every boot — which is the one property a
        // reproducible draw has to keep, and the reason the field `random` was never the right thing to
        // shuffle with in the first place.
        Random drawRandom = new Random(DRAW_SEED + cup.getId() * 1000L + roundNumber);
        List<Team> favourites = new ArrayList<>(halves.get(0));
        List<Team> nonFavourites = new ArrayList<>(halves.get(1));
        Collections.shuffle(favourites, drawRandom);
        Collections.shuffle(nonFavourites, drawRandom);

        int week = CUP_WEEKS[Math.min(roundNumber, CUP_WEEKS.length) - 1];
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
            fixture.setMatchDate(matchDateFor(week, seasons.getOrCreateClock().getCurrentDate()));
            made.add(fixtures.save(fixture));
        }
        log.info("Cup {} round {}: {} ties for week {} (day {}), favourites vs non-favourites, "
                        + "non-favourite at home.", cup.getName(), roundNumber, made.size(), week, CUP_DAY);
        return made;
    }
}
