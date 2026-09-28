package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.ResponseEntity;
import org.example.footballmanager.newLogic.service.GameClockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.Locale;
import java.util.List;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class APIController {

    private final SeasonService seasonService;
    private final GameClockService gameClockService;
    private final org.example.footballmanager.newLogic.jobs.JobRunner jobRunner;
    private final org.example.footballmanager.newLogic.repository.JobRunRepository jobRunRepository;

    @GetMapping("/server-time")
    public ResponseEntity<Map<String, String>> getServerTime() {
        ZoneId zone = ZoneId.of("Europe/Belgrade");  // CET za Srbiju
        ZonedDateTime nowZoned = ZonedDateTime.now(zone);  // trenutno vreme u CET
        LocalDateTime now = nowZoned.toLocalDateTime();

        Map<String, String> response = new HashMap<>();
        response.put("iso", nowZoned.toString());  // ISO sa zonom, npr. "2026-02-21T20:02:00+01:00"
        response.put("timestamp", String.valueOf(nowZoned.toInstant().toEpochMilli()));  // UTC ms za offset
        response.put("formatted", now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));  // lokalni format

        return ResponseEntity.ok(response);
    }

    /**
     * Moves the game clock forward (owner, 2026-09-28).
     *
     * <p>Adds to the clock's accumulated offset rather than overwriting the time, so the clock keeps
     * ticking at one game second per real second and the advance is permanent. A stored hour that
     * the real clock immediately overwrites cannot work, which is what the first cut did.
     */
    @PostMapping("/game-clock/advance")
    public Map<String, Object> advanceGameClock(
            @RequestParam(defaultValue = "hour") String unit,
            @RequestParam(defaultValue = "1") int amount) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "hour", "hours" -> gameClockService.advanceHours(amount);
            case "day", "days" -> gameClockService.advanceHours(amount * 24);
            default -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
    }

    /** Straight to a kickoff hour, e.g. 20 for day 1 internationals. Never moves backwards. */
    @PostMapping("/game-clock/advance-to-hour")
    public Map<String, Object> advanceToHour(@RequestParam int hour) {
        return gameClockService.advanceToHour(hour);
    }

    /**
     * Runs any job whose trigger has been reached, without moving the clock (owner, 2026-09-28).
     *
     * <p>Advancing the clock already runs what is due. This exists for the case where a job failed
     * and has been re-queued, and for seeing the outcome of a scan without an advance. It is safe to
     * call repeatedly: a job with a DONE record is skipped.
     */
    @PostMapping("/jobs/run-due")
    public Map<String, Object> runDueJobs() {
        Map<String, Object> snapshot = gameClockService.snapshot();
        return jobRunner.runDue(
                Integer.parseInt(String.valueOf(
                        SeasonService.BASE_SEASON_YEAR + (asInt(snapshot.get("seasonNumber"), 1) - 1))),
                asInt(snapshot.get("weekNumber"), 1),
                asInt(snapshot.get("day"), 1),
                asInt(snapshot.get("hour"), 0));
    }

    private int asInt(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Every job and its trigger, so the schedule is inspectable rather than folklore. */
    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs() {
        return jobRunner.describeJobs();
    }

    /** Job history for a week: what ran, when, and what failed. */
    @GetMapping("/jobs/runs")
    public List<Map<String, Object>> jobRuns(
            @RequestParam(defaultValue = "1") int week,
            @RequestParam(defaultValue = "1") int season) {
        int seasonYear = SeasonService.BASE_SEASON_YEAR + (season - 1);
        return jobRunRepository.findBySeasonYearAndWeekNumberOrderByDayNumberAscRanAtHourAsc(seasonYear, week)
                .stream()
                .map(run -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", run.getJobKey());
                    row.put("day", run.getDayNumber());
                    row.put("hour", run.getRanAtHour());
                    row.put("status", run.getStatus().name());
                    row.put("ranAt", run.getRanAt());
                    row.put("message", run.getMessage());
                    return row;
                })
                .toList();
    }

    @GetMapping("/game-clock")
    public ResponseEntity<Map<String, Object>> getGameClock() {
        GameClock clock = seasonService.getOrCreateClock();
        Map<String, Object> response = new HashMap<>();
        // Delegated to GameClockService so game time has exactly one definition. The inline
        // fields this replaced disagreed with the service whenever an advance moved the hour.
        response.putAll(gameClockService.snapshot());
        response.put("phase", "Season in progress");
        return ResponseEntity.ok(response);
    }
}
