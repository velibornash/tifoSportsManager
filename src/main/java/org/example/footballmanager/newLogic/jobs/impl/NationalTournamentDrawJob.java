package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.util.NationalTournamentSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Draws the next national-team round, on the morning of the day it is played (owner, 2026-10-06).
 *
 * <p><b>Why this is a separate job rather than part of the matchday.</b> A matchday plays what it
 * finds; it never creates fixtures, so a missing draw shows up as a quiet evening rather than as a
 * failure. This job is the draw, and it runs <b>before</b> the matchday on the same day, which is why
 * its order is lower.
 *
 * <p><b>Why it re-enters once a round.</b> A knockout cannot be drawn in one pass: the quarter-finals
 * do not exist until the round of sixteen has been played. So this runs on every tournament day and
 * draws whatever the results so far allow, stopping where it must. It is idempotent, so the second
 * and later runs are no-ops.
 *
 * <p><b>The qualifying draw is earlier than the tournament.</b> Groups are drawn at the end of the
 * mid-season window, a week before the first qualifying matchday, so the field exists before it is
 * needed and the manager can see who his nation is grouped with.
 */
@Component
public class NationalTournamentDrawJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(NationalTournamentDrawJob.class);

    public static final String KEY = "national-tournament-draw";

    /** Week 6 day 1, before the first qualifying matchday on day 2. */
    public static final int GROUP_DRAW_DAY = 1;
    /** The job's scheduled hour; the job itself runs at midnight on every day. */
    public static final int GROUP_DRAW_HOUR = 0;

    private final NationalTournamentSeeder seeder;

    public NationalTournamentDrawJob(NationalTournamentSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int week() {
        return ANY_WEEK;
    }

    @Override
    public int day() {
        return ANY_DAY;
    }

    @Override
    public int hour() {
        // Midnight: the draw is done before the day's first kickoff whatever that day is, and this
        // job is a no-op on a day with no national football.
        return 0;
    }

    @Override
    public int order() {
        // Before every matchday, so a round exists by the time the job that plays it runs.
        return 20;
    }

    @Override
    public void run(JobContext context) {
        int seasonYear = context.seasonYear();
        int week = context.weekNumber();

        // **The qualifying draw is at the start of the season.** The owner: the group matches are played
        // in week 6, but the *draw* is at season start, so the fixtures and the groups are known from week
        // 1 and the manager can plan around them rather than learning his opponents the day before. It
        // used to fire on week 6 day 1, which is after the fact for anyone watching the calendar.
        if (week == 1 && context.dayNumber() == GROUP_DRAW_DAY) {
            for (NationalTeamLevel level : NationalTeamLevel.values()) {
                NationalTournamentSeeder.DrawResult result = seeder.ensureGroupStage(level, seasonYear);
                log.info("{} qualifying draw (season {}): {}", level, seasonYear, result.note());
            }
        }

        if (week == NationalTournamentSchedule.TOURNAMENT_WEEK) {
            for (NationalTeamLevel level : NationalTeamLevel.values()) {
                NationalTournamentSeeder.DrawResult result = seeder.ensureKnockouts(level, seasonYear);
                if (result.knockoutFixtures() > 0) {
                    log.info("{} tournament draw (season {}): {}", level, seasonYear, result.note());
                }
            }
        }
    }
}
