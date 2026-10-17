package org.example.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.example.footballmanager.newLogic.sim.SimReplayStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sim/replay")
@RequiredArgsConstructor
public class SimReplayController {

    private final SimReplayStore replayStore;
    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final SimMatchService simMatchService;

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
        if (match == null || match.getReplayId() == null || match.getReplayId() <= 0) {
            return ResponseEntity.notFound().build();
        }
        return replayStore.find(match.getReplayId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    match.setReplayId(null);
                    matchRepository.save(match);
                    return ResponseEntity.status(HttpStatus.GONE).body(EXPIRED);
                });
    }

    /**
     * Re-simulates a played match so its replay can be shown.
     *
     * <p>Regular matchday matches are simulated with `storeReplay = false` for storage reasons, so they
     * have no replay. This endpoint re-runs the simulation with `storeReplay = true`, persists the new
     * replay id to the match row, and returns it. The result is the same match (deterministic seed from
     * the fixture id), so the replay matches the recorded score.
     */
    @PostMapping("/{matchId}")
    public ResponseEntity<Map<String, Object>> resimulate(@PathVariable long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) return ResponseEntity.notFound().build();

        MatchFixture fixture = matchFixtureRepository.findByPlayedMatchId(matchId).orElse(null);
        if (fixture == null) return ResponseEntity.notFound().build();

        try {
            SimMatchService.SimMatchOutcome sim = simMatchService.simulate(fixture, true);
            long replayId = sim.replayId();
            if (replayId <= 0) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            match.setReplayId(replayId);
            matchRepository.save(match);
            return ResponseEntity.ok(Map.of(
                    "replayId", replayId,
                    "viewer_url", "/demo/service/ui/proposal/index.html?matchId=" + replayId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
