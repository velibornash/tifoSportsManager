package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/game-clock")
    public ResponseEntity<Map<String, Object>> getGameClock() {
        GameClock clock = seasonService.getOrCreateClock();
        Map<String, Object> response = new HashMap<>();
        response.put("seasonNumber", clock.getCurrentSeason());
        response.put("weekNumber", clock.getCurrentWeek());
        response.put("phase", "Season in progress");
        return ResponseEntity.ok(response);
    }
}
