package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.controller.NewMatchController;
import org.example.footballmanager.newLogic.dto.MatchStatusResponse;
import org.example.footballmanager.newLogic.dto.ReplayChunkDTO;
import org.example.footballmanager.newLogic.dto.ReplayMetadataDTO;
import org.example.footballmanager.newLogic.dto.StartMatchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NewMatchControllerTest {

    @Test
    void fullMatchFlow() {
        NewMatchController controller = new NewMatchController(null);

        // 1. Start match
        StartMatchRequest req = new StartMatchRequest("Crvena Zvezda", "Partizan");
        MatchStatusResponse startResp = controller.startMatch(req).getBody();

        assertNotNull(startResp);
        assertTrue(startResp.matchId() >= 0);
        assertTrue(startResp.finished());
        assertEquals("FINISHED", startResp.status());
        assertEquals("Crvena Zvezda", startResp.homeTeamName());
        assertEquals("Partizan", startResp.awayTeamName());
        assertTrue(startResp.homeGoals() >= 0);
        assertTrue(startResp.awayGoals() >= 0);

        long matchId = startResp.matchId();

        // 2. Status
        MatchStatusResponse statusResp = controller.matchStatus(matchId).getBody();
        assertNotNull(statusResp);
        assertEquals(matchId, statusResp.matchId());
        assertTrue(statusResp.finished());

        // 3. Replay metadata
        ReplayMetadataDTO meta = controller.replayMetadata(matchId).getBody();
        assertNotNull(meta);
        assertTrue(meta.totalTicks() > 0);
        assertEquals(90, meta.totalMinutes());
        assertTrue(meta.totalTicks() >= 1000);
        assertEquals(startResp.homeGoals(), meta.homeGoals());
        assertEquals(startResp.awayGoals(), meta.awayGoals());
        assertNotNull(meta.eventSummaries());
        assertTrue(meta.eventSummaries().size() >= 10);

        // 4. Replay chunks
        List<ReplayChunkDTO> chunks = controller.replayChunks(matchId).getBody();
        assertNotNull(chunks);
        assertTrue(chunks.size() >= 5, "Should have at least 5 chunks, got " + chunks.size());

        // 5. Single chunk
        ReplayChunkDTO chunk0 = controller.replayChunk(matchId, 0).getBody();
        assertNotNull(chunk0);
        assertEquals(0, chunk0.chunkIndex());
        assertNotNull(chunk0.ticks());
        assertFalse(chunk0.ticks().isEmpty());

        // 6. Verify chunk boundary
        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
            var chunk = chunks.get(i);
            assertNotNull(chunk);
            assertEquals(i, chunk.chunkIndex());
            long tickCount = chunk.ticks().size();
            assertTrue(tickCount <= 125, "Chunk tick count: " + tickCount);
        }

        // 7. Unknown match returns 404
        var unknownResp = controller.matchStatus(99999L);
        assertTrue(unknownResp.getStatusCode().is4xxClientError());
    }
}
