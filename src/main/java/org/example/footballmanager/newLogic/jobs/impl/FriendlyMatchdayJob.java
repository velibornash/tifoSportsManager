package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.AsyncSimulationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Plays the friendlies of a day (owner, 2026-10-06).
 *
 * <h2>Why this job exists at all</h2>
 *
 * <p>Every matchday in this framework selects its fixtures by <b>competition type</b>. A friendly belongs
 * to no competition, because it decides nothing — which is correct and is also why a friendly was
 * <b>unplayable</b>. It could be agreed by two managers, written to the database, shown on the club page
 * and in the dashboard ticker, and then never played by anything: no job could find it. This job selects
 * by what a fixture <i>is</i> rather than what competition it belongs to.
 *
 * <h2>Registered for the three days a friendly can fall on</h2>
 *
 * <p>Day 3 and day 7 are the league's two slots, and day 1 is where a national warm-up is played — week 6
 * day 1, the day before the first qualifier. Three separate beans, because the done-flag is keyed on
 * (season, week, day, key) and a shared key would let one day's job suppress another's.
 *
 * <p>The kickoff hour is the template's own for that day, so a friendly plays when its slot says it
 * plays. On a day with no template kickoff it falls back to the owner's 20:00.
 */
public class FriendlyMatchdayJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(FriendlyMatchdayJob.class);

    private final String key;
    private final int day;
    private final MatchFixtureRepository fixtures;
    private final AsyncSimulationRunner runner;

    public FriendlyMatchdayJob(String key, int day, MatchFixtureRepository fixtures,
                               AsyncSimulationRunner runner) {
        this.key = key;
        this.day = day;
        this.fixtures = fixtures;
        this.runner = runner;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public int week() {
        return ANY_WEEK;
    }

    @Override
    public int day() {
        return day;
    }

    @Override
    public int hour() {
        return NationalTournamentSchedule.kickoffHour(day);
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public void run(JobContext context) {
        List<Long> ids = fixtures.findUnplayedFriendliesOnDay(
                        context.seasonYear(), context.weekNumber(), day).stream()
                .map(f -> f.getId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            log.debug("Friendly matchday {}: week {} day {} has no friendly.",
                    key, context.weekNumber(), day);
            return;
        }
        // Asynchronous, like every other matchday: a job that blocked on the matches would hold the
        // transaction open for the length of a football match.
        runner.simulateInBackground(ids);
        log.info("Friendly matchday {}: {} friendly fixture(s) played for week {} day {}.",
                key, ids.size(), context.weekNumber(), day);
    }
}