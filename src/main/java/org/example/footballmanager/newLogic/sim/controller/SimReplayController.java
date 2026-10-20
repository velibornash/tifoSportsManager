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
     *
     * <p><b>Side effects are off, and that is the point.</b> This match has already been played, so its
     * bans have been spent and its fatigue has been charged. Re-running the simulation with side effects
     * on would spend a second ban from every suspended player and add the whole match's fatigue again —
     * on an endpoint whose only job is to produce a recording. `applySideEffects = false` reproduces the
     * football and writes nothing; the only thing this method writes is the replay id below.
     */
    /**
     * The recording for a match, rebuilding it if the store no longer has it.
     *
     * <p><b>This is the endpoint the client should always ask for a match.</b> It answers "give me a
     * recording of this match" in one call, and it never answers with a recording of a <em>different</em>
     * match — which is what the client used to do by guessing.
     *
     * <p><b>Why it reuses before it rebuilds.</b> A recording already on disk is the right one and
     * rebuilding it costs ninety minutes of football for nothing. The store keeps a bounded number of
     * files and evicts the oldest, so after a matchday most recordings are gone and the rebuild is the
     * normal path — but the ones that are still there should not be thrown away.
     *
     * <p>Side effects are off. The match has already been played, so its bans have been spent and its
     * fatigue has been charged; re-running the simulation with side effects on would spend a second ban
     * from every suspended player and charge the whole match's fatigue twice.
     */
    @PostMapping("/{matchId}")
    public ResponseEntity<Map<String, Object>> recordingFor(@PathVariable long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) return ResponseEntity.notFound().build();

        // The recording is still on disk: hand it over, and do not spend a second simulation on it.
        if (match.getReplayId() != null && match.getReplayId() > 0 && replayStore.exists(match.getReplayId())) {
            return ResponseEntity.ok(Map.of(
                    "replayId", match.getReplayId(),
                    "rebuilt", false,
                    "viewer_url", viewerUrl(match.getReplayId())));
        }

        MatchFixture fixture = matchFixtureRepository.findByPlayedMatchId(matchId).orElse(null);
        if (fixture == null) return ResponseEntity.notFound().build();

        try {
            SimMatchService.SimMatchOutcome sim = simMatchService.simulate(fixture, true, false);
            long replayId = sim.replayId();
            if (replayId <= 0) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            match.setReplayId(replayId);
            matchRepository.save(match);
            return ResponseEntity.ok(Map.of(
                    "replayId", replayId,
                    "rebuilt", true,
                    "viewer_url", viewerUrl(replayId)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * The viewer URL for a recording.
     *
     * <p><b>{@code replayId}, never {@code matchId}.</b> The two id spaces overlap — match ids and
     * recording ids are both plain counters — and the viewer used to be handed one under the other's
     * name. It then resolved the number as a recording first and as a match second, so a recording that
     * had been evicted out of the store was answered with <em>another match's</em> recording, and the
     * manager watched the wrong game. That is the shape of the owner's report: <i>"watch your match ne
     * otvara dobar mec"</i> — it does not open the right match.
     *
     * <p>Two parameters, two meanings, and neither is ever guessed.
     */
    private static String viewerUrl(long replayId) {
        return "/demo/service/ui/proposal/index.html?replayId=" + replayId;
    }
}
