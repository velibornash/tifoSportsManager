package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.service.NationalGroupTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Qualifying groups and the tournament bracket, for both senior and U-21 (owner, 2026-10-06).
 *
 * <h2>Qualifying: 48 nations, 8 groups of 6, week 6</h2>
 *
 * <p>Drawn by ranking into <b>pots of eight</b>: the ranked field is cut into six pots of eight, and
 * from each pot one nation is drawn into each of the eight groups. The owner: <i>"draw them by
 * ranking, each pot gives one per group."</i> That is the guarantee worth having — no group can collect
 * two of the top eight, so the group stage cannot decide the tournament before it is played, which a
 * straight ranked deal into groups can.
 *
 * <p>Five matchdays on days 2 to 6 of week 6, one a day, single round robin.
 *
 * <h2>The tournament: the top two from each group, week 12</h2>
 *
 * <p>Eight groups × two is sixteen, which is exactly a round of sixteen. Round of 16 on day 1,
 * quarter-finals day 2, semi-finals day 4, third place and final on day 6.
 *
 * <h2>Two rules that are the owner's rather than football's</h2>
 *
 * <ul>
 *   <li><b>The worse-rated side hosts every game.</b> Not an alternation: the lower-rated nation is at
 *       home, so it is always the weaker team's stadium.</li>
 *   <li><b>A level knockout tie goes to penalties</b>, and a level group match does not — that is the
 *       difference between deciding a group on points and deciding it from the spot.</li>
 * </ul>
 *
 * <h2>Why the bracket is drawn a round at a time</h2>
 *
 * <p>Because a knockout cannot be drawn in one pass. The quarter-finals do not exist until the round
 * of sixteen has been played, so this is re-entered as the tournament runs and starts <b>from the
 * results</b> rather than from round one. A version that walked in at round one, drew it and returned
 * could never reach round two however many times it was called.
 *
 * <p>A round with an unplayed tie stops the bracket rather than inventing a winner, and so does a tie
 * that finished level with no shootout recorded. A tournament that invents a result is worse than one
 * that is a nation short and says so.
 */
@Component
public class NationalTournamentSeeder {

    private static final Logger log = LoggerFactory.getLogger(NationalTournamentSeeder.class);

    /** One nation's entry in the draw. */
    public record Entrant(Team team, Country country, int rating) {
    }

    /** What one draw produced, so the log can prove it moved something. */
    public record DrawResult(int groups, int entrants, int groupFixtures, int knockoutFixtures, String note) {

        public static DrawResult nothing(String note) {
            return new DrawResult(0, 0, 0, 0, note);
        }
    }

    private final NationalTeamCompetitions catalogue;
    private final NationalGroupTable groupTable;
    private final CountryRepository countries;
    private final MatchFixtureRepository fixtures;
    private final PlayerRepository players;
    private final SeasonCompetitionRepository seasonCompetitions;
    private final GameClockRepository clocks;

    public NationalTournamentSeeder(NationalTeamCompetitions catalogue,
                                    NationalGroupTable groupTable,
                                    CountryRepository countries,
                                    MatchFixtureRepository fixtures,
                                    PlayerRepository players,
                                    SeasonCompetitionRepository seasonCompetitions,
                                    GameClockRepository clocks) {
        this.catalogue = catalogue;
        this.groupTable = groupTable;
        this.countries = countries;
        this.fixtures = fixtures;
        this.players = players;
        this.seasonCompetitions = seasonCompetitions;
        this.clocks = clocks;
    }

    private LocalDateTime seasonStart() {
        return clocks.findById(1L).map(GameClock::getCurrentDate)
                .orElse(LocalDateTime.of(2026, 7, 1, 12, 0));
    }

    // ---------- entrants ----------

    /**
     * The ranked field for a level.
     *
     * <p>A side with no players is not entered. A national team exists for every seeded country, but
     * a country with no clubs has nothing to call up until the bot squad lands, and entering it would
     * produce a fixture that simulates to nothing.
     *
     * <p>Ranked on the country's own column for the level: {@code reputation} for the senior side and
     * {@code youthRating} for the under-21s, which are two separate Elo tracks.
     */
    @Transactional(readOnly = true)
    public List<Entrant> rankedField(NationalTeamLevel level) {
        List<Entrant> entrants = new ArrayList<>();
        for (Country country : countries.findAll()) {
            Team team = level == NationalTeamLevel.U21
                    ? country.getU21NationalTeam() : country.getSeniorNationalTeam();
            if (team == null || team.getId() == null) {
                continue;
            }
            Integer rating = level == NationalTeamLevel.U21
                    ? country.getYouthRating() : country.getReputation();
            if (rating == null) {
                continue;
            }
            List<Player> squad = players.findByTeamId(team.getId());
            if (squad.isEmpty()) {
                continue;
            }
            entrants.add(new Entrant(team, country, rating));
        }
        entrants.sort(Comparator.comparingInt(Entrant::rating).reversed()
                .thenComparing(e -> e.country().getName() == null ? "" : e.country().getName()));
        return entrants;
    }

    /**
     * Deals the ranked field into pots of eight, one per group per pot.
     *
     * <p>Public because the shape of the groups is the part worth arguing about, and an argument is far
     * easier against a method than against a loop buried in a seeder.
     *
     * @return group index to the six nations in it
     */
    public List<List<Entrant>> dealIntoGroups(List<Entrant> ranked, Competition competition, int seasonYear) {
        int field = Math.min(ranked.size(), NationalTournamentSchedule.QUALIFYING_FIELD);
        // A pot holds one nation per *group*, so it holds GROUPS of them, and 48 nations are six
        // pots of eight. Cutting it the other way - field/groups - makes a pot of six and eight pots,
        // which gives every group eight nations and a group stage the owner did not specify.
        int potSize = Math.max(1, NationalTournamentSchedule.GROUPS);
        List<List<Entrant>> groups = new ArrayList<>();
        for (int g = 0; g < NationalTournamentSchedule.GROUPS; g++) {
            groups.add(new ArrayList<>());
        }

        for (int start = 0; start < field; start += potSize) {
            List<Entrant> pot = new ArrayList<>(ranked.subList(start, Math.min(start + potSize, field)));
            // One seed per pot per group, derived from the pot's own index, so a re-run deals the same
            // groups. The randomness is the draw; its reproducibility is what makes the draw a fact.
            Collections.shuffle(pot, new Random(NationalGroupTable.deriveSeed(
                    competition.getId(), seasonYear, "POT" + (start / potSize))));
            for (int index = 0; index < pot.size(); index++) {
                groups.get(index).add(pot.get(index));
            }
        }
        return groups;
    }

    // ---------- qualifying ----------

    /**
     * Draws the qualifying group stage if it has not been drawn.
     *
     * <p>Idempotent by fixture count, not by a flag: the fixtures are the record of whether the draw
     * happened, which is the rule the cup and national-team seeders already use.
     */
    @Transactional
    public DrawResult ensureGroupStage(NationalTeamLevel level, int seasonYear) {
        // **Create the competition first.** This used to return "no competition has been created", which
        // is exactly why the admin re-draw was a no-op: the seeder could not draw into a competition that
        // did not exist, and nothing else made it exist. The four are created on demand here, so any draw
        // - the week-1 job, the admin re-draw, or seeding - leaves a drawable world behind it.
        catalogue.ensureAll();
        Competition competition = catalogue.qualifiers(level).orElse(null);
        if (competition == null) {
            return DrawResult.nothing("No qualifying competition has been created for " + level + ".");
        }
        return buildGroupStage(competition, seasonYear);
    }

    DrawResult buildGroupStage(Competition competition, int seasonYear) {
        List<Entrant> ranked = rankedField(levelOf(competition));
        if (ranked.size() < NationalTournamentSchedule.GROUPS) {
            return DrawResult.nothing("Only " + ranked.size() + " playable sides for "
                    + competition.getName() + "; a group needs one per pot in eight groups.");
        }

        // The guard counts **unplayed** ties only. Counting played ones too - which it used to do -
        // meant a re-draw that deliberately spared the played ties could never finish: one survivor
        // was enough to make the phase read as drawn for the rest of the season.
        long existing = fixtures
                .findByCompetitionIdAndSeasonYearAndPlayedFalse(competition.getId(), seasonYear)
                .stream()
                .filter(f -> f.getGroupCode() != null)
                .count();
        if (existing > 0) {
            return new DrawResult(NationalTournamentSchedule.GROUPS, ranked.size(), (int) existing, 0,
                    "already drawn");
        }

        ensureSeasonCompetition(competition, seasonYear);
        List<List<Entrant>> groups = dealIntoGroups(ranked, competition, seasonYear);
        Map<Long, Integer> ratingOf = new LinkedHashMap<>();
        for (Entrant entrant : ranked) {
            ratingOf.put(entrant.team().getId(), entrant.rating());
        }

        int made = 0;
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            List<Entrant> group = groups.get(groupIndex);
            if (group.size() < 2) {
                continue;
            }
            made += drawGroup(competition, seasonYear, group,
                    NationalTournamentSchedule.groupCode(groupIndex), ratingOf);
        }
        log.info("{}: {} group(s) of {} for {} side(s), {} qualifying fixture(s) across week {} days {}.",
                competition.getName(), groups.size(), NationalTournamentSchedule.GROUP_SIZE,
                ranked.size(), made, NationalTournamentSchedule.QUALIFYING_WEEK,
                java.util.Arrays.toString(NationalTournamentSchedule.QUALIFYING_DAYS));
        return new DrawResult(groups.size(), ranked.size(), made, 0, "drawn");
    }

    /** One group's five matchdays, in the round-robin circle method. */
    private int drawGroup(Competition competition, int seasonYear, List<Entrant> group,
                          String groupCode, Map<Long, Integer> ratingOf) {
        List<Team> ring = new ArrayList<>();
        for (Entrant entrant : group) {
            ring.add(entrant.team());
        }
        int size = ring.size();
        if (size % 2 != 0) {
            ring.add(null);
        }
        int padded = ring.size();

        int made = 0;
        for (int matchday = 0; matchday < padded - 1; matchday++) {
            int day = NationalTournamentSchedule.qualifyingDay(matchday + 1);
            for (int i = 0; i < padded / 2; i++) {
                Team one = ring.get(i);
                Team other = ring.get(padded - 1 - i);
                if (one == null || other == null) {
                    continue;
                }
                // The worse-rated side hosts every game, so it is always the weaker team's stadium.
                boolean oneHosts = ratingOf.getOrDefault(one.getId(), 0) <= ratingOf.getOrDefault(other.getId(), 0);
                made += fixture(competition, seasonYear,
                        oneHosts ? one : other, oneHosts ? other : one,
                        matchday + 1, NationalTournamentSchedule.QUALIFYING_WEEK, day, groupCode);
            }
            Team last = ring.remove(padded - 1);
            ring.add(1, last);
        }
        return made;
    }

    // ---------- the tournament ----------

    /**
     * Draws as much of the tournament as the results allow, and stops where it must.
     *
     * <p>One round per call is deliberate: a round cannot be drawn before the one before it has been
     * played, so this is re-entered as the tournament runs.
     */
    @Transactional
    public DrawResult ensureKnockouts(NationalTeamLevel level, int seasonYear) {
        catalogue.ensureAll();
        Competition competition = catalogue.tournament(level).orElse(null);
        if (competition == null) {
            return DrawResult.nothing("No tournament competition has been created for " + level + ".");
        }
        return buildKnockouts(competition, seasonYear);
    }

    DrawResult buildKnockouts(Competition competition, int seasonYear) {
        ensureSeasonCompetition(competition, seasonYear);
        List<MatchFixture> all = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(competition.getId(), seasonYear);

        // An empty bracket is the normal first call, not a reason to stop: the round of sixteen has to be
        // drawn precisely because nothing is here yet. The guard that matters is below, where a round
        // is drawn but unplayed.
        if (all.isEmpty()) {
            log.debug("{}: nothing drawn yet; starting from the round of 16.", competition.getName());
        }

        List<Team> alive = new ArrayList<>();
        int made = 0;

        for (int round : NationalTournamentSchedule.FEED_FORWARD_ROUNDS) {
            List<MatchFixture> thisRound = all.stream()
                    .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() == round)
                    .toList();

            if (thisRound.isEmpty()) {
                if (round == NationalTournamentSchedule.ROUND_LAST_SIXTEEN) {
                    // The standings live in the *qualifying* competition, not this one. Reading them from the
                    // tournament found nothing at all — it has no group fixtures — so the round of
                    // sixteen was never drawn, and it failed as an empty bracket rather than as an
                    // error.
                    Competition qualifyingCompetition = catalogue.qualifiers(levelOf(competition)).orElse(null);
                    if (qualifyingCompetition == null) {
                        return DrawResult.nothing("No qualifying competition exists to read the field from.");
                    }
                    List<Team> qualifiers = qualifiersFor(qualifyingCompetition, seasonYear);
                    if (qualifiers.size() < 2) {
                        return DrawResult.nothing("Only " + qualifiers.size()
                                + " side(s) qualified; the round of sixteen cannot be drawn.");
                    }
                    made += drawRoundOfSixteen(competition, seasonYear, qualifiers);
                    return new DrawResult(0, qualifiers.size(), 0, made, "round of 16 drawn");
                }
                if (alive.size() < 2) {
                    return DrawResult.nothing(alive.size() + " side(s) alive; the bracket stops.");
                }
                made += drawOneRound(competition, seasonYear, alive, round);
                return new DrawResult(0, 0, 0, made, NationalTournamentSchedule.roundLabel(round) + " drawn");
            }

            if (thisRound.stream().anyMatch(f -> !f.isPlayed())) {
                long outstanding = thisRound.stream().filter(f -> !f.isPlayed()).count();
                return new DrawResult(0, 0, 0, made,
                        NationalTournamentSchedule.roundLabel(round) + " has " + outstanding + " unplayed tie(s).");
            }

            List<Team> winners = new ArrayList<>();
            List<MatchFixture> undecided = new ArrayList<>();
            for (MatchFixture tie : thisRound) {
                Team winner = winnerOf(tie);
                if (winner == null) {
                    undecided.add(tie);
                } else {
                    winners.add(winner);
                }
            }
            if (!undecided.isEmpty()) {
                log.warn("{}: round {} has {} tie(s) with no recorded winner; the bracket stops.",
                        competition.getName(), round, undecided.size());
                return new DrawResult(0, 0, 0, made, "a tie has no recorded winner");
            }
            alive = winners;
        }

        // Past the semi-finals the two survivors are the finalists, and this draw has to be **here**
        // rather than inside the loop. The loop walks only the rounds whose winners feed forward, so it
        // exits with two teams alive — and the final used to be an `if (round == ROUND_FINAL)` branch
        // inside it, which could never execute: no round 5 is ever iterated. Every tournament therefore
        // stopped at two finalists, and the log cheerfully said "tournament complete".
        //
        // That is the same class of bug as the one this method was rewritten to fix — a branch that was
        // written and never reached — one level up, which is why it is worth stating rather than just
        // moving.
        // Guarded, and the guard is the whole reason this has to be checked at all: the loop above
        // only ever walks the rounds that feed forward, so on *every* call made after the semi-finals
        // are played it exits with the same two survivors and would draw the final again. Measured
        // before the guard existed: three finals and no complaint, because nothing was counting.
        //
        // One round per call is only safe if "already drawn" is asked about the round being drawn.
        boolean finalDrawn = all.stream()
                .anyMatch(f -> f.getRoundNumber() != null
                        && f.getRoundNumber() == NationalTournamentSchedule.ROUND_FINAL);
        if (finalDrawn) {
            return new DrawResult(0, 0, 0, made, "the final is already drawn.");
        }

        if (alive.size() == 2) {
            made += drawFinalAndThirdPlace(competition, seasonYear, alive, all);
            return new DrawResult(0, 0, 0, made, "final and third place drawn");
        }
        if (alive.size() > 2) {
            log.warn("{}: {} side(s) survived the semi-finals, which is not a bracket; nothing drawn.",
                    competition.getName(), alive.size());
        }
        return new DrawResult(0, 0, 0, made, alive.isEmpty()
                ? "tournament complete" : alive.size() + " side(s) alive before the final.");
    }

    /**
     * The sixteen that qualified: the top two of each group, ranked overall.
     *
     * <p>Group winners first so the bracket can be seeded standard-fashion, which keeps two group
     * winners from meeting in the first round.
     */
    @Transactional(readOnly = true)
    public List<Team> qualifiersFor(Competition qualifying, int seasonYear) {
        List<Team> qualifiers = new ArrayList<>();
        List<NationalGroupTable.Standing> winners = new ArrayList<>();
        for (int g = 0; g < NationalTournamentSchedule.GROUPS; g++) {
            List<NationalGroupTable.Standing> group =
                    groupTable.standings(qualifying, seasonYear, NationalTournamentSchedule.groupCode(g));
            qualifiers.addAll(group.stream().limit(NationalTournamentSchedule.QUALIFY_PER_GROUP)
                    .map(NationalGroupTable.Standing::team).toList());
            if (!group.isEmpty()) {
                winners.add(group.get(0));
            }
        }
        return bracketSeed(qualifiers, winners);
    }

    /**
     * The round of sixteen, seeded 1 v 16, 8 v 9 and so on.
     *
     * <p>Winners seeded by group standing, then the runners-up, so the two strongest group winners are
     * kept apart for as long as the bracket allows.
     */
    private List<Team> bracketSeed(List<Team> qualifiers, List<NationalGroupTable.Standing> winners) {
        List<Team> seeded = new ArrayList<>();
        List<Team> runnerUp = new ArrayList<>(qualifiers);
        for (NationalGroupTable.Standing winner : winners) {
            seeded.add(winner.team());
            runnerUp.remove(winner.team());
        }
        seeded.addAll(runnerUp);
        List<Team> pairs = new ArrayList<>();
        int top = seeded.size() / 2;
        for (int i = 0; i < top; i++) {
            pairs.add(seeded.get(i));
            pairs.add(seeded.get(seeded.size() - 1 - i));
        }
        return pairs;
    }

    private int drawRoundOfSixteen(Competition competition, int seasonYear, List<Team> paired) {
        int made = 0;
        for (int i = 0; i + 1 < paired.size(); i += 2) {
            made += fixture(competition, seasonYear, paired.get(i), paired.get(i + 1),
                    NationalTournamentSchedule.ROUND_LAST_SIXTEEN, NationalTournamentSchedule.TOURNAMENT_WEEK,
                    NationalTournamentSchedule.tournamentDay(NationalTournamentSchedule.ROUND_LAST_SIXTEEN), null);
        }
        log.info("{}: round of 16 drawn, {} tie(s).", competition.getName(), made);
        return made;
    }

    private int drawOneRound(Competition competition, int seasonYear, List<Team> alive, int round) {
        int made = 0;
        List<Team> ordered = new ArrayList<>(alive);
        Collections.shuffle(ordered, new Random(NationalGroupTable.deriveSeed(competition.getId(), seasonYear, "R" + round)));
        int day = NationalTournamentSchedule.tournamentDay(round);
        for (int i = 0; i + 1 < ordered.size(); i += 2) {
            made += fixture(competition, seasonYear, ordered.get(i), ordered.get(i + 1), round,
                    NationalTournamentSchedule.TOURNAMENT_WEEK, day, null);
        }
        log.info("{}: {} drawn, {} tie(s).", competition.getName(),
                NationalTournamentSchedule.roundLabel(round), made);
        return made;
    }

    private int drawFinalAndThirdPlace(Competition competition, int seasonYear, List<Team> finalists,
                                       List<MatchFixture> all) {
        int made = 0;
        int finalDay = NationalTournamentSchedule.tournamentDay(NationalTournamentSchedule.ROUND_FINAL);
        made += fixture(competition, seasonYear, finalists.get(0), finalists.get(1),
                NationalTournamentSchedule.ROUND_FINAL, NationalTournamentSchedule.TOURNAMENT_WEEK, finalDay, null);

        List<Team> semiLosers = new ArrayList<>();
        for (MatchFixture tie : all) {
            if (tie.getRoundNumber() == null
                    || tie.getRoundNumber() != NationalTournamentSchedule.ROUND_SEMI_FINAL
                    || !tie.isPlayed()) {
                continue;
            }
            Team winner = winnerOf(tie);
            if (winner != null) {
                semiLosers.add(winner == tie.getHomeTeam() ? tie.getAwayTeam() : tie.getHomeTeam());
            }
        }
        if (semiLosers.size() == 2) {
            made += fixture(competition, seasonYear, semiLosers.get(0), semiLosers.get(1),
                    NationalTournamentSchedule.ROUND_THIRD_PLACE, NationalTournamentSchedule.TOURNAMENT_WEEK, finalDay, null);
        } else {
            log.warn("{}: final drawn, but {} losing semi-finalist(s) found, so no third-place tie.",
                    competition.getName(), semiLosers.size());
        }
        return made;
    }

    /**
     * The winner of a played tie, or null when there is genuinely none.
     *
     * <p>A level tie is decided by the shootout columns. A tie that is level <i>and</i> has no shootout
     * recorded returns null and stops the bracket, rather than being settled by a coin.
     */
    private Team winnerOf(MatchFixture tie) {
        Match played = tie.getPlayedMatch();
        if (played == null) {
            return null;
        }
        int home = played.getHomeGoals();
        int away = played.getAwayGoals();
        if (home == away) {
            Integer homePens = played.getHomePenaltyGoals();
            Integer awayPens = played.getAwayPenaltyGoals();
            if (homePens == null || awayPens == null || homePens.equals(awayPens)) {
                log.warn("{} tie {} finished level at {}-{} with no shootout recorded; no winner taken.",
                        tie.getCompetition() == null ? "?" : tie.getCompetition().getName(), tie.getId(), home, away);
                return null;
            }
            return homePens > awayPens ? tie.getHomeTeam() : tie.getAwayTeam();
        }
        return home > away ? tie.getHomeTeam() : tie.getAwayTeam();
    }

    // ---------- rows ----------

    private SeasonCompetition ensureSeasonCompetition(Competition competition, int seasonYear) {
        return seasonCompetitions.findByCompetitionAndSeasonYear(competition, seasonYear)
                .orElseGet(() -> {
                    SeasonCompetition sc = new SeasonCompetition();
                    sc.setCompetition(competition);
                    sc.setSeasonYear(seasonYear);
                    sc.setFinished(false);
                    return seasonCompetitions.save(sc);
                });
    }

    private int fixture(Competition competition, int seasonYear, Team home, Team away, int round, int week,
                        int day, String groupCode) {
        MatchFixture f = new MatchFixture();
        f.setCompetition(competition);
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setSeasonYear(seasonYear);
        f.setRoundNumber(round);
        f.setWeekNumber(week);
        f.setDayNumber(day);
        f.setGroupCode(groupCode);
        f.setPlayed(false);
        f.setMatchDate(NationalTournamentSchedule.matchDate(seasonStart(), week, day));
        return save(f);
    }

    private int save(MatchFixture fixture) {
        fixtures.save(fixture);
        return 1;
    }

    /** Which level a competition belongs to, read from the competition rather than its name. */
    private NationalTeamLevel levelOf(Competition competition) {
        return competition.getNationalLevel() == null
                ? NationalTeamLevel.SENIOR : competition.getNationalLevel();
    }

    /** Both stages for one level, for an admin button. */
    @Transactional
    public DrawResult seedBoth(NationalTeamLevel level, int seasonYear) {
        DrawResult qualifying = ensureGroupStage(level, seasonYear);
        DrawResult tournament = ensureKnockouts(level, seasonYear);
        return new DrawResult(qualifying.groups(), qualifying.entrants(),
                qualifying.groupFixtures(), tournament.knockoutFixtures(),
                qualifying.note() + "; tournament: " + tournament.note());
    }

    /** Every level, for the admin button. */
    public List<DrawResult> seedAll(int seasonYear) {
        List<DrawResult> results = new ArrayList<>();
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            results.add(seedBoth(level, seasonYear));
        }
        return results;
    }

    /** Exposed so the admin screen can say what is and is not built. */
    public boolean stageFor(NationalTeamLevel level) {
        return catalogue.qualifiers(level).isPresent() && catalogue.tournament(level).isPresent();
    }

    /** The stage a competition's rating should use, never read off its name. */
    public static NationalStage stageOf(Competition competition) {
        return competition == null || competition.getNationalStage() == null
                ? NationalStage.OTHER : competition.getNationalStage();
    }
}