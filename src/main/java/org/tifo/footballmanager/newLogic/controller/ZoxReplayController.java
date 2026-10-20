package org.tifo.footballmanager.newLogic.controller;

import org.tifo.footballmanager.newLogic.dto.ReplayChunkDTO;
import org.tifo.footballmanager.newLogic.dto.ReplayMetadataDTO;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.MatchResult;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.service.ReplayBuilder;
import org.tifo.footballmanager.newLogic.store.MatchStore;
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
    private final MatchRepository matchRepository;
    private final org.tifo.footballmanager.newLogic.service.MatchPublicationGuard publication;
    private final ReplayBuilder replayBuilder = new ReplayBuilder();

    public ZoxReplayController(MatchStore store, MatchRepository matchRepository,
                               org.tifo.footballmanager.newLogic.service.MatchPublicationGuard publication) {
        this.store = store;
        this.matchRepository = matchRepository;
        this.publication = publication;
    }

    /**
     * Resolves a database match id to the in-memory store id, and refuses a prepared match.
     *
     * <p>The replay is the richest thing a prepared match leaks — every tick, every event — and it was
     * reachable: the store is populated at simulation time, an hour before kickoff. The owner asked for
     * the links to open at kickoff, and a replay endpoint is the link.
     *
     * @return the store id, or {@link Long#MIN_VALUE} when the match is prepared and unpublished, which
     *         no store will hold, so every caller below falls through to its existing 404
     */
    private long resolveStoreMatchId(long dbMatchId) {
        if (publication.isHidden(dbMatchId)) {
            return Long.MIN_VALUE;
        }
        return matchRepository.findById(dbMatchId)
                .map(Match::getReplayId)
                .orElse(dbMatchId);
    }

    @GetMapping("/{matchId}/metadata")
    public ResponseEntity<ReplayMetadataDTO> replayMetadata(@PathVariable long matchId) {
        long storeMatchId = resolveStoreMatchId(matchId);
        Match match = store.getMatch(storeMatchId);
        MatchResult result = store.getResult(storeMatchId);
        if (match == null || result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(replayBuilder.buildMetadata(storeMatchId, match, result));
    }

    @GetMapping("/{matchId}/chunks")
    public ResponseEntity<List<ReplayChunkDTO>> replayChunks(@PathVariable long matchId) {
        long storeMatchId = resolveStoreMatchId(matchId);
        MatchResult result = store.getResult(storeMatchId);
        if (result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(replayBuilder.buildChunks(storeMatchId, result));
    }

    @GetMapping("/{matchId}/chunks/{chunkIndex}")
    public ResponseEntity<ReplayChunkDTO> replayChunk(@PathVariable long matchId, @PathVariable int chunkIndex) {
        long storeMatchId = resolveStoreMatchId(matchId);
        MatchResult result = store.getResult(storeMatchId);
        if (result == null) return ResponseEntity.notFound().build();
        List<ReplayChunkDTO> chunks = replayBuilder.buildChunks(storeMatchId, result);
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(chunks.get(chunkIndex));
    }
}
