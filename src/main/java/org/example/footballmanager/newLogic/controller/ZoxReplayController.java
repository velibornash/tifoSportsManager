package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.ReplayChunkDTO;
import org.example.footballmanager.newLogic.dto.ReplayMetadataDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.service.ReplayBuilder;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/zox/replay")
public class ZoxReplayController {

    private final MatchStore store;
    private final ReplayBuilder replayBuilder = new ReplayBuilder();

    public ZoxReplayController(MatchStore store) {
        this.store = store;
    }

    @GetMapping("/{matchId}/metadata")
    public ResponseEntity<ReplayMetadataDTO> replayMetadata(@PathVariable long matchId) {
        Match match = store.getMatch(matchId);
        MatchResult result = store.getResult(matchId);
        if (match == null || result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(replayBuilder.buildMetadata(matchId, match, result));
    }

    @GetMapping("/{matchId}/chunks")
    public ResponseEntity<List<ReplayChunkDTO>> replayChunks(@PathVariable long matchId) {
        MatchResult result = store.getResult(matchId);
        if (result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(replayBuilder.buildChunks(matchId, result));
    }

    @GetMapping("/{matchId}/chunks/{chunkIndex}")
    public ResponseEntity<ReplayChunkDTO> replayChunk(@PathVariable long matchId, @PathVariable int chunkIndex) {
        MatchResult result = store.getResult(matchId);
        if (result == null) return ResponseEntity.notFound().build();
        List<ReplayChunkDTO> chunks = replayBuilder.buildChunks(matchId, result);
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(chunks.get(chunkIndex));
    }
}
