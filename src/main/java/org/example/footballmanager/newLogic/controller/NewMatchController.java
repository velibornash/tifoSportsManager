package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.MatchStatusResponse;
import org.example.footballmanager.newLogic.dto.ReplayChunkDTO;
import org.example.footballmanager.newLogic.dto.ReplayMetadataDTO;
import org.example.footballmanager.newLogic.dto.StartMatchRequest;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.TacticRules;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.service.ReplayBuilder;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.example.footballmanager.newLogic.service.NewLogicTacticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/match")
public class NewMatchController {

    private final MatchStore store;
    private final MatchOrchestrator orchestrator;
    private final ReplayBuilder replayBuilder;
    private final NewLogicTacticsService tacticsService;

    @Autowired
    public NewMatchController(NewLogicTacticsService tacticsService, MatchStore store) {
        this.store = store;
        this.orchestrator = new MatchOrchestrator(store);
        this.replayBuilder = new ReplayBuilder();
        this.tacticsService = tacticsService;
    }

    @PostMapping("/start")
    public ResponseEntity<MatchStatusResponse> startMatch(@RequestBody StartMatchRequest request) {
        String home = request.homeTeamName() != null ? request.homeTeamName() : "Home CTeam";
        String away = request.awayTeamName() != null ? request.awayTeamName() : "Away CTeam";
        String formation = request.formation() != null ? request.formation() : "4-4-2";

        TacticRules homeTactics = null;
        TacticRules awayTactics = null;
        List<String> homeSlots = null;
        List<String> awaySlots = null;

        if (request.homeTeamId() != null) {
            homeTactics = tacticsService.loadTacticRules(request.homeTeamId(), formation);
            homeSlots = tacticsService.loadSlotKeys(formation);
        }
        if (request.awayTeamId() != null) {
            awayTactics = tacticsService.loadTacticRules(request.awayTeamId(), formation);
            awaySlots = tacticsService.loadSlotKeys(formation);
        }

        // If only one team has custom tactics, generate defaults for the other
        if (homeTactics == null && awayTactics != null) {
            homeSlots = awaySlots;
        }
        if (awayTactics == null && homeTactics != null) {
            awaySlots = homeSlots;
        }

        long matchId = orchestrator.startMatch(home, away, homeTactics, homeSlots, awayTactics, awaySlots);
        MatchResult result = orchestrator.simulate(matchId);
        Match match = orchestrator.getMatch(matchId);

        MatchStatusResponse response = new MatchStatusResponse(
            matchId, "FINISHED",
            match.homeTeam().name(), match.awayTeam().name(),
            result.homeGoals(), result.awayGoals(), true
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{matchId}/status")
    public ResponseEntity<MatchStatusResponse> matchStatus(@PathVariable long matchId) {
        Match match = store.getMatch(matchId);
        if (match == null) return ResponseEntity.notFound().build();

        MatchResult result = store.getResult(matchId);
        boolean finished = result != null;

        return ResponseEntity.ok(new MatchStatusResponse(
            matchId, finished ? "FINISHED" : "SCHEDULED",
            match.homeTeam().name(), match.awayTeam().name(),
            finished ? result.homeGoals() : 0,
            finished ? result.awayGoals() : 0,
            finished
        ));
    }

    @GetMapping("/{matchId}/replay/metadata")
    public ResponseEntity<ReplayMetadataDTO> replayMetadata(@PathVariable long matchId) {
        Match match = store.getMatch(matchId);
        MatchResult result = store.getResult(matchId);
        if (match == null || result == null) return ResponseEntity.notFound().build();

        ReplayMetadataDTO metadata = replayBuilder.buildMetadata(matchId, match, result);
        return ResponseEntity.ok(metadata);
    }

    @GetMapping("/{matchId}/replay/chunks")
    public ResponseEntity<List<ReplayChunkDTO>> replayChunks(@PathVariable long matchId) {
        MatchResult result = store.getResult(matchId);
        if (result == null) return ResponseEntity.notFound().build();

        List<ReplayChunkDTO> chunks = replayBuilder.buildChunks(matchId, result);
        return ResponseEntity.ok(chunks);
    }

    @GetMapping("/{matchId}/replay/chunks/{chunkIndex}")
    public ResponseEntity<ReplayChunkDTO> replayChunk(
        @PathVariable long matchId,
        @PathVariable int chunkIndex
    ) {
        MatchResult result = store.getResult(matchId);
        if (result == null) return ResponseEntity.notFound().build();

        List<ReplayChunkDTO> chunks = replayBuilder.buildChunks(matchId, result);
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) return ResponseEntity.notFound().build();

        return ResponseEntity.ok(chunks.get(chunkIndex));
    }
}
