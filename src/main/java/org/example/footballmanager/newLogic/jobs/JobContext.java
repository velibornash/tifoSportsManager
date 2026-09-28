package org.example.footballmanager.newLogic.jobs;

/**
 * What a job is told when it runs (owner, 2026-09-28).
 *
 * <p>Deliberately a small value with the three facts a job needs and nothing else. A job that can
 * reach the whole application context will start reading things it was not designed against, and
 * then the trigger in {@link DayJob} stops describing what it actually does.
 *
 * @param seasonYear the season the job is running in
 * @param weekNumber the week within that season
 * @param dayNumber  the game day, 1..7
 * @param hour       the hour the trigger fired at
 */
public record JobContext(int seasonYear, int weekNumber, int dayNumber, int hour) {
}
