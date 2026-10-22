package org.tifo.footballmanager.newLogic.jobs;

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
     * The minute of the hour the job fires at.
     *
     * <p><b>The hour alone is not enough, and the day-1 internationals are why.</b> The club week
     * gives day 1 a 20:45 kickoff, so the matchday has to be prepared at 19:45 and published at
     * 20:45 (owner, 2026-10-22: "ako je mec u 20:45, u 19:45 treba matchday-prepare-1"). A trigger
     * that only knows the hour fires at 19:00 and at 20:00 - forty-five minutes early for the lock,
     * and forty-five minutes before the whistle for the publish.
     *
     * <p>Zero for every job that does not care, which is all of them but the day-1 pair.
     */
    default int minute() {
        return 0;
    }

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

    /**
     * Whether the run that just finished should be recorded as DONE for this day.
     *
     * <p><b>Defaults to true — a job that ran, ran.</b> The override exists for one case, and the case is
     * a bug the owner hit on 2026-10-19: <i>"job ili nije opalio ili nije pokupio mec"</i>, an international
     * fixture that sat in the schedule and never played.
     *
     * <p><b>The defect.</b> The day-1 matchday ran at 12:00:00 and found nothing, because the Champions Cup
     * tie was not drawn yet. Sixty-five seconds later the cup draw wrote it into that same day. The matchday
     * job had already been recorded DONE, and DONE is terminal for a day — the owner's own rule is that a
     * job which does not execute successfully is not DONE, so the next scan picks it up. But this job
     * <em>had</em> executed successfully. It had simply executed against an empty day, and "the day was
     * empty" and "the day was played" were recorded identically.
     *
     * <p><b>So a fixture created after its matchday has run is never played.</b> Not just the cup draw:
     * the international club cup draw and the national tournament draw can all land on a day the clock has
     * already walked.
     *
     * <p><b>Why this method rather than never memoising a matchday job.</b> The day-scoped memo exists to
     * stop the scheduler re-issuing the same guard query 264 times a game day; removing it outright would
     * put that cost back. Only the empty case needs to reopen, and only the job knows whether it was empty.
     *
     * <p>Called immediately after {@link #run}, on the same thread, and only after the claim has been taken
     * — so a job reading its own last-run state here is not racing another body.
     */
    default boolean countsAsDone(JobContext context) {
        return true;
    }
}
