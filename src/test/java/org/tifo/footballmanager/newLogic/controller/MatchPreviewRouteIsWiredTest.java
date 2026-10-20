package org.tifo.footballmanager.newLogic.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The match-preview route answers a preview, not two empty maps.
 *
 * <p><b>What was wrong (found 2026-10-19, P0-9).</b> {@code MatchController.getMatchPreview} put
 * {@code Map.of()} in for the prediction and another for the h2h, and did not inject
 * {@code MatchPreviewService} at all. It was the <b>third</b> route to answer the question "what does
 * the preview of this match say", and the worst of the three — the other two go through
 * {@code MatchPreviewService}, which builds the ratings, the form, the prediction and the formations.
 *
 * <p><b>Why a structural test.</b> The route's <em>shape</em> is the defect, and its shape is decided
 * by which service it calls — so the assertion is on the wiring, which is what the repo's other
 * structural guards do ({@code PlusGateHasCallersTest} and its neighbours). A request-level test would
 * have to publish a match and read the whole payload to notice that two of its fields were empty,
 * and it would still have said nothing about the other route in the family.
 */
class MatchPreviewRouteIsWiredTest {

    private static final Path CONTROLLER = Path.of(
            "src/main/java/org/example/footballmanager/newLogic/controller/MatchController.java");

    private static String source() throws Exception {
        return Files.readString(CONTROLLER, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the preview route is built by the preview service")
    void theRouteUsesThePreviewService() throws Exception {
        String source = source();
        assertTrue(source.contains("MatchPreviewService matchPreviewService"),
                "the preview route does not inject MatchPreviewService, so it builds its own answer - "
                        + "and its own answer was two empty maps");
        assertTrue(source.contains("matchPreviewService.preview("),
                "the preview route does not call MatchPreviewService.preview(...), so it is the third "
                        + "implementation of the preview and the worst of the three");
    }

    @Test
    @DisplayName("the preview route does not answer with empty maps")
    void theRouteDoesNotAnswerWithEmptyMaps() throws Exception {
        String source = source();
        int from = source.indexOf("public ResponseEntity<Map<String, Object>> getMatchPreview(");
        assertTrue(from > 0, "the preview route is gone from MatchController");
        int to = source.indexOf("\n    @", from);
        String body = source.substring(from, to < 0 ? source.length() : to);

        assertFalse(body.contains("Map.of()"),
                "the preview route still answers with an empty map. The prediction and the h2h were "
                        + "the two empty ones, and the route now answers what the fixture route "
                        + "answers - the same service, the same shape");
    }
}
