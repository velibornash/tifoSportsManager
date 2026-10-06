package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.AsyncSimulationRunner;

/**
 * A national-team matchday: {@link MatchdayJob} pinned to one week of the season (owner, 2026-10-06).
 *
 * <p><b>A subclass rather than a new job class.</b> {@code MatchdayJob} already knows how to play a
 * day of fixtures for one competition type, and its own comment says why it is one class instantiated
 * per trigger rather than five near-identical ones. Copying it would create the second copy of that
 * logic that class was written to prevent.
 *
 * <p><b>Why the week is pinned.</b> The base job is {@code ANY_WEEK}, which is right for the league
 * and the cup — they play in most weeks of a season. National football happens in exactly two: week 6
 * and week 12. Without this the job would fire on day 3 of every week in the season, find no
 * tournament fixtures, and be recorded in {@code job_run} as a successful national matchday that
 * played nothing, seventy-odd times a season.
 */
public class NationalMatchdayJob extends MatchdayJob {

    private final int week;

    public NationalMatchdayJob(String key, int week, int day, CompetitionRepository competitions,
                               MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        super(key, CompetitionType.TOURNAMENT, day,
                NationalTournamentSchedule.kickoffHour(day), 30, competitions, fixtures, runner);
        this.week = week;
    }

    @Override
    public int week() {
        return week;
    }
}