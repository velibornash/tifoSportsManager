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
 *   <li><b>Week 1</b> — for each of the fifteen cups, read the clubs that qualified <i>off last
 *       season's finished tables</i> and draw the group stage. This is the only week that needs the
 *       qualification rule; after it the bracket is self-contained.</li>
 *   <li><b>Weeks 7-10</b> — walk one knockout round per week. The last sixteen cannot be drawn until the
 *       group stage is finished, so this is re-entered every week and stops wherever the results do not
 *       yet reach. Week 10 carries the final and the third-place play-off together.</li>
 * </ul>
 *
 * <h2>Why this is not on day 2 like the domestic cup</h2>
 *
 * <p>{@link CupDrawJob} draws the national cup three days before the tie, because clubs need time to scout
 * the opposition. A continental field is drawn once a season, its five matchdays are already spread
 * across weeks 1-5, and a manager can read a group off the group table — so there is nothing to scout in
 * week 1 that a calendar does not already say.
 *
 * <h2>Which season's tables</h2>
 *
 * <p>The <b>finished</b> one. A cup is entered on the strength of last season, so a club that wins its
 * division in week 12 cannot enter the same season's Champions Cup by winning it in week 12 — the entry
 * is decided by the table everyone has already played. {@link #qualifyingSeason(int)} clamps at 1, which
 * is what makes season 1 honest: there is no season 0, so nothing has finished, no club qualifies, and no
 * field is invented from a season that was never played.
 *
 * <h2>Hour 8, and order 30</h2>
 *
 * <p>Before the day-1 matchday job at 20:00, because this job <b>creates</b> the fixtures that job plays
 * — and before the 20:45 kickoff. The existing {@code league-table-reconcile-a} job uses hour 1 on day 4
 * for the same reason.
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
        // Any week: week 1 is the group stage and 7-10 are the knockouts. The job decides which by
        // looking at the week rather than being registered twice under one key — a shared key would let
        // whichever ran first mark the other done.
        return ANY_WEEK;
    }

    @Override
    public int day() {
        return InternationalClubCupDraw.CUP_DAY;
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
        cups.ensureCompetitions();

        if (week == 1) {
            // Simulated clubs are deliberately playerless until a competition needs them. Qualification
            // needs their static tables first, so finish that world step before reading the field. Both
            // seasons matter: entry comes from last season, while the active season owns the fixtures.
            simulatedWorldSeeder.seedAllSimulated(Math.max(1, season - 1));
            simulatedWorldSeeder.seedAllSimulated(season);
            drawEveryGroupStage(season);
        } else if (isKnockoutWeek(week)) {
            walkEveryBracket(season, week);
        } else {
            // Weeks 2-5 are group matchdays; weeks 6, 11 and 12 belong to national teams and the league
            // promotion play-off.
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
    private void drawEveryGroupStage(int season) {
        int qualifying = qualifyingSeason(season);

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
                        cup.fullName(), qualifying, season);
                continue;
            }
            InternationalClubCupDraw.DrawResult result = draw.ensureGroupStage(
                    competition, entrants, InternationalClubCupDraw.qualifyPerGroupFor(cup.name()), season);
            log.info("Club cups: {} — {} club(s) into {} group(s), {} group fixture(s).",
                    cup.fullName(), result.clubs(), result.groups(), result.groupFixtures());
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
    private int qualifyingSeason(int season) {
        return Math.max(1, season - 1);
    }

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
