package org.example.footballmanager.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class APIController {

    @GetMapping("/server-time")
    public ResponseEntity<Map<String, String>> getServerTime() {
        LocalDateTime now = LocalDateTime.now();
        
        Map<String, String> response = new HashMap<>();
        response.put("iso", now.toString());                    // pun ISO format za JS parsiranje
        response.put("timestamp", String.valueOf(System.currentTimeMillis())); // milisekunde za precizan offset
        response.put("formatted", now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))); // opciono za debug

        return ResponseEntity.ok(response);
    }
}