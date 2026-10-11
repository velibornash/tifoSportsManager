package org.example.footballmanager.newLogic.jobs;

/**
 * One scheduled unit of work (owner, 2026-09-28).
 *
 * <p>A job declares <b>when</b> it fires and a key that identifies it, and the runner decides
 * whether it is due. The job does not check the clock itself and does not decide whether it has
 * already run - that is the runner's job, and doing it in two places is how a job ends up running
 * twice or never.
 *
 * <h2>Triggers</h2>
 * <p>{@link #week()} and {@link #day()} accept {@link #ANY_WEEK} / {@link #ANY_DAY} for jobs that fire
 * every week or every day, so "finance" and "training" are one declaration rather than twelve.
 *
 * <p>Ordering inside a day is by {@link #order()}, not by declaration order in a list. Spring's
 * injection order of a {@code List<DayJob>} is not a contract, so relying on it would make the
 * sequence of financial effects depend on classpath ordering.
 */
public interface DayJob {

    /** Matches any week, for a job that runs in every week. */
    int ANY_WEEK = 0;

    /** Matches any day, for a job that runs in every day. */
    int ANY_DAY = 0;

    /**
     * Stable identifier, unique per job.
     *
     * <p>Part of the {@code JobRun} uniqueness key, so it must not change once a season is in
     * progress: changing it makes the job look outstanding again and it fires a second time.
     */
    String key();

    /** Week this fires in, or {@link #ANY_WEEK}. */
    int week();

    /** Game day 1..7 this fires on, or {@link #ANY_DAY}. */
    int day();

    /** Hour 0..23 the trigger becomes due. Jobs earlier in the day fire earlier. */
    int hour();

    /**
     * Runs before others on the same hour.
     *
     * <p>Several jobs read each other's output - finance settles before fixtures are created,
     * training happens before form is recalculated - and that dependency has to be explicit.
     */
    default int order() {
        return 100;
    }

    /**
     * Whether the job body runs inside the framework's transaction.
     *
     * <p>{@code JobRunner} wraps every job in one {@code REQUIRES_NEW} transaction, which is right for
     * a job that either happens or does not: a job that throws leaves no half-written state.
     *
     * <p>It is wrong for a job that is really a loop over independent units of work, and the matchday
     * jobs are that. A matchday is 155 matches; in one transaction it committed nothing for twenty
     * minutes and one bad fixture lost the whole evening. Committing each match separately is not
     * possible either - a per-fixture {@code REQUIRES_NEW} deadlocks against the job transaction it is
     * nested in, because the outer transaction already holds the {@code player_discipline} rows the
     * inner one wants to update. PostgreSQL cannot see its own locks from the outside.
     *
     * <p>So a job that opts out is one that manages its own boundaries. The prepare and publish jobs
     * each wrap a single fixture; nothing else opts out, and a new opt-out needs the same argument.
     */
    default boolean transactionalRun() {
        return true;
    }

    /** Whether the trigger hour is inclusive. A job at 00:00 fires when the day starts. */
    default boolean dueAtMidnight() {
        return true;
    }

    /** What the job does. Throwing is allowed: the runner records it and carries on. */
    void run(JobContext context);
}
