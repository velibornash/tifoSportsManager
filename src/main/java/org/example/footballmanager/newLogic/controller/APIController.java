package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.ResponseEntity;
import org.example.footballmanager.newLogic.service.GameClockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.Locale;
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
