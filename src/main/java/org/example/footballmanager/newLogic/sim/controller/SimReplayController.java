package org.example.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.sim.SimReplayStore;
import org.springframework.http.HttpStatus;
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

    /**
     * Body returned when a match has a replayId but the recording is gone.
     *
     * <p>Previously this was a bare 404, identical to a match that never had a replay, so the
     * viewer could only show a generic failure. A restart used to expire every replay in the
     * database at once, which turned "your replay expired" into "the replay feature is broken".
     */
    private static final Map<String, Object> EXPIRED =
            Map.of("error", "replay_expired",
                   "message", "This replay is no longer available. Simulated replays are kept for a "
                           + "limited time; the match result and statistics are unaffected.");

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getRecording(@PathVariable long id) {
        return replayStore.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).body(EXPIRED));
    }

    @GetMapping("/by-match/{matchId}")
    public ResponseEntity<Map<String, Object>> getByMatch(@PathVariable long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null || match.getReplayId() == null) {
            return ResponseEntity.notFound().build();
        }
        return replayStore.find(match.getReplayId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // Clear the dangling id so the database stops advertising a replay that cannot
                    // be served, and so this is a one-way transition rather than a repeated 410.
                    match.setReplayId(null);
                    matchRepository.save(match);
                    return ResponseEntity.status(HttpStatus.GONE).body(EXPIRED);
                });
    }
}