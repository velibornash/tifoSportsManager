package org.example.footballmanager.newLogic.sim.controller;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.example.footballmanager.newLogic.sim.SimReplayStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match is opened by its match id, and the recording that comes back is the right one.
 *
 * <p><b>What was broken (found by clicking the button, 2026-10-19).</b> The dashboard's "Watch your
 * match" handed the viewer a number taken from the match row — the id the recording had when it was
 * made — under the name {@code matchId}. The store keeps a bounded number of files and evicts the
 * oldest, and the match row is never told, so on a 480-fixture matchday most of those numbers pointed
 * at recordings that had been deleted. The viewer then resolved the number as a recording, found
 * nothing, and <b>resolved it as a match</b> — and opened that match's recording instead. The manager
 * watched somebody else's game. That is the owner's report, exactly: <i>"watch your match ne otvara dobar
 * mec"</i>.
 *
 * <p>The fix has two halves and this class covers the server's half: the endpoint that answers "give me
 * a recording of this match" now <b>reuses</b> a recording that is still there and <b>rebuilds</b> one
 * that has been evicted, and it never answers with a recording of a different match.
 */
class SimReplayControllerRecordingForTest {

    private static final long MATCH_ID = 157L;

    private SimReplayStore store;
    private MatchRepository matches;
    private MatchFixtureRepository fixtures;
    private SimMatchService sim;

    private SimReplayController controller() {
        store = Mockito.mock(SimReplayStore.class);
        matches = Mockito.mock(MatchRepository.class);
        fixtures = Mockito.mock(MatchFixtureRepository.class);
        sim = Mockito.mock(SimMatchService.class);
        return new SimReplayController(store, matches, fixtures, sim);
    }

    private Match aMatch(long replayId) {
        Match match = new Match();
        match.setId(MATCH_ID);
        match.setReplayId(replayId);
        return match;
    }

    @Test
    @DisplayName("a recording that is still on disk is handed over, and nothing is re-simulated")
    void anExistingRecordingIsReused() {
        SimReplayController controller = controller();
        Mockito.when(matches.findById(MATCH_ID)).thenReturn(Optional.of(aMatch(623L)));
        Mockito.when(store.exists(623L)).thenReturn(true);

        var response = controller.recordingFor(MATCH_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(623L, ((Map<?, ?>) response.getBody()).get("replayId"),
                "the recording that is already on disk is the one to hand over");
        assertEquals(false, ((Map<?, ?>) response.getBody()).get("rebuilt"),
                "nothing should have been re-simulated");
        Mockito.verifyNoInteractions(sim);
    }

    @Test
    @DisplayName("a recording the store has evicted is rebuilt")
    void anEvictedRecordingIsRebuilt() {
        SimReplayController controller = controller();
        Mockito.when(matches.findById(MATCH_ID)).thenReturn(Optional.of(aMatch(623L)));
        // The store evicted it — this is the ordinary case after a matchday.
        Mockito.when(store.exists(623L)).thenReturn(false);
        Mockito.when(fixtures.findByPlayedMatchId(MATCH_ID)).thenReturn(Optional.of(new MatchFixture()));
        Mockito.when(sim.simulate(Mockito.any(), Mockito.eq(true), Mockito.eq(false)))
                .thenReturn(new SimMatchService.SimMatchOutcome(
                        null, 1108L, null, null, java.util.List.of(), null));

        var response = controller.recordingFor(MATCH_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1108L, ((Map<?, ?>) response.getBody()).get("replayId"),
                "the rebuilt recording is the one to hand over");
        assertEquals(true, ((Map<?, ?>) response.getBody()).get("rebuilt"));
    }

    @Test
    @DisplayName("the viewer URL names a recording, never a match")
    void theViewerUrlNamesARecording() {
        SimReplayController controller = controller();
        Mockito.when(matches.findById(MATCH_ID)).thenReturn(Optional.of(aMatch(623L)));
        Mockito.when(store.exists(623L)).thenReturn(true);

        var response = controller.recordingFor(MATCH_ID);
        String url = (String) ((Map<?, ?>) response.getBody()).get("viewer_url");

        assertTrue(url.contains("?replayId="),
                "the viewer URL must name a recording. It used to name it 'matchId', and the two id "
                        + "spaces overlap — match ids and recording ids are both plain counters — so the "
                        + "viewer fell back from one to the other and opened the wrong match: " + url);
        assertTrue(!url.contains("?matchId="), url);
    }

    @Test
    @DisplayName("a match that does not exist is not found, and nothing is simulated")
    void anUnknownMatchIsNotFound() {
        SimReplayController controller = controller();
        Mockito.when(matches.findById(MATCH_ID)).thenReturn(Optional.empty());

        var response = controller.recordingFor(MATCH_ID);

        assertEquals(404, response.getStatusCode().value());
        Mockito.verifyNoInteractions(sim);
    }
}
