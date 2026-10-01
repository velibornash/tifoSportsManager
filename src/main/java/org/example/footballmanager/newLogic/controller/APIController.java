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
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
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
    private final MatchFixtureRepository matchFixtureRepository;
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
                        asInt(snapshot.get("seasonNumber"), 1))),
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
        int seasonYear = season;
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

    /**
     * Whether the manager can watch a match right now (owner, 2026-09-29).
     *
     * <p>Replaces a whole-week boolean. "Watch" was disabled once the week was consumed, which meant
     * it was available at 08:00 on a match day - seven hours before kickoff - and unavailable for the
     * rest of the week even on a day with a fixture on it.
     *
     * <p>The owner's rule: when the clock reaches kickoff, Watch becomes active, and only clicking it
     * populates the stats, even though the match was already generated. So the gate is the kickoff
     * hour of the current day, and it is computed here rather than in the browser - a client that
     * decides for itself will disagree with the clock.
     *
     * <p>Both leagues and cups are considered. The owner's position was "watch your match should also
     * show cup ties, or a separate cup button" - this answers that by having one button that knows
     * about both, which is less to click and cannot drift out of sync with two of them.
     */
    /**
     * The manager's own match for the current day, and its statistics once played (owner, 2026-09-29).
     *
     * <p>The other half of the watch requirement: the gate says when watching is allowed, this is what
     * the click asks for. "Only clicking it populates the stats, even though the match was already
     * generated" - so the match is generated by the matchday job and this endpoint reads it, rather
     * than generating anything. Clicking twice is free.
     *
     * <p><b>Day-precise.</b> It looks for a fixture on the current game day, not the current week: a
     * week holds two league rounds and a cup round, so asking for "this week's match" would hand back
     * whichever came first in the table rather than the one being played today.
     *
     * <p>Leagues and cups together, which is the same reasoning as the gate - one answer, so a cup tie
     * and a league game are not two code paths that can disagree.
     */
    @GetMapping("/watch/my-match")
    public Map<String, Object> myMatch(
            @org.springframework.security.core.annotation.AuthenticationPrincipal User viewer) {
        Map<String, Object> clock = gameClockService.snapshot();
        int day = ((Number) clock.getOrDefault("day", 1)).intValue();
        int week = ((Number) clock.getOrDefault("weekNumber", 1)).intValue();
        int season = asInt(clock.get("seasonNumber"), 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day);
        out.put("week", week);

        if (viewer == null || viewer.getCTeam() == null) {
            out.put("found", false);
            out.put("reason", "No club is attached to this account.");
            return out;
        }
        Long teamId = viewer.getCTeam().getId();

        MatchFixture fixture = matchFixtureRepository
                .findBySeasonYearAndWeekNumberAndDayNumber(season, week, day)
                .stream()
                .filter(f -> (f.getHomeTeam() != null && teamId.equals(f.getHomeTeam().getId()))
                        || (f.getAwayTeam() != null && teamId.equals(f.getAwayTeam().getId())))
                .findFirst()
                .orElse(null);

        if (fixture == null) {
            out.put("found", false);
            out.put("reason", "Your club has no fixture on day " + day + ".");
            return out;
        }

        boolean isHome = fixture.getHomeTeam() != null && teamId.equals(fixture.getHomeTeam().getId());
        out.put("found", true);
        out.put("fixtureId", fixture.getId());
        out.put("isHome", isHome);
        out.put("played", fixture.isPlayed());
        out.put("competition", fixture.getCompetition() == null
                ? null : fixture.getCompetition().getName());
        out.put("competitionType", fixture.getCompetition() == null
                ? null : fixture.getCompetition().getType().name());
        out.put("opponent", (isHome ? fixture.getAwayTeam() : fixture.getHomeTeam()) == null
                ? null : (isHome ? fixture.getAwayTeam() : fixture.getHomeTeam()).getName());
        out.put("opponentId", (isHome ? fixture.getAwayTeam() : fixture.getHomeTeam()) == null
                ? null : (isHome ? fixture.getAwayTeam() : fixture.getHomeTeam()).getId());

        // Only populated once the matchday job has run it. Null is the honest answer before then.
        org.example.footballmanager.newLogic.model.Match match =
                fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch();
        out.put("matchId", match == null ? null : match.getId());
        out.put("homeGoals", match == null ? null : match.getHomeGoals());
        out.put("awayGoals", match == null ? null : match.getAwayGoals());
        out.put("possessionHome", match == null ? null : match.getPossessionHome());
        out.put("possessionAway", match == null ? null : match.getPossessionAway());
        return out;
    }

    @GetMapping("/watch/status")
    public Map<String, Object> watchStatus(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                           User viewer) {
        Map<String, Object> clock = gameClockService.snapshot();
        int day = ((Number) clock.getOrDefault("day", 1)).intValue();
        int hour = ((Number) clock.getOrDefault("hour", 0)).intValue();
        int week = ((Number) clock.getOrDefault("weekNumber", 1)).intValue();
        Object kickoff = clock.get("kickoffHour");
        boolean matchDay = Boolean.TRUE.equals(clock.get("matchDay"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(clock);

        if (!matchDay) {
            out.put("available", false);
            out.put("reason", "Not a match day. The next match day is day 3.");
            return out;
        }
        if (kickoff == null) {
            out.put("available", false);
            out.put("reason", "No kickoff is defined for this day.");
            return out;
        }
        int kickoffHour = ((Number) kickoff).intValue();
        // The international slot is 20:45, not 20:00. Truncating to the hour both misreported the time
        // and let the match unlock 45 minutes early.
        int kickoffMinute = ((Number) clock.getOrDefault("kickoffMinute", 0)).intValue();
        out.put("kickoffHour", kickoffHour);
        out.put("kickoffMinute", kickoffMinute);
        String kickoffAt = String.format("%02d:%02d", kickoffHour, kickoffMinute);

        // **The clock counts whole hours on purpose** - `hour` is an explicit counter, decoupled from the
        // wall clock so a job fires the same way whatever time the manager pressed the button. So the
        // gate cannot express 20:45, and the honest reading of "not yet" is the top of the hour *before*
        // the one containing kickoff. On the exact slots (19:00, 18:00, 16:00) this is plain
        // `hour >= kickoffHour`; only the 20:45 international moves.
        boolean kickedOff = kickoffMinute == 0 ? hour >= kickoffHour : hour > kickoffHour;
        boolean beforeKickoff = !kickedOff;

        if (beforeKickoff) {
            out.put("available", false);
            out.put("reason", "Kickoff is at " + kickoffAt
                    + ". It is now " + String.format("%02d:00", hour) + ".");
            return out;
        }

        out.put("available", true);
        out.put("reason", "Kickoff is " + kickoffAt + " - your match is ready to watch.");
        out.put("week", week);
        out.put("day", day);
        return out;
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
