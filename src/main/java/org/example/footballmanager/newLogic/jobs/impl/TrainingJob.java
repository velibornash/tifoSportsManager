package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.SquadEnvironmentService;
import org.example.footballmanager.newLogic.service.SquadTrainingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Day 4: training (owner, 2026-09-28).
 *
 * <p>Before the day-5 cup round, so the players who played on day 3 have trained before they are
 * asked to play again. Training after a match round is how a cup side turns up exhausted.
 *
 * <p><b>Week-scoped service, day-scoped trigger.</b> {@code SquadTrainingService.trainEveryClub}
 * takes a (season, week) and does the week's training for every club. It is not re-implemented here
 * as a day version, because a second implementation would be a second source of truth for what
 * training does. The trigger is day-shaped; the work stays week-shaped.
 */
@Component
public class TrainingJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(TrainingJob.class);

    public static final String KEY = "training";

    private final SeasonService seasons;
    private final SquadTrainingService squadTraining;
    private final SquadEnvironmentService squadEnvironment;

    public TrainingJob(SeasonService seasons, SquadTrainingService squadTraining,
                       SquadEnvironmentService squadEnvironment) {
        this.seasons = seasons;
        this.squadTraining = squadTraining;
        this.squadEnvironment = squadEnvironment;
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
        return 4;
    }

    @Override
    public int hour() {
        return 10;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void run(JobContext context) {
        int trained = squadTraining.trainEveryClub(context.seasonYear(), context.weekNumber());
        int advanced = squadEnvironment.advanceWeek(context.seasonYear(), context.weekNumber());
        if (trained > 0 || advanced > 0) {
            log.info("Week {} season {}: training applied to {} players, environment advanced for {} clubs.",
                    context.weekNumber(), context.seasonYear(), trained, advanced);
        }
    }
}
