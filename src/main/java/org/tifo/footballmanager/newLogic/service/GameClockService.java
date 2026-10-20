package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.jobs.JobRunner;
import org.tifo.footballmanager.newLogic.model.GameClock;
import org.tifo.footballmanager.newLogic.model.GameDay;
import org.tifo.footballmanager.newLogic.model.GamePosition;
import org.tifo.footballmanager.newLogic.model.GameTimeline;
import org.tifo.footballmanager.newLogic.repository.GameClockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The game clock (owner, 2026-09-28; rebuilt 2026-10-11).
 *
 * <h2>There is one clock</h2>
 *
 * <p>This service used to own four counters ({@code currentSeason}, {@code currentWeek},
 * {@code currentDay}, {@code currentHour}) and move them itself on every advance. It also owned an
 * offset, and rendered a time from it. Those were two clocks, they disagreed, and the disagreement was
 * silent — a header reading {@code 16:20} beside a job runner that believed it was 09:00 of day 4.
 *
 * <p>Now the clock is {@code now() + offset} and the position
 * ({@link GameTimeline#positionAt}) is a pure function of it. Moving the world means changing the
 * offset. Nothing else.
 *
 * <h2>Every movement ends in the same place</h2>
 *
 * <p>Advance hour, advance day, advance week, the admin offset control and the scheduled synchroniser
 * all do exactly one thing — add to the offset — and then call {@link #synchronise()}. There is no
 * second code path that moves the clock without running the jobs that hour made due, which is how the
 * old week-rollover could be skipped entirely.
 */
@Service
public class GameClockService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GameClockService.class);

    /**
     * The most game hours one synchronisation will walk.
     *
     * <p>Seven days. A month-long outage would otherwise walk a month of matchdays in one request, and a
     * matchday simulates 155 fixtures — catching up on a season of downtime is not a rescue, it is a
     * denial of service against the owner who pressed the button.
     */
    public static final int MAX_CATCH_UP_HOURS = 24 * 7;

    private final GameClockRepository clocks;
    private final SeasonService seasons;
    private final JobRunner jobRunner;
    private final Clock time;
    private final Executor walkExecutor;
    private volatile boolean syncing;

    /**
     * Marked explicitly because this class has two constructors and Spring refuses to guess between
     * them — the same trap {@code NationalTeamSeeder} records.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public GameClockService(GameClockRepository clocks, SeasonService seasons, JobRunner jobRunner) {
        this(clocks, seasons, jobRunner, Clock.systemUTC());
    }

    /**
     * One thread, one walk at a time.
     *
     * <p>Single on purpose. Two walks at once would both read {@code lastSyncedAt} and both run the
     * same training over the same 14,880 clubs; the {@code job_run} claim stops the body running twice,
     * but it does not stop the second walk walking 168 hours of positions that the first one is
     * already past.
     */
    private static Executor newWalkExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "game-clock-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The real one and the test one differ in exactly one thing: where "now" comes from.
     *
     * <p>A game clock whose only input is the wall clock cannot be asserted on. "Advance 168 hours and
     * land on the same hour of the day" is true for 363 nights a year and false on the two
     * daylight-saving ones, so the test would be right almost always and wrong for a reason no failure
     * message would explain.
     */
    GameClockService(GameClockRepository clocks, SeasonService seasons, JobRunner jobRunner, Clock time) {
        this(clocks, seasons, jobRunner, time, newWalkExecutor());
    }

    GameClockService(GameClockRepository clocks, SeasonService seasons, JobRunner jobRunner, Clock time,
                     Executor walkExecutor) {
        this.clocks = clocks;
        this.seasons = seasons;
        this.jobRunner = jobRunner;
        this.time = time;
        this.walkExecutor = walkExecutor;
    }

    private GameClock clock() {
        return seasons.getOrCreateClock();
    }

    /** Real time, as this service sees it. */
    private Instant instant() {
        return time.instant();
    }

    /** Game time: real time plus everything the owner has moved the clock by. */
    public Instant gameTime() {
        return clock().gameTimeAt(instant());
    }

    /** Where the game is. Derived, so it cannot be stale. */
    public GamePosition position() {
        return clock().positionAt(instant());
    }

    /**
     * The whole clock, as the header and the dashboard read it.
     *
     * <p><b>The time is sent in two forms on purpose.</b> {@code gameTimeEpochMillis} is the server's
     * own game time in epoch milliseconds, and the browser anchors on it rather than on the device
     * clock: the server this runs against is a machine in another country, and a laptop whose clock is
     * four minutes out would then show a game time four minutes out — which is the same class of bug as
     * the two clocks this replaced, wearing a different hat.
     */
    public Map<String, Object> snapshot() {
        GameClock clock = clock();
        Instant realNow = instant();
        GamePosition position = clock.positionAt(realNow);
        GameDay gameDay = GameDay.of(position.day());
        Instant gameTime = clock.gameTimeAt(realNow);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seasonNumber", position.season());
        out.put("weekNumber", position.week());
        out.put("day", position.day());
        out.put("dayLabel", gameDay.label());
        out.put("dayKind", gameDay.kind().name());
        out.put("matchDay", gameDay.isMatchDay());
        out.put("kickoffHour", gameDay.kickoffHour());
        out.put("kickoffMinute", gameDay.kickoffMinute());
        out.put("hour", position.hour());
        out.put("minute", gameTime.atZone(GameTimeline.GAME_ZONE).getMinute());
        out.put("second", gameTime.atZone(GameTimeline.GAME_ZONE).getSecond());
        out.put("gameTime", gameTime.toString());
        out.put("gameTimeEpochMillis", gameTime.toEpochMilli());
        out.put("serverEpochMillis", realNow.toEpochMilli());
        out.put("advanceOffsetSeconds", clock.offset());
        out.put("anchorDate", clock.getAnchorDate() == null ? null : clock.getAnchorDate().toLocalDate().toString());
        out.put("lastSyncedAt", clock.getLastSyncedAt());
        out.put("syncing", syncing);
        return out;
    }

    /**
     * Moves the world forward by a number of game hours.
     *
     * <p>One write and one walk. This used to call {@link #advanceHour()} in a loop, which wrote the
     * clock row 168 times to advance a week and — because it invoked itself rather than going through
     * the proxy — ran all 168 steps inside a single transaction.
     */
    public Map<String, Object> advanceHours(int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("Advance must be a positive number of hours.");
        }
        if (hours > MAX_CATCH_UP_HOURS) {
            throw new IllegalArgumentException(
                    "A single advance is at most " + MAX_CATCH_UP_HOURS + " hours, was " + hours + ".");
        }
        return moveClock(hours * 3600L);
    }

    /** One game day. */
    public Map<String, Object> advanceDay() {
        return advanceHours(24);
    }

    /** One game week — 168 hours, two league matchdays, one training day, one rollover. */
    public Map<String, Object> advanceWeek() {
        return advanceHours(24 * 7);
    }

    /**
     * Moves the offset and hands the walk to the synchroniser.
     *
     * <p><b>It does not wait for the jobs.</b> Measured against the owner's database on 2026-10-11:
     * one Advance Hour landed on day 4 hour 10, the training job became due, and the request was still
     * open after five minutes — because {@code trainEveryClub} walks 14,880 clubs in a single
     * transaction. The button spun, the browser timed out, and the world had moved with nothing on
     * screen saying so.
     *
     * <p>So the clock moves first and returns, the walk runs on the single synchronisation thread, and
     * {@code snapshot()} reports {@code syncing} so the browser knows to come back. This is the same
     * arrangement the matchday jobs already use: the fixture work is queued and the page refreshes
     * when it is done.
     *
     * <p>A rewind has nothing to wait for and nothing to catch up on, so it returns directly.
     *
     * <p><b>There is deliberately no {@code @Transactional} here, and adding one would be a
     * regression.</b> This method is {@code protected} and self-invoked, so Spring's proxy never sees
     * it and the annotation would do nothing at all — it would only make the next reader believe the
     * write could be rolled back. It cannot. {@code clocks.save(clock)} opens and commits its own
     * transaction (Spring Data's {@code SimpleJpaRepository} is transactional), and that is what has to
     * happen: the walk is dispatched to the synchronisation thread afterwards, and **the walk must see
     * the moved clock.** A transaction held open across the dispatch would let the background walk
     * read the old offset.
     *
     * <p>That is not a hypothetical. On 2026-10-19 a rewind the API refused — "400 Season counts from
     * 1, was 0" — had already moved the clock by exactly the number of seconds asked for, because the
     * write had committed and there was no transaction to undo it. The fix was to check the position
     * before the write; the annotation that made the method <em>look</em> transactional is what hid it.
     */
    protected Map<String, Object> moveClock(long seconds) {
        GameClock clock = clock();
        if (seconds < 0) {
            // **The new position is checked BEFORE the clock moves.**
            //
            // The offset used to be saved first and the position checked afterwards, and because
            // {@code moveClock} is self-invoked its {@code @Transactional} does nothing either — so a
            // rewind the API refused with "400 Season counts from 1, was 0" had already moved the clock.
            // Found on 2026-10-19: a refused rewind of -999,999 left the offset at -1,316,799 and the game
            // before the season it was in, and the API had answered that nothing happened.
            //
            // An error response that leaves the state changed is worse than no response at all: the caller
            // is told the move was refused and the world moved anyway.
            long candidate = clock.offset() + seconds;
            GameTimeline.positionAt(clock.anchor(), instant().plusSeconds(candidate));

            clock.setAdvanceOffsetSeconds(candidate);
            clocks.save(clock);
            Map<String, Object> result = snapshot();
            result.put("jobs", idleJobs());
            result.put("advance", summary(0, List.of(), List.of()));
            return result;
        }
        establishBaseline(clock);
        long candidate = clock.offset() + seconds;
        // The same check, in the other direction: a forward move from an offset that is already behind
        // the season can land before it again, and the clock must not move to a position it cannot be at.
        GameTimeline.positionAt(clock.anchor(), instant().plusSeconds(candidate));
        clock.setAdvanceOffsetSeconds(candidate);
        clocks.save(clock);
        return dispatchSynchronise();
    }

    /**
     * Hands the walk to the single synchronisation thread and answers immediately.
     *
     * <p>The response therefore describes a walk that may still be in progress, and says so through
     * {@code jobs.dispatched} and {@code syncing}. It never claims a job ran before it ran.
     */
    private Map<String, Object> dispatchSynchronise() {
        GameClock clock = clock();
        Instant atDispatch = clock.gameTimeAt(instant());
        long fromIndex = GameTimeline.hourIndex(
                GameTimeline.positionAt(clock.anchor(), clock.getLastSyncedAt()));
        long toIndex = GameTimeline.hourIndex(GameTimeline.positionAt(clock.anchor(), atDispatch));

        // **The flag is raised here, not in the background thread.**
        //
        // It used to be raised by `synchronise()`, which runs on the walk executor — so the response,
        // built immediately after the dispatch, could read it as false because the thread had not been
        // scheduled yet. The response then said "not syncing" about a walk it had just started, and the
        // browser that reads this flag has no way to tell the difference between "no walk is running" and
        // "the walk has not started yet".
        //
        // It is raised before the dispatch so that the answer is true whatever the scheduler does, and
        // the background `synchronise()` clears it when that walk finishes. A rejected dispatch leaves it
        // raised, which is right: the comment below says the run in progress will pick the work up.
        syncing = true;

        try {
            walkExecutor.execute(() -> {
                try {
                    synchronise();
                } catch (RuntimeException e) {
                    log.error("Job walk after a clock move failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // The queue is one deep and the thread is busy; the run in progress will pick this up,
            // because the walk is measured from lastSyncedAt and not from what queued it.
        }

        Map<String, Object> result = snapshot();
        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("dispatched", true);
        jobs.put("walkedHours", Math.max(0, Math.min(toIndex - fromIndex, MAX_CATCH_UP_HOURS)));
        jobs.put("ran", 0);
        jobs.put("skipped", 0);
        jobs.put("failed", 0);
        result.put("jobs", jobs);
        result.put("advance", summary(0, List.of(), List.of()));
        return result;
    }

    /**
     * Moves the offset by a number of seconds, in either direction (owner, 2026-10-11).
     *
     * <p>The owner's words: the clock is Belgrade time plus or minus an offset, and admin controls can
     * add to it or take from it. This is that control, and it is the only way to move the world
     * backwards.
     *
     * <p><b>Backwards does not un-run jobs.</b> The done-flags in {@code job_run} are not reversible —
     * rewinding to 15:00 and forward again finds the training already DONE. So a rewind moves the clock
     * and re-arms nothing; it is for looking at the world, not for replaying a week.
     */
    public Map<String, Object> shiftOffsetSeconds(long seconds) {
        return moveClock(seconds);
    }

    /**
     * Runs every job that came due since the last synchronisation, and reports what happened.
     *
     * <p>This is the owner's requirement in one sentence: <b>run everything due, or that did not run
     * last time.</b> It walks the game hours between {@code lastSyncedAt} and now, one hour at a time,
     * rather than looking only at where the clock is standing — so an app that was down over a kickoff
     * comes back and plays the matchday it missed instead of stepping over it.
     *
     * <p>Called by every movement of the clock and by the scheduled tick. There is one path, so "the
     * clock moved but the jobs did not run" is not a state this codebase can reach.
     */
    @Transactional
    public Map<String, Object> synchronise() {
        syncing = true;
        try {
            return walk();
        } finally {
            syncing = false;
        }
    }

    private Map<String, Object> walk() {
        GameClock clock = clock();
        Instant now = clock.gameTimeAt(instant());
        Instant from = clock.getLastSyncedAt() == null ? now : clock.getLastSyncedAt();

        // **A watermark ahead of game time kills every job, permanently and silently.**
        //
        // This walk is measured from `lastSyncedAt` to now. When the clock is wound back - which is a
        // supported admin operation, `POST /api/game-clock/offset` exists precisely to do it - the new
        // game time falls *behind* the watermark, `delta` goes negative, `steps` clamps to zero and not
        // one job runs. Nothing reports it: the walk returns an empty summary, `synchronise()` logs
        // nothing because it only logs when a job ran, and the offset sits unchanged while the entire
        // scheduled job system is dead.
        //
        // Measured on this world: `last_synced_at` 2026-10-27 12:14 against a game time of 08:24. A walk
        // over 71 hours afterwards ran nothing at all, which is how a league table, a cup draw, the
        // matchday and both Elo ledgers all went unmoved while the app looked perfectly healthy.
        //
        // So the watermark is repaired rather than trusted. It is a resume point, and a resume point
        // that lies about how far we got must not be believed. Resetting it to `now` alone (the previous
        // behaviour) costs at most one already-elapsed hour but leaves every missed job un-run: a
        // manager whose app was down over a kickoff comes back and finds the matchday still unplayed,
        // because `steps` is zero and nothing catches up. Resetting it to a week earlier lets the walk
        // replay the missed hours instead - the jobs are idempotent by key, so a second run of a DONE
        // job is a no-op rather than a duplicate.
        if (from.isAfter(now)) {
            log.warn("Clock watermark {} is ahead of game time {}; rewinding it one catch-up window so "
                    + "missed jobs can run again.", from, now);
            from = now.minusSeconds(MAX_CATCH_UP_HOURS * 3600L);
            clock.setLastSyncedAt(from);
        }

        long fromIndex = GameTimeline.hourIndex(GameTimeline.positionAt(clock.anchor(), from));
        long toIndex = GameTimeline.hourIndex(GameTimeline.positionAt(clock.anchor(), now));
        long delta = toIndex - fromIndex;

        int ran = 0;
        int skipped = 0;
        int failed = 0;
        List<String> ranKeys = new ArrayList<>();
        List<String> failedKeys = new ArrayList<>();
        List<Map<String, Object>> jobDetails = new ArrayList<>();

        // A rewind leaves delta negative. It is honoured on the clock and refused here: job runs are
        // recorded and not reversible, so "catching up" towards the past would re-run a week that has
        // already been settled.
        long steps = Math.max(0, Math.min(delta, MAX_CATCH_UP_HOURS));

        for (long step = 1; step <= steps; step++) {
            GamePosition position = GameTimeline.atHourIndex(fromIndex + step);
            Map<String, Object> result = jobRunner.runDue(position.season(), position.week(),
                    position.day(), position.hour());
            for (Object entry : asJobs(result)) {
                if (!(entry instanceof Map<?, ?> job)) {
                    continue;
                }
                String status = String.valueOf(job.get("status"));
                String key = String.valueOf(job.get("key"));
                String message = job.get("message") == null ? null : String.valueOf(job.get("message"));
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("key", key);
                detail.put("status", status);
                if (message != null) {
                    detail.put("message", message);
                }
                jobDetails.add(detail);
                switch (status) {
                    case "DONE" -> {
                        ran++;
                        if (!ranKeys.contains(key)) {
                            ranKeys.add(key);
                        }
                    }
                    case "FAILED" -> {
                        failed++;
                        if (!failedKeys.contains(key)) {
                            failedKeys.add(key);
                        }
                    }
                    default -> skipped++;
                }
            }
        }

        // **The watermark is the GAME time as of this sync**, for the reason
        // {@link #establishBaseline} gives: the walk measures game hours, so the resume point has to be
        // in that frame. A real instant here is a game position of the wrong hour, and the walk covers
        // the wrong number of hours — measured on 2026-10-19, a 36-hour outage walked as 45.
        //
        // It is written AFTER the walk, so it is the game time of the moment the jobs finished. The next
        // walk starts from there, and a game that has not moved offers no hours, which is right: those
        // hours' jobs have already run.
        clock.setLastSyncedAt(clock.gameTimeAt(instant()));
        clocks.save(clock);

        Map<String, Object> result = snapshot();
        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("season", position().season());
        jobs.put("week", position().week());
        jobs.put("day", position().day());
        jobs.put("hour", position().hour());
        jobs.put("jobs", List.of());
        jobs.put("ran", ran);
        jobs.put("skipped", skipped);
        jobs.put("failed", failed);
        jobs.put("walkedHours", (int) steps);
        if (delta > MAX_CATCH_UP_HOURS) {
            // Said out loud rather than silently truncated. A manager whose week never happened needs
            // to know it was the cap, not the game.
            jobs.put("truncated", true);
            jobs.put("missedHours", delta - MAX_CATCH_UP_HOURS);
        }
        result.put("jobs", jobs);
        Map<String, Object> adv = summary((int) steps, ranKeys, failedKeys);
        adv.put("jobDetails", jobDetails);
        result.put("advance", adv);
        return result;
    }

    /**
     * Straight to a kickoff hour, e.g. 20 for day 1 internationals.
     *
     * <p>Never moves backwards. The hour it names is today's; if it has passed, the request is a no-op
     * and the caller is told so by the returned position rather than by an exception.
     */
    @Transactional
    public Map<String, Object> advanceToHour(int targetHour) {
        if (targetHour < 0 || targetHour > 23) {
            throw new IllegalArgumentException("Hour must be between 0 and 23.");
        }
        GamePosition current = position();
        int hours = targetHour - current.hour();
        if (hours <= 0) {
            Map<String, Object> result = snapshot();
            result.put("jobs", idleJobs());
            result.put("advance", summary(0, List.of(), List.of()));
            return result;
        }
        return advanceHours(hours);
    }

    // --- helpers ---

    /** No jobs to report — a rewind, or a request for an hour that has already passed. */
    private Map<String, Object> idleJobs() {
        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("dispatched", false);
        jobs.put("jobs", List.of());
        jobs.put("ran", 0);
        jobs.put("skipped", 0);
        jobs.put("failed", 0);
        jobs.put("walkedHours", 0);
        return jobs;
    }

    /**
     * Marks where the world stands, if nothing has said so yet.
     *
     * <p><b>This is load-bearing and was a real bug.</b> The walk runs from {@code lastSyncedAt}. A world
     * that had never synchronised had none, so the walk started where it was already standing — and an
     * Advance Week walked <b>zero</b> hours, ran <b>no</b> jobs, and reported a week that had elapsed with
     * nothing in it. The clock moved; training, finance and both matchdays were silently skipped. Only
     * the half-hourly tick, arriving some time later with a baseline of its own, would have rescued it.
     *
     * <p>So a movement establishes the baseline <em>before</em> it moves. The hours that advance crosses
     * are then genuinely new hours, which is what they are.
     */
    private void establishBaseline(GameClock clock) {
        if (clock.getLastSyncedAt() == null) {
            // **Game time, and the distinction is the whole thing.**
            //
            // The watermark is a resume point in the frame the walk measures in, and the walk measures
            // GAME hours. So the watermark has to be the game time as of the last sync.
            //
            // It used to be the REAL instant, on the reasoning that "the walk is measured from real time
            // to game time (real time + offset)". But `positionAt(anchor, from)` reads the instant's
            // calendar position — it does not apply the offset — so a real instant in that slot is a
            // *game* position of the wrong hour, and the walk covered the wrong number of hours. Measured
            // on 2026-10-19: a 36-hour outage was walked as 45 hours, because the offset was nine hours.
            //
            // The 2026-10-17 fix was right about the symptom and wrong about the frame: storing the
            // game time made the two equal because it stored the game time *now* rather than the game
            // time *as of the last sync*. Storing the game time **as of the last sync** is not equal to
            // now — that is the point of a resume point — and it is what makes the delta the number of
            // game hours that actually passed.
            clock.setLastSyncedAt(clock.gameTimeAt(instant()));
        }
    }

    private List<?> asJobs(Map<String, Object> result) {
        Object jobs = result == null ? null : result.get("jobs");
        return jobs instanceof List<?> list ? list : List.of();
    }

    private Map<String, Object> summary(int hours, List<String> ranKeys, List<String> failedKeys) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hoursAdvanced", hours);
        summary.put("jobsRan", ranKeys);
        summary.put("jobsFailed", failedKeys);
        return summary;
    }

    /**
     * Runs what is due where the clock stands, without moving it.
     *
     * <p>Kept because a job that failed is re-queued and somebody has to be able to nudge it. It does
     * <b>not</b> move {@code lastSyncedAt}, because it has not caught up with anything — it looked.
     */
    @Transactional
    public Map<String, Object> runDueNow() {
        GamePosition current = position();
        Map<String, Object> result = jobRunner.runDue(current.season(), current.week(), current.day(),
                current.hour());
        return result;
    }
}
