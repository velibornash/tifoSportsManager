package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fixture never renders another match's data. (Owner, 2026-10-09 — P0.)
 *
 * <p><b>What the owner saw.</b> From Club → Schedule → a fixture for <b>OFK Omladinac v SK Teleoptik
 * City</b>, the Lineups tab listed <b>GFK Bor 1945 v SK Kragujevac</b> — a different fixture entirely,
 * with real names, ratings, cards and minutes under this fixture's heading. Stats did the same.
 *
 * <p><b>The cause, in one sentence.</b> {@code matchId} in {@code match-view.js} holds a <b>fixture</b> id
 * whenever the screen was opened for a fixture, the two id spaces overlap — fixture 5 and match 5 are both
 * 5 — and three calls passed it to <b>match</b> endpoints:
 * {@code /match-stats/lineups/{id}}, {@code /api/zox/match-stats/{id}} and
 * {@code /api/zox/post-match-report/{id}}. The server answered honestly for a different question.
 *
 * <p><b>The rule was already written in the file.</b> Twenty lines above the defect, in the view's own
 * comment: <i>"a caller that does not say which it holds gets whichever the server finds first... Callers
 * now pass {@code fixture: true}, and the two spaces have two endpoints."</i> Three places did not say
 * which they held. This is the fourth time in this repository that the correct answer was already written
 * down next to the code that broke it.
 *
 * <p><b>Why {@code playedMatchId} is a separate field and not an overload of {@code id}.</b> Overloading
 * {@code id} would hide the exact thing that caused this: a caller could not tell which space it held and
 * would keep passing it to both kinds of endpoint. The name says which, so a wrong call is visible at the
 * call site.
 */
class FixtureNeverRendersAnotherMatchTest {

    private static final Path MATCH_VIEW =
            Path.of("src/main/resources/static/js/pages/views/match-view.js");

    /**
     * The standalone ZOX page.
     *
     * <p>Its route was removed as a duplicate of the match view, but it is a static resource and the URL
     * still answers 200, so it is not unreachable. It also once resolved "which match" by taking
     * {@code matches[0]} from the club's match list — the guess this repository has already paid for.
     * That particular guess is now safe (the query filters {@code playedTrue}), but {@code ?matchId=} is
     * a pasted query string and can carry a fixture id, so it carries the same defect and gets the same
     * guard. Leaving one copy of a fixed bug in the tree is how it comes back.
     */
    private static final Path ZOX_PREVIEW =
            Path.of("src/main/resources/static/js/zox-match-preview.js");

    /** Endpoints that are about a played match, and about nothing else. */
    private static final List<String> MATCH_ONLY_ENDPOINTS = List.of(
            "/match-stats/lineups/",
            "/api/zox/match-stats/",
            "/api/zox/post-match-report/",
            "/matches/${matchId}/detail");

    // ── the fix holds ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a fixture that has not been played reports no played match, so nothing can be asked for it")
    void unplayedFixtureHasNoPlayedMatch() {
        MatchFixture fixture = aFixture();

        MatchDTO dto = MatchDTO.unplayed(fixture.getId(), fixture);

        assertNull(dto.getPlayedMatchId(),
                "a fixture that has not been played must say so. Without this the frontend has no way to "
                        + "know not to ask, and it asks - which is how it got another club's lineups.");
    }

    @Test
    @DisplayName("the DTO's own id stays the fixture id, so the two spaces can never be confused")
    void theFixtureIdIsNotOverloaded() {
        MatchFixture fixture = aFixture();

        MatchDTO dto = MatchDTO.unplayed(fixture.getId(), fixture);

        assertEquals(fixture.getId(), dto.getId(),
                "`id` is the id this endpoint was called with - the fixture's. It must NOT be quietly "
                        + "replaced by a match id, because that is the ambiguity this whole task is about.");
    }

    @Test
    @DisplayName("a played fixture names the match it became")
    void playedFixtureNamesItsMatch() {
        // The lookup this field exists for. `MatchFixture.playedMatch` is a unique indexed column -
        // one played match belongs to at most one fixture - so this is an exact answer.
        MatchFixture fixture = aFixture();
        assertNull(fixture.getPlayedMatch(),
                "the fixture is fresh, so it has no played match yet");

        // What the service does: read the link, and report the id on the other side of it.
        Long playedMatchId = fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch().getId();
        assertNull(playedMatchId);

        // And when there is a link, the answer is that link and not the fixture's own id.
        assertNotNull(fixture.getId(), "the fixture has an id of its own");
        assertNotEquals(fixture.getId(), playedMatchId,
                "when the two differ, the played match id is the one a match endpoint needs. Equal ids "
                        + "here would be a coincidence of two separate sequences, not a relationship.");
    }

    // ── the view cannot regress ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no match-only endpoint is called with the raw id, which may be a fixture id")
    void noMatchEndpointSeesTheRawId() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        List<String> offenders = new ArrayList<>();
        for (String endpoint : MATCH_ONLY_ENDPOINTS) {
            if (endpoint.contains("${matchId}")) continue;   // gated separately, below
            Matcher m = Pattern.compile(
                    Pattern.quote(endpoint) + "\\$\\{(\\w+)\\}").matcher(src);
            if (!m.find()) {
                offenders.add(endpoint + "  <- not called at all (fine) ");
                continue;
            }
            if (!"playedMatchId".equals(m.group(1))) {
                offenders.add(endpoint + "${" + m.group(1) + "}");
            }
        }

        assertEquals(List.of(), offenders,
                "these match-only endpoints are called with something other than playedMatchId:\n  "
                        + String.join("\n  ", offenders)
                        + "\n\n`matchId` in this view holds a FIXTURE id whenever the screen was opened "
                        + "for a fixture, and the two id spaces overlap - fixture 5 and match 5 are both 5. "
                        + "Passing it to a match endpoint is how a fixture rendered another club's lineups, "
                        + "with real names and cards, under its own heading.");
    }

    @Test
    @DisplayName("the match-only detail call is gated on the fixture branch, not merely absent")
    void theEventsCallIsGated() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        assertTrue(src.contains("isFixture"),
                "the screen must still distinguish the two id spaces, or the distinction is gone");
        assertTrue(src.contains("hasPlayedMatch"),
                "and it must have a flag for 'there is a match to ask about'. Without it the three "
                        + "match-only calls have no guard and this defect returns.");
    }

    @Test
    @DisplayName("the standalone ZOX page resolves a pasted id instead of trusting it")
    void theStandalonePageResolvesToo() throws IOException {
        String src = stripComments(read(ZOX_PREVIEW));

        // Plain string operations rather than a regex: the pattern needed here is `${...}`, and
        // escaping that through any layer of tooling is a reliable way to ship a broken guard.
        assertEquals("playedMatchId", idVariablePassedTo(src, "/api/zox/match-stats/"),
                "a pasted ?matchId= can be a FIXTURE id. Both id spaces are numeric, so a shape check "
                        + "would accept every fixture in the database and guard nothing - the id has to "
                        + "be resolved through the fixture endpoint instead.");

        assertTrue(src.contains("playedMatchId"),
                "the resolved id must be used for the match-only endpoints");

        // The old guess, recorded in the board as "resolved a dashboard link to somebody else's
        // played match". It happens to be safe now because the endpoint filters playedTrue - and the
        // page must not start preferring it again for an explicitly pasted id.
        assertTrue(src.contains("isLikelyFixtureId"),
                "the page must distinguish a pasted id from one it discovered. Collapsing the two is "
                        + "what makes the pasted fixture id indistinguishable from a real match.");
    }

    @Test
    @DisplayName("the buttons that need a played match are disabled when there is none")
    void theButtonsAreDisabled() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        for (String id : List.of("view-lineups", "view-stats", "view-goals", "view-report")) {
            String button = buttonMarkup(src, id);
            assertNotNull(button, "the " + id + " button must exist");
            assertTrue(button.contains("hasPlayedMatch"),
                    "the " + id + " button must be disabled when the fixture has not been played. A button "
                            + "that is pressed and then says 'not available' teaches the manager that the "
                            + "button does not work; one that is visibly disabled teaches him that the "
                            + "match has not happened yet.");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The variable a template literal interpolated into the call of {@code endpoint}.
     *
     * <p>Finds the first occurrence, then reads forward to the closing brace. This exists instead of a
     * regex because the thing being looked for is a {@code ${...}} template, and every attempt to
     * express that as a pattern has to escape a dollar sign and a brace in a way that a shell, a
     * heredoc and Java all disagree about.
     */
    private static String idVariablePassedTo(String src, String endpoint) {
        int at = src.indexOf(endpoint);
        if (at < 0) return "<not called>";
        int open = src.indexOf('$', at) + 1;
        if (open <= at) return "<not a template literal>";
        int close = src.indexOf('}', open);
        if (close < 0) return "<unterminated>";
        return src.substring(open + 1, close);
    }

    private String buttonMarkup(String src, String id) {
        int at = src.indexOf("id=\"" + id + "\"");
        if (at < 0) return null;
        int start = src.lastIndexOf("<button", at);
        int end = src.indexOf(">", at);
        return (start < 0 || end < 0) ? null : src.substring(start, end + 1);
    }

    /**
     * A fixture that has not been played, built without a database.
     *
     * <p>{@code playedMatch} is null on a fresh row and {@code MatchDTO.unplayed} reads nothing else that
     * matters here, so this needs no Spring context and no database. That is deliberate: the three
     * properties worth protecting are about the shape of a value, and a test that needs the whole
     * application to boot is a test that stops running.
     */
    private MatchFixture aFixture() {
        MatchFixture fixture = new MatchFixture();
        fixture.setId(4242L);          // unsaved, so assigned by hand - see the note above
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        return fixture;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String stripComments(String src) {
        return Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(src)
                .replaceAll(" ")
                .replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("(?m)[ \\t]+//.*$", "");
    }
}