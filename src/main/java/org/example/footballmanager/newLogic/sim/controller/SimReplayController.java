package org.example.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.sim.SimReplayStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sim/replay")
@RequiredArgsConstructor
public class SimReplayController {

    private final SimReplayStore replayStore;
    private final MatchRepository matchRepository;

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getRecording(@PathVariable long id) {
        Map<String, Object> view = replayStore.get(id);
        if (view == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(view);
    }

    @GetMapping("/by-match/{matchId}")
    public ResponseEntity<Map<String, Object>> getByMatch(@PathVariable long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null || match.getReplayId() == null) return ResponseEntity.notFound().build();
        Map<String, Object> view = replayStore.get(match.getReplayId());
        if (view == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(view);
    }
}