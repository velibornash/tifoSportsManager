package org.example.footballmanager.zox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ZOX Match Analytics Controller
 * REST endpoints za match preview, player ratings, i predictions
 */
@Slf4j
@RestController
@RequestMapping("/api/zox")
@RequiredArgsConstructor
public class ZoxMatchAnalyticsController {

    private final ZoxMatchAnalyticsService zoxAnalyticsService;
    private final ZoxReplayService zoxReplayService;
    private final TeamRepository teamRepository;

    /**
     * Match preview sa detailed analytics
     */
    @GetMapping("/match-preview/{matchId}")
    public ResponseEntity<?> getMatchPreview(@PathVariable Long matchId) {
        try {
            log.info("Fetching ZOX match preview for match {}", matchId);
            ZoxMatchPreviewDTO preview = zoxAnalyticsService.generateMatchPreview(matchId);
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            log.error("Error fetching match preview for match {}", matchId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Player ratings za oba tima
     */
    @GetMapping("/player-ratings/{matchId}")
    public ResponseEntity<?> getPlayerRatings(@PathVariable Long matchId) {
        try {
            log.info("Fetching player ratings for match {}", matchId);

            return ResponseEntity.ok(zoxAnalyticsService.generatePlayerRatings(matchId));
        } catch (Exception e) {
            log.error("Error fetching player ratings for match {}", matchId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Match statistics (shots, possession, etc)
     */
    @GetMapping("/match-stats/{matchId}")
    public ResponseEntity<?> getMatchStats(@PathVariable Long matchId) {
        try {
            log.info("Fetching match stats for match {}", matchId);

            return ResponseEntity.ok(zoxAnalyticsService.generateMatchStats(matchId));
        } catch (Exception e) {
            log.error("Error fetching match stats for match {}", matchId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Match prediction pre meča
     */
    @GetMapping("/prediction/{matchId}")
    public ResponseEntity<?> getMatchPrediction(@PathVariable Long matchId) {
        try {
            log.info("Fetching match prediction for match {}", matchId);

            ZoxMatchPredictionDTO prediction = zoxAnalyticsService.generateMatchPrediction(matchId);
            return ResponseEntity.ok(prediction);
        } catch (Exception e) {
            log.error("Error fetching match prediction for match {}", matchId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Formation visualization za tim
     */
    @GetMapping("/formation/{matchId}/{teamId}")
    public ResponseEntity<?> getFormation(@PathVariable Long matchId, @PathVariable Long teamId) {
        try {
            log.info("Fetching formation for match {} team {}", matchId, teamId);

            Team team = teamRepository.findById(teamId)
                    .orElseThrow(() -> new RuntimeException("Team not found"));

            ZoxFormationDTO formation = zoxAnalyticsService.generateFormation(matchId, team);
            return ResponseEntity.ok(formation);
        } catch (Exception e) {
            log.error("Error fetching formation", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Event stream - chronological match events
     */
    @GetMapping("/event-stream/{matchId}")
    public ResponseEntity<?> getEventStream(@PathVariable Long matchId) {
        try {
            log.info("Fetching event stream for match {}", matchId);
            ZoxEventStreamDTO eventStream = zoxAnalyticsService.generateEventStream(matchId);
            return ResponseEntity.ok(eventStream);
        } catch (Exception e) {
            log.error("Error fetching event stream for match {}", matchId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/replay/{matchId}/metadata")
    public ResponseEntity<?> getReplayMetadata(@PathVariable Long matchId) {
        try {
            log.info("Fetching replay metadata for match {}", matchId);
            return ResponseEntity.ok(zoxReplayService.getPlaybackMetadata(matchId));
        } catch (Exception e) {
            log.error("Error fetching replay metadata for match {}", matchId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/replay/{matchId}/chunks/{chunkIndex}")
    public ResponseEntity<?> getReplayChunk(@PathVariable Long matchId, @PathVariable int chunkIndex) {
        try {
            log.info("Fetching replay chunk {} for match {}", chunkIndex, matchId);
            return ResponseEntity.ok(zoxReplayService.getPlaybackChunk(matchId, chunkIndex));
        } catch (IllegalArgumentException e) {
            log.warn("Replay chunk {} unavailable for match {}: {}", chunkIndex, matchId, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching replay chunk {} for match {}", chunkIndex, matchId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
