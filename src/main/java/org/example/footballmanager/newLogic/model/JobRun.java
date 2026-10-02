package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Proof that a job fired for one (season, week, day) (owner, 2026-09-28).
 *
 * <p>This row is the entire reason the framework is safe. Advancing an hour can cross several job
 * triggers, and the same day can be advanced through many times, so without a durable record each
 * job would run once per advance - wages applied twenty-four times, training applied twenty-four
 * times. A job is skipped when a DONE row already exists for its key.
 *
 * <p>The unique constraint is on (season, week, day, job_key) rather than on application logic, so
 * two concurrent runner invocations cannot both decide the job is outstanding. That is a real risk
 * once this runs as a scheduled job in production, where two instances can pick up the same tick.
 *
 * <p>FAILED is a real state, not a retry counter. A job that throws is recorded and left FAILED, and
 * the runner moves on: one broken job must not stop the jobs after it, or a single failure freezes
 * the season. It also must not be silently retried into a double-apply, so a FAILED row is not
 * re-run by the same scan - an operator re-queues it explicitly.
 */
@Entity
@Table(name = "job_run",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_job_run_season_week_day_key",
                columnNames = {"season_year", "week_number", "day_number", "job_key"}),
        indexes = @Index(name = "ix_job_run_season_week", columnList = "season_year,week_number"))
public class JobRun {

    public enum Status {
        /**
         * Claimed but not finished: a scan has taken this slot and the body is running, or the process died
         * before it could say otherwise.
         *
         * <p>Previously declared and <b>never written</b> - the audit's "a pattern used nowhere". It now
         * carries the claim that makes A1 safe: the row is inserted <i>before</i> the body runs, so two
         * concurrent scans collide on the unique constraint and only one gets to do the work.
         *
         * <p><b>A row left PENDING means the run died mid-flight.</b> Under the owner's rule (2026-10-02) —
         * "if it does not execute successfully the status is not DONE and the scheduler picks it up next
         * time" — a PENDING is simply not DONE, so the next scan re-claims and re-runs it. No timeout and no
         * manual re-queue is needed, and a crashed job cannot wedge itself permanently.
         */
        PENDING,
        /** Ran cleanly. Never runs again for this day. */
        DONE,
        /**
         * Threw. <b>Not terminal</b> — the next scan picks it up again, because a permanently missed
         * matchday is worse than a duplicate application (owner, 2026-10-02).
         */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_key", nullable = false, length = 64)
    private String jobKey;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "week_number", nullable = false)
    private Integer weekNumber;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    /** The hour the trigger fired at, which is not the day the job belongs to. */
    @Column(name = "ran_at_hour", nullable = false)
    private Integer ranAtHour;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "ran_at")
    private Instant ranAt;

    /** Why it failed, so a broken job is diagnosable without a debugger. */
    @Column(name = "message", length = 500)
    private String message;

    public Long getId() {
        return id;
    }

    public String getJobKey() {
        return jobKey;
    }

    public void setJobKey(String jobKey) {
        this.jobKey = jobKey;
    }

    public Integer getSeasonYear() {
        return seasonYear;
    }

    public void setSeasonYear(Integer seasonYear) {
        this.seasonYear = seasonYear;
    }

    public Integer getWeekNumber() {
        return weekNumber;
    }

    public void setWeekNumber(Integer weekNumber) {
        this.weekNumber = weekNumber;
    }

    public Integer getDayNumber() {
        return dayNumber;
    }

    public void setDayNumber(Integer dayNumber) {
        this.dayNumber = dayNumber;
    }

    public Integer getRanAtHour() {
        return ranAtHour;
    }

    public void setRanAtHour(Integer ranAtHour) {
        this.ranAtHour = ranAtHour;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getRanAt() {
        return ranAt;
    }

    public void setRanAt(Instant ranAt) {
        this.ranAt = ranAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
