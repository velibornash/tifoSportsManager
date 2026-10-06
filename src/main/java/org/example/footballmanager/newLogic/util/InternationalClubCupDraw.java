package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The international club cups: a group stage and a knockout bracket (owner, 2026-09-30).
 *
 * <h2>The format, as specified</h2>
 *
 * <pre>
 *   Champions Cup    one club per league          -> 8 groups of 6, single round robin, 5 matchdays
 *   Masters Cup      2nd and 3rd of every league   -> 16 groups of 6, same, 5 matchdays
 *   Challenge Cup    4th of every league           -> 8 groups of 6, same, 5 matchdays
 * </pre>
 *
 * <p><b>Who goes through differs, and that is the whole difference between them.</b> The top two from
 * each Champions group reach the knockouts; only the winner of each Masters group does; the Challenge
 * Cup is the same as the Champions. From the knockout stage they are identical: 1/8, 1/4, 1/2, a final
 * and a third-place play-off.
 *
 * <p><b>Week 6 is a national-team pause</b>, so it carries no club match, and week 12 is the other
 * national-team week. The five group matchdays therefore fill weeks 1-5 exactly and the knockouts take
 * weeks 7-10, with the final and the third-place play-off sharing week 10.
 *
 * <h2>The group draw is mine, and it is the one choice here that was not specified</h2>
 *
 * <p>Ranked clubs are dealt into groups in a <b>serpentine</b>, so group strength is spread rather than
 * bunched: the strongest club goes to group A, the second to group B, and so on round by round, reversing
 * direction on each pass. Dealing the top eight into one group each and the next eight into one group
 * each would make every knockout tie decided before it started, and putting the eight strongest into a
 * single group would decide the Champions Cup in the group stage alone.
 *
 * <h2>Where a club's group is recorded</h2>
 *
 * <p>On its fixtures, as a group code. Not in a table of its own: a group's membership is fully
 * determined by who appears in its five matchdays, and a second record of it would be a second thing to
 * fall out of step with the fixtures. The knockouts read a club's group by looking at the fixture it
 * played in the group stage, which is the same fact from the same place.
 */
@Service
public class InternationalClubCupDraw {

    private static final Logger log = LoggerFactory.getLogger(InternationalClubCupDraw.class);

    /** Teams per group. The owner: 8 groups of 6, and 16 groups of 6 for the Masters Cup. */
    public static final int GROUP_SIZE = 6;

    /** Single round robin, so 5 matchdays. */
    public static final int GROUP_MATCHDAYS = GROUP_SIZE - 1;

    /**
     * Day 1, 20:45 — the international slot, not the domestic cup slot (owner, 2026-10-06).
     *
     * <p>Was day 5 at 18:00, which is the <b>domestic</b> cup's slot. The country-side calendar says
     * day 1 is International and day 5 is Cup, and all fifteen of these competitions are international:
     * the Champions Cup, the Masters Cup and the Challenge Cup at every tier. Day 5 stays the national
     * cup and nothing else.
     */
    public static final int CUP_DAY = 1;

    /**
     * Five group matchdays in weeks 1-5, then the knockouts in weeks 7-10 — owner, 2026-10-06.
     *
     * <p>Week 6 is skipped because it is a national-team match week, and week 12 is the other one.
     * Week 11 belongs to the league promotion play-off.
     *
     * <p><b>Nine weeks, not ten, and the last two rounds share week 10.</b> There are five knockout
     * rounds — last sixteen, quarter, semi, third place, final — and four knockout weeks, so the final
     * and the third-place play-off are both played in week 10. The owner's words were "5 meceva izmedju
     * week 1 i week 5" and "eliminaciona faza izmedju week 7 i week 10"; the fifth round fits because
     * the final and the third place are decided the same evening, which is how a cup final day works.
     *
     * <p>{@link #weekFor} already clamps its index to this array's length, so a tenth stage resolves to
     * the ninth week rather than throwing — which is what puts the final on week 10 without a special
     * case anywhere else.
     */
    public static final int[] CUP_WEEKS = {1, 2, 3, 4, 5, 7, 8, 9, 10};

    /** How many from each group reach the knockouts, by cup. */
    public static final int CHAMPIONS_QUALIFY_PER_GROUP = 2;
    public static final int MASTERS_QUALIFY_PER_GROUP = 1;
    public static final int CHALLENGE_QUALIFY_PER_GROUP = 2;

    /** The knockout stages, in order, with the round number each is stored as. */
    public static final int ROUND_LAST_SIXTEEN = 6;
    public static final int ROUND_QUARTER_FINAL = 7;
    public static final int ROUND_SEMI_FINAL = 8;
    public static final int ROUND_THIRD_PLACE = 9;
    public static final int ROUND_FINAL = 10;

    private final CompetitionRepository competitions;
    private final MatchFixtureRepository fixtures;
    private final CompetitionEntryRepository entries;
    private final SeasonCompetitionRepository seasonCompetitions;
    private final TransactionTemplate requiresNew;
    private final org.example.footballmanager.newLogic.repository.GameClockRepository clocks;
    private final LazySquadGenerator lazySquadGenerator;

    /**
     * The game clock's season start, which is where a fixture date is measured from.
     *
     * <p>Was {@code LocalDate.of(2026, 7, 1)}, hardcoded - B4. Once the clock passed 2026-07-06 no cup
     * fixture fell inside the two-day recovery window at all, so zone loads were written and never read.
     * The league path already used {@code clock.getCurrentDate()} and this now matches it.
     */
    private java.time.LocalDateTime seasonStart() {
        return clocks.findById(1L)
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentDate)
                .orElse(java.time.LocalDateTime.of(2026, 7, 1, 12, 0));
    }

    public InternationalClubCupDraw(CompetitionRepository competitions,
                                     MatchFixtureRepository fixtures,
                                     CompetitionEntryRepository entries,
                                     SeasonCompetitionRepository seasonCompetitions,
                                     org.springframework.transaction.PlatformTransactionManager transactionManager,
                                     org.example.footballmanager.newLogic.repository.GameClockRepository clocks,
                                     LazySquadGenerator lazySquadGenerator) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.entries = entries;
        this.seasonCompetitions = seasonCompetitions;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.clocks = clocks;
        this.lazySquadGenerator = lazySquadGenerator;
        this.requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One competition's field, and what it produced. */
    public record DrawResult(String cup, int groups, int clubs, int groupFixtures, int knockoutFixtures) {
    }

    /**
     * Creates the group stage for a cup, if it does not have one.
     *
     * <p>Committed on its own, for the same reason the cups themselves are: the boot transaction has lost
     * three writes in this codebase and there is no reason to give it a fourth.
     */
    public DrawResult ensureGroupStage(Competition cup, List<Team> entrants, int qualifyPerGroup,
                                      int seasonYear) {
        return requiresNew.execute(status -> buildGroupStage(cup, entrants, qualifyPerGroup, seasonYear));
    }

    /**
     * The smallest field that is worth a group stage.
     *
     * <p>Below this a group stage is a formality: eight groups of six needs 48 clubs, and the tiers of
     * this world do not all have them — a tier with two divisions has two clubs in its Champions Cup.
     * Rather than pad it or drop clubs, a field this size goes straight to a knockout, which is what a
     * competition with four entrants is.
     *
     * <p><b>This threshold is mine, not the owner's.</b> The owner specified "8 groups of 6, top two
     * through" for a 48-club Champions Cup; the per-tier structure means most fields are smaller, and
     * what counts as "too small for groups" is the one judgement call left to make.
     */
    public static final int MIN_FIELD_FOR_GROUPS = 8;

    /** How many groups a field is split into: as close to six as the field allows. */
    public int groupCountFor(int entrants) {
        if (entrants < GROUP_SIZE) {
            return Math.max(1, entrants);
        }
        return (int) Math.ceil(entrants / (double) GROUP_SIZE);
    }

    @Transactional
    DrawResult buildGroupStage(Competition cup, List<Team> entrants, int qualifyPerGroup, int seasonYear) {
        SeasonCompetition sc = ensureSeasonCompetition(cup, seasonYear);

        // **Before the idempotency check, and deliberately (P0-CUPS-3).**
        //
        // The cups are the first thing in this game that makes two bot clubs play each other, and 46 of
        // the 48 countries are simulated with no players at all. Without this, a Champions Cup tie between
        // two of them reaches SimMatchService.loadRealSquad(), gets null for both sides, and is handed to
        // SimTeamFactory.addTeam() — synthetic placeholders. The competition would be decided by 22
        // unnamed stand-ins and the tier rating ladder would be invisible, because a synthetic squad has
        // no rating to average.
        //
        // It runs before the "already drawn" early return on purpose. A re-run of this method is the
        // normal state of a world that has been repaired, and a club whose squad was cleared out from
        // under it still has to be able to turn up. The squad fill is idempotent on its own terms — it
        // skips any club that already has players.
        int squadsGiven = lazySquadGenerator.ensureSquadsForCupEntrants(entrants);

        long existing = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), seasonYear)
                .stream()
                .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() <= GROUP_MATCHDAYS)
                .count();
        if (existing > 0) {
            log.info("{}: {} group-stage fixture(s) already drawn.", cup.getName(), existing);
            return new DrawResult(cup.getName(), 0, entrants.size(), (int) existing, 0);
        }

        if (entrants.size() < MIN_FIELD_FOR_GROUPS) {
            // Too few clubs for a group stage to mean anything. The competition is real and the clubs
            // are real; it simply starts at the knockout, and the log says so rather than drawing two
            // groups of one and calling it a group stage.
            log.info("{}: {} club(s) is too small a field for a group stage; it starts at the knockout.",
                    cup.getName(), entrants.size());
            ensureTableRows(cup, sc, entrants);
            return new DrawResult(cup.getName(), 0, entrants.size(), 0, 0);
        }

        List<List<Team>> groups = dealIntoGroups(entrants);
        List<MatchFixture> made = new ArrayList<>();
        Map<Team, String> groupOf = new LinkedHashMap<>();

        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            String code = groupCode(groupIndex);
            List<Team> group = groups.get(groupIndex);
            for (Team team : group) {
                groupOf.put(team, code);
            }
            made.addAll(drawGroup(cup, sc, group, code, seasonYear));
        }

        ensureTableRows(cup, sc, entrants);

        cup.setTeamsPerCompetition(entrants.size());
        competitions.save(cup);

        log.info("{}: {} group(s) of {} for {} club(s), {} group-stage fixture(s), top {} of each group "
                        + "reach the knockouts.",
                cup.getName(), groups.size(), GROUP_SIZE, entrants.size(), made.size(), qualifyPerGroup);
        return new DrawResult(cup.getName(), groups.size(), entrants.size(), made.size(), 0);
    }

    /**
     * Deals ranked clubs into groups, strongest first, in a serpentine.
     *
     * <p>Public because the shape of the groups is the part worth arguing about, and an argument is much
     * easier against a method than against a loop buried in a seeder.
     */
    public List<List<Team>> dealIntoGroups(List<Team> rankedDescending) {
        // **groupCountFor, not `size() / GROUP_SIZE` — P0-CUPS-5.**
        //
        // This was integer division, so it floored. Eight entrants gave `8 / 6 = 1` group, and the eight
        // champions of a small field were dealt into **one group of eight**: seven matchdays instead of
        // five, whose round numbers ran 1..7 — and rounds 6 and 7 are the last sixteen and the quarter
        // final. The bracket then read a knockout stage out of the middle of a group stage.
        //
        // It never showed for a real field, because the fields are 48, 96 and 48 and all three divide
        // exactly by six. It shows the moment a country is removed or a tier is short.
        //
        // The two methods also disagreed with each other: `groupCountFor` already answered this question
        // with a ceiling, so the count that was asked for and the count that was built were not the same
        // number. One method answers it now.
        int groupCount = Math.max(1, groupCountFor(rankedDescending.size()));
        List<List<Team>> groups = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(new ArrayList<>());
        }
        for (int index = 0; index < rankedDescending.size(); index++) {
            // Reversing direction on every pass is the serpentine: without it the eight strongest clubs
            // all land in the same group and the Champions Cup is decided in the group stage.
            int pass = index / groupCount;
            int offset = index % groupCount;
            int group = pass % 2 == 0 ? offset : groupCount - 1 - offset;
            groups.get(group).add(rankedDescending.get(index));
        }
        // A group larger than six has more than five matchdays, and its round numbers would run into the
        // knockout's. The count above cannot produce one, and this says so rather than letting it happen.
        for (List<Team> group : groups) {
            if (group.size() > GROUP_SIZE) {
                throw new IllegalStateException("a group of " + group.size() + " clubs was dealt for "
                        + rankedDescending.size() + " entrants; a group may hold at most " + GROUP_SIZE
                        + ", or its matchdays run past " + GROUP_MATCHDAYS + " and collide with the "
                        + "knockout rounds.");
            }
        }
        return groups;
    }

    /** The matchdays of one group, in the round-robin circle method. */
    private List<MatchFixture> drawGroup(Competition cup, SeasonCompetition sc, List<Team> group,
                                          String groupCode, int seasonYear) {
        List<Team> ring = new ArrayList<>(group);
        if (ring.size() % 2 != 0) {
            ring.add(null); // a bye, which is what an odd group needs rather than a dropped club
        }
        int size = ring.size();
        List<MatchFixture> made = new ArrayList<>();

        // A group of one has no matchdays, and a group of two has one. The full six-team group has
        // five, which is where GROUP_MATCHDAYS comes from.
        int matchdays = Math.max(0, size - 1);
        for (int matchday = 0; matchday < matchdays; matchday++) {
            for (int i = 0; i < size / 2; i++) {
                Team home = ring.get(i);
                Team away = ring.get(size - 1 - i);
                if (home == null || away == null) {
                    continue;
                }
                made.add(fixtures.save(fixture(cup, sc, home, away, matchday + 1,
                        weekFor(1 + matchday), groupCode, seasonYear)));
            }
            // Rotate, keeping the first team fixed.
            Team last = ring.remove(size - 1);
            ring.add(1, last);
        }
        return made;
    }

    /**
     * Draws the knockout rounds from the finished group stage.
     *
     * <p>No-op until every group fixture is played, because a knockout drawn from a half-played group
     * stage would send a club into the quarter-finals that has not earned it.
     */
    public DrawResult ensureKnockouts(Competition cup, int qualifyPerGroup, int seasonYear) {
        return requiresNew.execute(status -> buildKnockouts(cup, qualifyPerGroup, seasonYear));
    }

    /**
     * Draws as much of the knockout as the results so far allow, and stops where it must.
     *
     * <p><b>One round per call is not the bug; starting at the last sixteen every time is.</b> A
     * knockout cannot be drawn in one pass — the quarter-finals do not exist until the last sixteen have
     * been played, so this has to be re-entered as the cup progresses. What it used to do was walk in at
     * round one, draw it, and `return` from inside the loop, which meant rounds two and three were
     * unreachable for ever and the tournament stopped at the first knockout round however many times this
     * was called.
     *
     * <p>So it now <b>starts from the results rather than from the beginning</b>: it walks the rounds in
     * order, and at each one asks what is already there.
     *
     * <ul>
     *   <li>round not drawn yet, and its entrants are known &rarr; draw it, then stop;</li>
     *   <li>round drawn but not finished &rarr; stop, the results are not in;</li>
     *   <li>round drawn and finished &rarr; carry the winners into the next one;</li>
     *   <li>two clubs left &rarr; this is the final, and the losing semi-finalists get the third-place
     *       play-off, because a knockout with no third-place match is not the format the owner wrote.</li>
     * </ul>
     *
     * <p>It draws at most one round per call and that is deliberate: the caller is the cup-draw job, which
     * runs on the cup matchday, and a round cannot be drawn before the one before it has been played.
     */
    @Transactional
    DrawResult buildKnockouts(Competition cup, int qualifyPerGroup, int seasonYear) {
        SeasonCompetition sc = ensureSeasonCompetition(cup, seasonYear);
        List<MatchFixture> allFixtures = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), seasonYear);

        List<MatchFixture> groupFixtures = allFixtures.stream()
                .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() <= GROUP_MATCHDAYS)
                .toList();
        if (groupFixtures.isEmpty()) {
            // Small field (< MIN_FIELD_FOR_GROUPS): no group fixtures, but the table rows were written
            // (see buildGroupStage at line 218). Read the entrants from those entries and proceed
            // straight to the knockout, which is what the owner's format says.
            List<CompetitionEntry> tableEntries = entries.findBySeasonCompetition(sc);
            if (!tableEntries.isEmpty()) {
                List<Team> fromTable = tableEntries.stream()
                        .map(CompetitionEntry::getTeam)
                        .sorted((a, b) -> {
                            CompetitionEntry ea = entryFor(sc, a);
                            CompetitionEntry eb = entryFor(sc, b);
                            return LeagueTableOrder.comparator().compare(ea, eb);
                        })
                        .toList();
                log.info("{}: field of {} club(s) has no group stage; starting knockout from table.",
                        cup.getName(), fromTable.size());
                List<Team> qualifiers = fromTable.subList(0,
                        Math.min(qualifyPerGroup * 8, fromTable.size()));
                // The knockout for a small field: start from R16 (6) or adjust. For simplicity,
                // draw the final directly if only 2 clubs remain after filtering, else start at R16.
                if (qualifiers.size() < 2) {
                    return new DrawResult(cup.getName(), 0, qualifiers.size(), 0, 0);
                }
                int made = 0;
                List<Team> alive = new ArrayList<>(qualifiers);
                // A small field skips straight to knockouts; draw the rounds that apply.
                for (int round = ROUND_LAST_SIXTEEN; round <= ROUND_SEMI_FINAL; round++) {
                    final int stage = round;
                    final List<Team> aliveHere = new ArrayList<>(alive);
                    List<MatchFixture> thisRound = allFixtures.stream()
                            .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() == stage)
                            .toList();
                    if (!thisRound.isEmpty() && thisRound.stream().anyMatch(f -> !f.isPlayed())) {
                        return new DrawResult(cup.getName(), 0, qualifiers.size(), 0, made);
                    }
                    if (thisRound.isEmpty()) {
                        if (aliveHere.size() == 2) {
                            made += drawFinalAndThirdPlace(cup, sc, aliveHere, seasonYear);
                            return new DrawResult(cup.getName(), 0, qualifiers.size(), 0, made);
                        }
                        if (aliveHere.size() < 2) {
                            return new DrawResult(cup.getName(), 0, qualifiers.size(), 0, made);
                        }
                        made += drawOneKnockoutRound(cup, sc, aliveHere, stage, seasonYear);
                        // The fixtures were just drawn but not played; winners will be read on the
                        // next call when the fixtures have results. The return stops here by design.
                        return new DrawResult(cup.getName(), 0, qualifiers.size(), 0, made);
                    }
                    // Round finished: carry winners forward
                    List<Team> winners = new ArrayList<>();
                    for (MatchFixture tie : thisRound) {
                        Team w = winnerOf(tie);
                        if (w != null) winners.add(w);
                    }
                    if (winners.isEmpty()) {
                        return new DrawResult(cup.getName(), 0, qualifiers.size(), 0, made);
                    }
                    alive = winners;
                }
                if (alive.size() == 2) {
                    made += drawFinalAndThirdPlace(cup, sc, alive, seasonYear);
                }
                return new DrawResult(cup.getName(), 0, qualifiers.size(), 0, made);
            }
            return new DrawResult(cup.getName(), 0, 0, 0, 0);
        }
        if (groupFixtures.stream().anyMatch(f -> !f.isPlayed())) {
            long outstanding = groupFixtures.stream().filter(f -> !f.isPlayed()).count();
            log.info("{}: {} group-stage fixture(s) unplayed; no knockout drawn yet.",
                    cup.getName(), outstanding);
            return new DrawResult(cup.getName(), 0, 0, groupFixtures.size(), 0);
        }

        List<Team> qualifiers = qualifiers(cup, sc, groupFixtures, qualifyPerGroup);
        log.info("{}: {} club(s) through from the group stage.", cup.getName(), qualifiers.size());
        if (qualifiers.size() < 2) {
            return new DrawResult(cup.getName(), 0, qualifiers.size(), groupFixtures.size(), 0);
        }

        int made = 0;
        // Reassigned each round as winners are carried forward, so it cannot be effectively final - and
        // the lambdas below close over it.
        List<Team> alive = qualifiers;

        for (int round = ROUND_LAST_SIXTEEN; round <= ROUND_SEMI_FINAL; round++) {
            // Both captured below, and a for-loop variable is not effectively final. Two copies rather
            // than restructuring the loop into an array iteration for the sake of a lambda.
            final int stage = round;
            final List<Team> aliveHere = alive;
            List<MatchFixture> thisRound = allFixtures.stream()
                    .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() == stage)
                    .toList();

            if (!thisRound.isEmpty() && thisRound.stream().anyMatch(f -> !f.isPlayed())) {
                long outstanding = thisRound.stream().filter(f -> !f.isPlayed()).count();
                log.info("{}: round {} drawn with {} tie(s) still unplayed; nothing further.",
                        cup.getName(), stage, outstanding);
                return new DrawResult(cup.getName(), 0, qualifiers.size(), groupFixtures.size(), made);
            }

            if (thisRound.isEmpty()) {
                // Two clubs at this point means the previous round was a semi-final, so this is the final
                // and the losers of that semi-final are the third-place contestants.
                if (aliveHere.size() == 2) {
                    return new DrawResult(cup.getName(), 0, qualifiers.size(), groupFixtures.size(),
                            made + drawFinalAndThirdPlace(cup, sc, aliveHere, seasonYear));
                }
                if (aliveHere.size() < 2) {
                    return new DrawResult(cup.getName(), 0, qualifiers.size(), groupFixtures.size(), made);
                }
                made += drawOneKnockoutRound(cup, sc, aliveHere, stage, seasonYear);
                log.info("{}: round {} drawn, {} tie(s) in total; the next round waits for results.",
                        cup.getName(), stage, made);
                return new DrawResult(cup.getName(), 0, qualifiers.size(), groupFixtures.size(), made);
            }

            // This round is finished: the winners go into the next one, and a tie with no winner stops
            // the bracket rather than inventing one.
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
                log.warn("{}: round {} has {} tie(s) with no recorded winner; the bracket stops here.",
                        cup.getName(), round, undecided.size());
                return new DrawResult(cup.getName(), 0, qualifiers.size(), groupFixtures.size(), made);
            }
            alive = winners;
        }

        // Past the last round the loop covers, and the two survivors are the finalists. This has to be
        // here rather than inside the loop: the loop stops at the semi-final, so on the run that plays
        // the semi-finals it exits with two clubs alive and would otherwise return having drawn nothing.
        // That is the same class of bug as the one this method was rewritten to fix, one level up.
        if (alive.size() == 2) {
            made += drawFinalAndThirdPlace(cup, sc, alive, seasonYear);
        } else if (alive.size() > 2) {
            log.warn("{}: {} club(s) survived the semi-finals, which is not a bracket; nothing drawn.",
                    cup.getName(), alive.size());
        }
        return new DrawResult(cup.getName(), 0, qualifiers.size(), groupFixtures.size(), made);
    }

    /**
     * The final, plus the third-place play-off between the clubs that lost their semi-finals.
     *
     * <p>The third place is not decoration. The owner's format says "round of 16 &rarr; quarter-final &rarr;
     * semi-final &rarr; <b>3rd place</b> &rarr; final", and a knockout that silently drops a round is a
     * different tournament from the one that was specified.
     *
     * <p>Both ties are drawn at once because both are decided by the same semi-final, so neither exists
     * without the other.
     */
    private int drawFinalAndThirdPlace(Competition cup, SeasonCompetition sc, List<Team> finalists,
                                       int seasonYear) {
        int made = 0;
        // Strongest-listed first, so the finalist seeded first hosts. Deterministic on purpose.
        List<Team> seeded = new ArrayList<>(finalists);
        Team home = seeded.get(0);
        Team away = seeded.get(1);

        fixtures.save(fixture(cup, sc, home, away, ROUND_FINAL, weekFor(ROUND_FINAL), null, seasonYear));
        made++;

        List<Team> semiLosers = losingSemiFinalists(cup, sc, seasonYear);
        if (semiLosers.size() == 2) {
            fixtures.save(fixture(cup, sc, semiLosers.get(0), semiLosers.get(1),
                    ROUND_THIRD_PLACE, weekFor(ROUND_THIRD_PLACE), null, seasonYear));
            made++;
            log.info("{}: final and third place drawn ({} and {}).", cup.getName(), home.getName(), away.getName());
        } else {
            // Better a third-place match that cannot be named than one invented from the wrong round.
            log.warn("{}: final drawn, but {} losing semi-finalist(s) found so no third-place tie was made.",
                    cup.getName(), semiLosers.size());
        }
        return made;
    }

    /** The two clubs that went out of the semi-final, which is who plays for third place. */
    private List<Team> losingSemiFinalists(Competition cup, SeasonCompetition sc, int seasonYear) {
        List<Team> losers = new ArrayList<>();
        for (MatchFixture tie : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), seasonYear)) {
            if (tie.getRoundNumber() == null || tie.getRoundNumber() != ROUND_SEMI_FINAL || !tie.isPlayed()) {
                continue;
            }
            Team winner = winnerOf(tie);
            if (winner == null) {
                continue;
            }
            losers.add(winner == tie.getHomeTeam() ? tie.getAwayTeam() : tie.getHomeTeam());
        }
        return losers;
    }

    /** One knockout round: the owner's split — rank, halve, shuffle each half, pair across. */
    private int drawOneKnockoutRound(Competition cup, SeasonCompetition sc, List<Team> ranked,
                                     int round, int seasonYear) {
        List<List<Team>> halves = splitInHalf(ranked);
        if (halves.get(0).isEmpty() || halves.get(1).isEmpty()) {
            // An odd field cannot be halved without dropping somebody. Said rather than done.
            log.warn("{}: round {} has {} club(s), which cannot be halved; nothing drawn.",
                    cup.getName(), round, ranked.size());
            return 0;
        }
        List<Team> upper = new ArrayList<>(halves.get(0));
        List<Team> lower = new ArrayList<>(halves.get(1));
        java.util.Collections.shuffle(upper, new Random(cup.getId() * 31L + round));
        java.util.Collections.shuffle(lower, new Random(cup.getId() * 31L + round + 1));

        int made = 0;
        for (int i = 0; i < Math.min(upper.size(), lower.size()); i++) {
            // The non-favourite hosts, which is the owner's rule and the reason the split exists.
            fixtures.save(fixture(cup, sc, lower.get(i), upper.get(i), round, weekFor(round), null, seasonYear));
            made++;
        }
        return made;
    }

    /**
     * The winner of a played tie, or null when there is genuinely none.
     *
     * <p>Same rule as the national cup's: a level tie is decided by the shootout columns, and a tie that
     * is level <i>and</i> has no shootout recorded stops the bracket instead of being settled by a coin
     * toss. A cup that invents a winner is worse than a cup that is one club short and says so.
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
                log.warn("Cup tie {} finished level at {}-{} with no shootout recorded; no winner taken.",
                        tie.getId(), home, away);
                return null;
            }
            return homePens > awayPens ? tie.getHomeTeam() : tie.getAwayTeam();
        }
        return home > away ? tie.getHomeTeam() : tie.getAwayTeam();
    }

    /**
     * Who is through from each group, best first.
     *
     * <p>The group a club was in is read back off the fixtures it played, which is the same fact the
     * draw wrote and cannot disagree with it. A separate table of group membership would be a second
     * record of something the fixtures already say exactly.
     */
    private List<Team> qualifiers(Competition cup, SeasonCompetition sc, List<MatchFixture> groupFixtures,
                                  int qualifyPerGroup) {
        Map<String, Set<Team>> membersByGroup = new LinkedHashMap<>();
        for (MatchFixture fixture : groupFixtures) {
            if (fixture.getGroupCode() == null) {
                continue;
            }
            membersByGroup.computeIfAbsent(fixture.getGroupCode(), key -> new LinkedHashSet<>())
                    .add(fixture.getHomeTeam());
            membersByGroup.computeIfAbsent(fixture.getGroupCode(), key -> new LinkedHashSet<>())
                    .add(fixture.getAwayTeam());
        }

        List<Team> qualifiers = new ArrayList<>();
        for (Set<Team> members : membersByGroup.values()) {
            List<Team> ranked = new ArrayList<>(members);
            ranked.sort(rankingWithin(sc));
            qualifiers.addAll(ranked.subList(0, Math.min(qualifyPerGroup, ranked.size())));
        }
        // The knockout is seeded on the same ranking, strongest first.
        qualifiers.sort(rankingWithin(sc));
        return qualifiers;
    }

    /**
     * Orders clubs by their group-stage record, strongest first.
     *
     * <p>The one comparator in the codebase, {@link LeagueTableOrder}, orders <i>entries</i>. A group is
     * a subset of a competition's clubs, so a club is ordered by looking its own entry up rather than by
     * having a second ranking of its own - which is what a club with two records and one rule would
     * become.
     */
    private java.util.Comparator<Team> rankingWithin(SeasonCompetition sc) {
        java.util.Comparator<CompetitionEntry> byRecord = LeagueTableOrder.comparator();
        return (left, right) -> byRecord.compare(entryFor(sc, left), entryFor(sc, right));
    }

    private CompetitionEntry entryFor(SeasonCompetition sc, Team team) {
        return entries.findBySeasonCompetitionAndTeam(sc, team).orElseGet(() -> {
            CompetitionEntry empty = new CompetitionEntry();
            empty.setTeam(team);
            empty.setPoints(0);
            empty.setWins(0);
            empty.setDraws(0);
            empty.setLosses(0);
            empty.setGoalsScored(0);
            empty.setGoalsConceded(0);
            empty.setPosition(0);
            return empty;
        });
    }

    /** The owner's national-cup split, reused: sort descending, halve, and pair across. */
    public List<List<Team>> splitInHalf(List<Team> rankedDescending) {
        int half = rankedDescending.size() / 2;
        return List.of(
                new ArrayList<>(rankedDescending.subList(0, half)),
                new ArrayList<>(rankedDescending.subList(half, rankedDescending.size())));
    }

    /** The table rows a cup's group stage is read from. */
    private void ensureTableRows(Competition cup, SeasonCompetition sc, List<Team> entrants) {
        for (Team team : entrants) {
            if (entries.findBySeasonCompetitionAndTeam(sc, team).isEmpty()) {
                CompetitionEntry entry = new CompetitionEntry();
                entry.setSeasonCompetition(sc);
                entry.setTeam(team);
                entry.setPoints(0);
                entry.setWins(0);
                entry.setDraws(0);
                entry.setLosses(0);
                entry.setGoalsScored(0);
                entry.setGoalsConceded(0);
                entry.setPosition(0);
                entries.save(entry);
            }
        }
    }

    private SeasonCompetition ensureSeasonCompetition(Competition cup, int seasonYear) {
        return seasonCompetitions.findByCompetitionAndSeasonYear(cup, seasonYear)
                .orElseGet(() -> {
                    SeasonCompetition sc = new SeasonCompetition();
                    sc.setCompetition(cup);
                    sc.setSeasonYear(seasonYear);
                    sc.setFinished(false);
                    return seasonCompetitions.save(sc);
                });
    }

    private MatchFixture fixture(Competition cup, SeasonCompetition sc, Team home, Team away, int round,
                                 int week, String groupCode, int seasonYear) {
        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(cup);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setRoundNumber(round);
        fixture.setWeekNumber(week);
        fixture.setDayNumber(CUP_DAY);
        fixture.setSeasonYear(seasonYear);
        fixture.setGroupCode(groupCode);
        fixture.setPlayed(false);
        fixture.setMatchDate(CupFixtureSeeder.matchDateFor(week, seasonStart()));
        return fixture;
    }

    private int weekFor(int stage) {
        int index = Math.min(stage, CUP_WEEKS.length) - 1;
        return CUP_WEEKS[Math.max(0, index)];
    }

    static String groupCode(int index) {
        return "G" + (char) ('A' + index);
    }

    /** All three cups and how many of each qualify from a group. */
    public static int qualifyPerGroupFor(String cupName) {
        return switch (cupName) {
            case InternationalClubCups.MASTERS -> MASTERS_QUALIFY_PER_GROUP;
            case InternationalClubCups.CHAMPIONS -> CHAMPIONS_QUALIFY_PER_GROUP;
            case InternationalClubCups.CHALLENGE -> CHALLENGE_QUALIFY_PER_GROUP;
            default -> CHAMPIONS_QUALIFY_PER_GROUP;
        };
    }
}
