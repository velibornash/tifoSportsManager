package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.util.InternationalClubCupDraw;
import org.example.footballmanager.newLogic.util.InternationalClubCups;
import org.example.footballmanager.newLogic.util.SimulatedWorldSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs the fifteen international club cups (owner, 2026-10-06) — P0-CUPS-4.
 *
 * <p><b>Until this existed, none of them played.</b> Fifteen competitions, a correct qualification rule
 * and 631 lines of group-stage and bracket code — and zero callers anywhere in {@code src/main}, so no
 * club had ever entered a group or played a tie. Every one of those cups was "created" and every one of
 * them was empty.
 *
 * <h2>What it does, in order of the season</h2>
 *
 * <ul>
 *   <li><b>Week 12, day 7</b> — the last day of the season, with the tables finished: for each of the
 *       fifteen cups, read the clubs that qualified <i>off this season's tables</i> and draw the group
 *       stage <b>into next season's</b> competition. This is the only week that needs the qualification
 *       rule; after it the bracket is self-contained. (Owner, 2026-10-08: the draw happens at the end of
 *       the season it qualifies from, because a promotion playoff can otherwise move a club into a
 *       division whose cup it has already been drawn into.)</li>
 *   <li><b>Weeks 7-10 of that next season</b> — walk one knockout round per week. The last sixteen cannot
 *       be drawn until the group stage is finished, so this is re-entered every week and stops wherever
 *       the results do not yet reach. Week 10 carries the final and the third-place play-off together.</li>
 * </ul>
 *
 * <h2>Why this is not on day 2 like the domestic cup</h2>
 *
 * <p>{@link CupDrawJob} draws the national cup three days before the tie, because clubs need time to scout
 * the opposition. A continental field is drawn once a season, its five matchdays are already spread
 * across weeks 1-5, and a manager can read a group off the group table — so there is nothing to scout
 * that a calendar does not already say.
 *
 * <h2>Which season's tables</h2>
 *
 * <p>The one that has <b>just finished</b>, which is why this runs on the last day of it. A cup is entered
 * on the strength of a finished table, so the draw cannot honestly happen before the season ends — and a
 * club that wins its division in week 12 enters <i>next</i> season's cup by winning it, never the one it
 * is in the middle of. Season 1 is honest for the same reason it always was: its tables have nothing in
 * them yet, so no club qualifies and no field is invented from a season nobody played.
 *
 * <h2>Hour 8, and order 30</h2>
 *
 * <p>On week 12 day 7 the league's season is over, so the tables are settled before this runs; the hour
 * and order are kept from the old week-1 slot because nothing else competes for them. The existing
 * {@code league-table-reconcile-a} job uses hour 1 on day 4 for the same kind of reason.
 *
 * <p>Idempotent by construction: the draw skips a cup whose fixtures already exist, and the bracket stops
 * at the first round whose results are not in. Running this job twice in one week changes nothing.
 */
@Component
public class InternationalClubCupJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(InternationalClubCupJob.class);

    public static final String KEY = "club-cup-draw";

    /** 08:00 on day 1 — the earliest sensible hour, and well before the 20:45 kickoff. */
    private static final int DRAW_HOUR = 8;

    private final InternationalClubCups cups;
    private final SimulatedWorldSeeder simulatedWorldSeeder;
    private final InternationalClubCupDraw draw;
    private final CompetitionRepository competitions;

    public InternationalClubCupJob(InternationalClubCups cups,
                                   SimulatedWorldSeeder simulatedWorldSeeder,
                                   InternationalClubCupDraw draw,
                                   CompetitionRepository competitions) {
        this.cups = cups;
        this.simulatedWorldSeeder = simulatedWorldSeeder;
        this.draw = draw;
        this.competitions = competitions;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int week() {
        // Any week: week 12 draws, and weeks 7-10 of the following season are the knockouts. The job
        // decides which by looking at the week rather than being registered twice under one key — a
        // shared key would let whichever ran first mark the other done.
        return ANY_WEEK;
    }

    /**
     * Week 12, day 7 — the last day of the season (owner, 2026-10-08).
     *
     * <p><b>The draw happens at the end of the season it qualifies from.</b> Previously it ran on day 1
     * of week 1 and read <i>last</i> season's tables, which had two consequences the owner did not want:
     * a promotion playoff could still change who was in which division after the field had been drawn,
     * and a tier-2 champion who qualified could end up promoted to tier 1 — so he would be seen playing
     * "Champions Cup" in a division he no longer belongs to. Drawing the moment the tables are finished
     * removes the confusion rather than explaining it.
     */
    public static final int DRAW_WEEK = 12;
    public static final int DRAW_DAY = 7;

    @Override
    public int day() {
        return DRAW_DAY;
    }

    @Override
    public int hour() {
        return DRAW_HOUR;
    }

    @Override
    public int order() {
        // Ahead of the day-1 matchday job (order 40), because a matchday with no fixtures to select is a
        // wasted tick at 20:00.
        return 30;
    }

    @Override
    public void run(JobContext context) {
        int season = context.seasonYear();
        int week = context.weekNumber();

        // The rows are a durable world object, not a prerequisite that may be missing forever because
        // an older database was created before this competition family existed. The job is the first
        // live writer that needs them, so make that boundary idempotent and self-healing. This remains
        // explicit world work; boot still does not seed anything.
        cups.ensureCompetitionsDurably();

        if (week == DRAW_WEEK) {
            // Simulated clubs are deliberately playerless until a competition needs them, and qualification
            // reads finished tables, so the world's static half is completed before the field is read.
            //
            // One season, not two: the draw qualifies off **this** season's tables and creates **next**
            // season's competition, so this season is the one that has to exist. The old version asked for
            // both, and in season 1 those were the same call — the world's static half built twice per
            // tick, which is why a day advance on an unseeded world ran past twenty minutes.
            //
            // Each country is its own transaction now, so this is visible while it runs and a failure
            // costs one country rather than all forty-six.
            simulatedWorldSeeder.seedAllSimulated(season);
            drawEveryGroupStage(season + 1, season);
        } else if (isKnockoutWeek(week)) {
            walkEveryBracket(season, week);
        } else {
            // Weeks 1-5 of the cup season are group matchdays; weeks 6 and 11 belong to national teams
            // and the league promotion play-off.
            log.debug("Club cups: week {} is neither the group draw nor a knockout round.", week);
        }
    }

    /**
     * Week 1: qualify every cup off the finished season and draw its groups.
     *
     * <p>Fifteen cups, and each one's entrants come from its own tier's divisions — tier 1's Champions
     * Cup never meets tier 5's. A cup already drawn is skipped by the draw itself, so this is safe to
     * re-run.
     */
    /**
     * Draws every cup's group stage for {@code targetSeason} off the finished tables of
     * {@code qualifyingSeason}.
     *
     * <p>Two seasons as arguments rather than one derived inside, because they are genuinely different
     * numbers now and the old helper — which reached back a season — is what put the draw in week 1.
     */
    private void drawEveryGroupStage(int targetSeason, int qualifyingSeason) {
        int qualifying = qualifyingSeason;

        for (InternationalClubCups.Cup cup : InternationalClubCups.cups()) {
            Competition competition = theCompetition(cup);
            if (competition == null) {
                log.warn("Club cups: {} has no competition row, so it will not be drawn this season.",
                        cup.fullName());
                continue;
            }
            List<Team> entrants = cups.qualifiedFor(cup, qualifying);
            if (entrants.isEmpty()) {
                log.info("Club cups: {} has no qualified clubs. The entry rule reads season {}'s finished "
                                + "tables, and season {} has none.",
                        cup.fullName(), qualifying, qualifying);
                continue;
            }
            InternationalClubCupDraw.DrawResult result = draw.ensureGroupStage(
                    competition, entrants, InternationalClubCupDraw.qualifyPerGroupFor(cup.name()), targetSeason);
            log.info("Club cups: {} — {} club(s) from season {} into season {}: {} group(s), "
                            + "{} group fixture(s).",
                    cup.fullName(), entrants.size(), qualifying, targetSeason,
                    result.clubs(), result.groups(), result.groupFixtures());
        }
    }

    /**
     * Weeks 7-10: one knockout round per cup, as far as the results reach.
     *
     * <p>The draw stops by itself, at the first round whose entrants are not yet knowable, so calling this
     * every week is what advances the tournament rather than what re-draws it.
     */
    private void walkEveryBracket(int season, int week) {
        for (InternationalClubCups.Cup cup : InternationalClubCups.cups()) {
            Competition competition = theCompetition(cup);
            if (competition == null) {
                continue;
            }
            InternationalClubCupDraw.DrawResult result = draw.ensureKnockouts(
                    competition, InternationalClubCupDraw.qualifyPerGroupFor(cup.name()), season);
            if (result.knockoutFixtures() > 0) {
                log.info("Club cups: {} — {} knockout tie(s) drawn in week {}.",
                        cup.fullName(), result.knockoutFixtures(), week);
            }
        }
    }

    /**
     * Weeks 7, 8, 9 and 10 — the four knockout weeks.
     *
     * <p>The first {@link InternationalClubCupDraw#GROUP_MATCHDAYS} entries of {@code CUP_WEEKS} are the
     * group matchdays and everything after them is a knockout round. Stated by position rather than
     * written out, so changing the week map cannot leave this disagreeing with it.
     */
    private boolean isKnockoutWeek(int week) {
        int[] weeks = InternationalClubCupDraw.CUP_WEEKS;
        for (int index = InternationalClubCupDraw.GROUP_MATCHDAYS; index < weeks.length; index++) {
            if (weeks[index] == week) {
                return true;
            }
        }
        return false;
    }

    /** The season whose finished tables decide this season's entry. Never below 1. */
    /**
     * The competition row for a cup, by tier and name.
     *
     * <p><b>Scoped and tiered on purpose.</b> There are sixteen {@code CUP} competitions in the world — one
     * domestic cup and fifteen continental ones — so asking for "a CUP" answers with whichever row came
     * first. {@code Competition.scope = INTERNATIONAL} is the only column that says the entrants come from
     * several countries, and P0-CUPS-6 is exactly what happens when a lookup forgets it.
     *
     * <p>Goes through {@code findByTypeAndTier}, which is indexed by {@code ix_competition_type_tier},
     * rather than reading the whole table fifteen times — which is what the competition table is, at
     * roughly 1,457 rows of league division.
     */
    private Competition theCompetition(InternationalClubCups.Cup cup) {
        for (Competition candidate : competitions.findByTypeAndTier(CompetitionType.CUP, cup.tier())) {
            if (candidate.getScope() == CompetitionScope.INTERNATIONAL
                    && cup.fullName().equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }
}
