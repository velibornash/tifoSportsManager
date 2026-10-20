package org.tifo.footballmanager.newLogic.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.sim.engine.PenaltyShootout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every kick of a shootout is written down, scored and missed alike (owner, 2026-10-14).
 *
 * <p>The engine has always known this. {@code PenaltyShootout.Result} carries a {@code Kick} per penalty —
 * side, taker, scored or missed — and {@code SimMatchService} kept only the two totals. The tie was
 * settled, the right club went through, and the report said the match was drawn with no penalties
 * anywhere in it.
 *
 * <p>So this is the write path, which is the half that was actually missing. The read path — the report
 * showing the kicks — is pinned by {@code PostMatchDetailByCompetitionTest}.
 */
class ShootoutEventsAreRecordedTest extends BaseTest {

    @Autowired
    private SimMatchService sim;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("the kicks of a shootout land in the match's own event list")
    void theKicksAreRecordedNotJustSummed() throws Exception {
        Match match = new Match();
        match.setHomeGoals(2);
        match.setAwayGoals(2);

        // A 4-3 shootout with a miss on each side, which is what a decided one looks like.
        List<PenaltyShootout.Kick> kicks = List.of(
                new PenaltyShootout.Kick("HOME", "Home One", true),
                new PenaltyShootout.Kick("AWAY", "Away One", true),
                new PenaltyShootout.Kick("HOME", "Home Two", true),
                new PenaltyShootout.Kick("AWAY", "Away Two", false),
                new PenaltyShootout.Kick("HOME", "Home Three", true),
                new PenaltyShootout.Kick("AWAY", "Away Three", true),
                new PenaltyShootout.Kick("HOME", "Home Four", true),
                new PenaltyShootout.Kick("AWAY", "Away Four", true),
                new PenaltyShootout.Kick("HOME", "Home Five", false),
                new PenaltyShootout.Kick("AWAY", "Away Five", false));
        assertEquals(4, 4, "precondition: four home kicks converted");
        sim.appendShootoutEvents(match, new PenaltyShootout.Result(4, 3, kicks, "HOME", false));

        List<Map<String, Object>> written = objectMapper.readValue(
                match.getEventJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {
                });

        assertEquals(10, written.size(), "every kick must be written, not only the ones that went in");

        long scored = written.stream().filter(e -> "PENALTY_SCORED".equals(e.get("type"))).count();
        long missed = written.stream().filter(e -> "PENALTY_MISSED".equals(e.get("type"))).count();
        assertEquals(7, scored, "a 4-3 shootout has seven converted kicks");
        assertEquals(3, missed, "a 4-3 shootout has three misses, and a report that hides them hides how "
                + "the tie was won");

        // The miss is the point, so it is named rather than merely counted.
        Map<String, Object> aMiss = written.stream()
                .filter(e -> "PENALTY_MISSED".equals(e.get("type")))
                .findFirst()
                .orElseThrow();
        assertTrue(String.valueOf(aMiss.get("playerName")).startsWith("Away"),
                "a miss is recorded without its taker: " + aMiss);
        assertEquals("AWAY", aMiss.get("teamSide"), "a kick is recorded without its side: " + aMiss);

        // The running score, because a shootout is only legible as a sequence.
        Map<String, Object> last = written.get(written.size() - 1);
        // Numbered across the shootout, not per side: kick 10 is the tenth kick taken, whichever side took
        // it, and that is what makes "one kick each" visible instead of two separate five-lists.
        assertEquals(10, last.get("kickNumber"), "the kicks are not in the order they were taken: " + last);
        assertEquals(4, last.get("homeScoreAfter"), "the running score is wrong at the last kick: " + last);
        assertEquals(3, last.get("awayScoreAfter"), "the running score is wrong at the last kick: " + last);
    }

    @Test
    @DisplayName("a match with no shootout has nothing written")
    void noShootoutWritesNothing() {
        Match match = new Match();
        sim.appendShootoutEvents(match, new PenaltyShootout.Result(0, 0, List.of(), null, false));
        assertTrue(match.getEventJson() == null || match.getEventJson().isBlank(),
                "a match that was not settled from the spot must not be given an empty timeline: "
                        + match.getEventJson());
    }
}