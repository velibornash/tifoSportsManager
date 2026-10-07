package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.model.JobRun;
import org.example.footballmanager.newLogic.repository.JobRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every job is, when it last ran, and when it runs next (owner, 2026-10-07).
 *
 * <p><b>Why this exists.</b> The owner asked "does training work?" after a day passed on Oracle and
 * nothing appeared to happen. Training does work — it was recorded DONE. But there was nowhere to see
 * that: no panel, no history, no next trigger, so <b>a job that ran and a job that did not looked
 * identical</b>. The data was already there (`job_run` has the key, the day, the hour and the status);
 * it had no reader.
 *
 * <p><b>A job's day and hour are not enough to know whether it fires.</b> Whether a trigger is reached
 * depends on which hours the clock actually offers, which is why {@code nextTrigger} is computed by
 * walking the clock rather than by comparing two numbers. {@code JobTriggerCoverageTest} covers that
 * separately.
 */
@Service
public class JobStatusService {

    private static final int DAYS_PER_WEEK = 7;
    private static final int HOURS_PER_DAY = 24;
    /** How far ahead the "next trigger" is allowed to look. A week is the longest schedule here. */
    private static final int LOOKAHEAD_HOURS = DAYS_PER_WEEK * HOURS_PER_DAY + 1;

    private final List<DayJob> jobs;
    private final JobRunRepository runs;
    private final GameClockService clock;

    public JobStatusService(List<DayJob> jobs, JobRunRepository runs, GameClockService clock) {
        this.jobs = jobs.stream()
                .sorted(Comparator.comparingInt(DayJob::order).thenComparing(DayJob::key))
                .toList();
        this.runs = runs;
        this.clock = clock;
    }

    /** One job as the panel shows it. */
    public record Status(
            String key,
            String trigger,
            String lastStatus,
            String lastRunAt,
            String lastMessage,
            String nextTrigger,
            int nextInHours,
            int runsThisSeason,
            int failuresThisSeason) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> report() {
        Map<String, Object> snapshot = clock.snapshot();
        int season = numberOf(snapshot.get("seasonNumber"), 1);
        int week = numberOf(snapshot.get("weekNumber"), 1);
        int day = numberOf(snapshot.get("day"), 1);
        int hour = numberOf(snapshot.get("hour"), 0);

        List<JobRun> history = runs.findAll();
        List<Status> rows = new ArrayList<>();
        for (DayJob job : jobs) {
            rows.add(statusFor(job, history, season, week, day, hour));
        }

        long failedEver = history.stream()
                .filter(row -> row.getStatus() == JobRun.Status.FAILED)
                .count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("season", season);
        out.put("week", week);
        out.put("day", day);
        out.put("hour", hour);
        out.put("dayLabel", snapshot.get("dayLabel"));
        out.put("jobs", rows);
        out.put("failing", failedEver > 0);
        out.put("failureCount", failedEver);
        return out;
    }

    private Status statusFor(DayJob job, List<JobRun> history, int season, int week, int day, int hour) {
        JobRun last = history.stream()
                .filter(row -> job.key().equals(row.getJobKey()))
                .max(Comparator.comparing(JobRun::getRanAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);

        List<JobRun> mine = history.stream()
                .filter(row -> job.key().equals(row.getJobKey()))
                .filter(row -> season == row.getSeasonYear())
                .toList();

        Next next = nextTrigger(job, week, day, hour);

        return new Status(
                job.key(),
                describe(job),
                last == null || last.getStatus() == null ? null : last.getStatus().name(),
                last == null || last.getRanAt() == null ? null : last.getRanAt().toString(),
                last == null ? null : last.getMessage(),
                next.when(),
                next.inHours(),
                mine.size(),
                (int) mine.stream().filter(row -> row.getStatus() == JobRun.Status.FAILED).count());
    }

    /**
     * When this job next fires, and how far off that is.
     *
     * <p>Walked forward rather than compared. A job at day 4 hour 10 is due for the whole of hour 10
     * onwards, so "next" is the next hour at which {@code day} matches and {@code hour >= trigger} —
     * which is exactly the runner's own rule, asked one hour at a time.
     *
     * <p>{@code ANY_DAY} and {@code ANY_WEEK} are matched the same way the runner matches them, because a
     * job scheduled for "day 4 of week 6" has no next trigger in week 1 and saying "never" would be a
     * lie — it reports the hour count instead, which is honest about how far away it is.
     */
    private Next nextTrigger(DayJob job, int currentWeek, int day, int hour) {
        for (int ahead = 1; ahead <= LOOKAHEAD_HOURS; ahead++) {
            int position = hour + ahead;
            int d = day + position / HOURS_PER_DAY;
            int h = position % HOURS_PER_DAY;
            int w = currentWeek;
            while (d > DAYS_PER_WEEK) {
                d -= DAYS_PER_WEEK;
                w++;
                if (w > 12) {
                    w = 1;
                }
            }
            boolean weekOk = job.week() == DayJob.ANY_WEEK || job.week() == w;
            boolean dayOk = job.day() == DayJob.ANY_DAY || job.day() == d;
            if (weekOk && dayOk && h >= job.hour()) {
                return new Next(formatNext(w, d, h, currentWeek), ahead);
            }
        }
        return new Next("beyond a week", -1);
    }

    /** When a job next fires: the sentence for the screen, and how many game hours away it is. */
    private record Next(String when, int inHours) {
    }

    private String formatNext(int week, int day, int hour, int currentWeek) {
        String when = "week " + week + ", day " + day + " at " + pad(hour) + ":00";
        if (week < currentWeek) {
            // The walk crossed a season boundary. Said plainly, because "week 3" with no season on it
            // invites the reader to look for week 3 of the season they are in.
            return when + " (next season)";
        }
        return when;
    }

    /** The trigger as a sentence, because a job's real rule is two fields and one exception. */
    private String describe(DayJob job) {
        String day = job.day() == DayJob.ANY_DAY ? "every day" : "day " + job.day();
        String week = job.week() == DayJob.ANY_WEEK ? "every week" : "week " + job.week() + " only";
        return day + ", " + week + ", from " + pad(job.hour()) + ":00 (order " + job.order() + ")";
    }

    private String pad(int hour) {
        return String.format("%02d", hour);
    }

    private int numberOf(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}