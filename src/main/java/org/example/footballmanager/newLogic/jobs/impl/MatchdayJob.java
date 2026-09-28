package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.AsyncSimulationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plays a matchday (owner, 2026-09-28).
 *
 * <p>One class, instantiated per competition type, rather than five near-identical jobs. The trigger
 * is all that differs between the day-1 internationals, the day-3 and day-7 league rounds and the
 * day-5 cup round; the work is the same, and five copies of it would drift apart.
 *
 * <p><b>It plays whatever is scheduled, it does not create fixtures.</b> Draw and fixture generation
 * is a separate concern and a separate job - a matchday that silently invented its own fixtures
 * would make a missing draw look like a quiet evening.
 *
 * <p>The day is part of the trigger, not an argument, because the job key has to be stable: the
 * done-flag is keyed on (season, week, day, key) and a key that moved would make the job fire twice.
 */
public class MatchdayJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(MatchdayJob.class);

    private final String key;
    private final CompetitionType competitionType;
    private final int day;
    private final int hour;
    private final int order;
    private final CompetitionRepository competitions;
    private final MatchFixtureRepository fixtures;
    private final AsyncSimulationRunner runner;

    public MatchdayJob(String key, CompetitionType competitionType, int day, int hour, int order,
                       CompetitionRepository competitions, MatchFixtureRepository fixtures,
                       AsyncSimulationRunner runner) {
        this.key = key;
        this.competitionType = competitionType;
        this.day = day;
        this.hour = hour;
        this.order = order;
        this.competitions = competitions;
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
        return hour;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public void run(JobContext context) {
        List<Competition> targets = competitions.findAll().stream()
                .filter(c -> c.getType() == competitionType)
                .toList();
        if (targets.isEmpty()) {
            log.debug("Matchday {}: no {} competition exists, nothing to play.", key, competitionType);
            return;
        }
        List<Long> ids = targets.stream()
                .flatMap(competition -> fixtures
                        .findUnplayedOnDay(context.seasonYear(), context.weekNumber(), day).stream()
                        .filter(fixture -> fixture.getCompetition() != null
                                && fixture.getCompetition().getId().equals(competition.getId()))
                        .map(MatchFixture::getId))
                .distinct()
                .toList();

        if (ids.isEmpty()) {
            log.debug("Matchday {}: week {} day {} has no {} fixtures.", key, context.weekNumber(), day, competitionType);
            return;
        }
        // The runner is asynchronous, so this job schedules the football and returns. It does not
        // wait: a job that blocked on 300 matches would hold the transaction open for the length of a
        // football match and the next hour's tick would pile up behind it.
        runner.simulateInBackground(ids);
        log.info("Matchday {}: {} {} fixtures played for week {} day {}.",
                key, ids.size(), competitionType, context.weekNumber(), day);
    }
}
