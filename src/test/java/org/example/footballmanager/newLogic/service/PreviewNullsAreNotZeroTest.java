package org.example.footballmanager.newLogic.service;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Not knowable yet" must read as not knowable, and not as zero. (Owner, 2026-10-09 — P0.)
 *
 * <p><b>What the owner saw</b> on the unplayed OFK Omladinac v SK Teleoptik City fixture:
 *
 * <pre>
 *   HOME EDGE            OFK Omladinac        0% · 0.0 bench
 *   SQUAD FIT            OFK Omladinac        0% fit    0.0 bench
 *   Availability 0% vs 0%
 * </pre>
 *
 * <p><b>And the service was right.</b> {@code MatchPreviewService.preview} deliberately sends
 * {@code null} for formation fitness, bench quality, availability, position mismatches and play style,
 * and says why in its own comment: <i>"These are not knowable before a match, and inventing them is what
 * the original all-null fixture preview was right about."</i> That reasoning is sound and is kept here.
 *
 * <p><b>The defect was one operator.</b> The renderer read
 * {@code Number(previewPayload?.homeBenchQuality ?? 0)} — the {@code ?? 0} collapsed the null into a real
 * zero three lines before the display helpers saw it. The helpers were written correctly:
 * {@code pct}, {@code fixed1} and {@code withUnit} all return empty for null, so the card was designed to
 * print <b>"Not known yet"</b>. Every one of those guards had nothing left to guard, and a null became a
 * confident, specific, wrong number.
 *
 * <p>This is the same defect the {@code MatchDTO} score already documents: <i>a 0-0 that was never played
 * is indistinguishable from a goelless draw.</i> A squad with no known fitness is not a squad at 0% fitness.
 *
 * <p><b>Why a source scan.</b> As in {@code RouterNamesResolveTest}: this repository has no JavaScript
 * test infrastructure at all. The property here is about which operator is applied to which field in the
 * shipped file, and that is checkable without a browser.
 */
class PreviewNullsAreNotZeroTest {

    private static final Path MATCH_VIEW =
            Path.of("src/main/resources/static/js/pages/views/match-view.js");

    /**
     * The fields the service sends as null on purpose, because they are not knowable before a match.
     *
     * <p>Named in the service, not guessed here — {@code MatchPreviewService.preview} puts each of these
     * to null with a comment saying why.
     */
    private static final List<String> NOT_KNOWABLE_YET = List.of(
            "homeFormationFitness", "awayFormationFitness",
            "homeBenchQuality", "awayBenchQuality",
            "homeAvailabilityScore", "awayAvailabilityScore");

    @Test
    @DisplayName("a not-knowable field keeps its null instead of becoming a confident zero")
    void nullsSurviveToTheRenderer() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        List<String> coerced = new ArrayList<>();
        for (String field : NOT_KNOWABLE_YET) {
            int at = src.indexOf(field);
            while (at >= 0) {
                // The declaration line is `const <field> = <expression>;`
                int end = src.indexOf(';', at);
                String line = src.substring(at, end + 1);
                if (line.contains("?? 0") || line.contains("|| 0")) {
                    coerced.add(field + "  ->  " + line.trim());
                }
                at = src.indexOf(field, at + 1);
            }
        }

        assertEquals(List.of(), coerced,
                "these fields are null on purpose because they are not knowable before a match, and the "
                        + "renderer collapsed them to a real 0:\n  " + String.join("\n  ", coerced)
                        + "\n\nThat is how an unplayed fixture displayed \"0.0 bench\" and "
                        + "\"Availability 0% vs 0%\". A 0-0 that was never played is not a goalless draw, "
                        + "and a squad of unknown fitness is not a squad at 0% fitness.");
    }

    @Test
    @DisplayName("a null is not multiplied into a zero on its way to the display")
    void nullsAreNotArithmetic() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        // The second half of the same defect, and it is the reason the first fix was not enough.
        //
        // With `Number(x ?? 0)` corrected, the card stopped saying "0.0 bench" — and still said
        // "0%" and "0% fit", because the line was `pct(homeFormationFitness * 100)` and in JavaScript
        // **`null * 100 === 0`**. The null was turned into a real zero by the arithmetic itself,
        // before `pct` ever saw it, so every null-safe guard downstream had nothing left to guard.
        //
        // Found by looking at the screen after believing the first fix had worked.
        Matcher arith = Pattern.compile("\\bpct\\w*\\(([^()]*)\\)").matcher(src);
        List<String> coerced = new ArrayList<>();
        while (arith.find()) {
            String argument = arith.group(1);
            // A field known to be null-by-design, multiplied or added on its way into a helper.
            for (String field : NOT_KNOWABLE_YET) {
                if (argument.contains(field) && argument.contains("*")
                        && !argument.contains("pctOfFraction(")) {
                    coerced.add(arith.group().trim());
                }
            }
        }

        assertEquals(List.of(), coerced,
                "these multiply a not-knowable field before displaying it:\n  "
                        + String.join("\n  ", coerced)
                        + "\n\nnull * 100 is 0 in JavaScript, so the multiply itself invents the "
                        + "number. It must happen only once there is something to multiply — which is "
                        + "what pctOfFraction does.");
    }

    @Test
    @DisplayName("the display helpers still know how to say nothing")
    void theHelpersCanStillSayNothing() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        // If these stop handling null, the fix in the previous test silently becomes cosmetic again.
        for (String helper : List.of("const pct", "const fixed1", "const withUnit")) {
            int at = src.indexOf(helper);
            assertTrue(at >= 0, helper + " must exist");
            assertTrue(src.substring(at, at + 200).contains("null"),
                    helper + " must still return empty for null. It is what turns a not-knowable value "
                            + "into an honest blank rather than a number.");
        }
    }

    @Test
    @DisplayName("the card falls back to 'Not known yet' when nothing is knowable")
    void thereIsStillAHonestFallback() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        assertTrue(src.contains("Not known yet"),
                "the edge cards must still have an honest fallback. Without it a null renders as an "
                        + "empty string and the manager reads a missing value as a zero one.");
    }

    @Test
    @DisplayName("the analysis is not printed twice on the same card")
    void theAnalysisIsNotDuplicated() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        // MatchPreviewService.reasonsFor already appends prediction.analysis() to predictionReasons, and
        // analysisText carries that same string. Rendering both showed "Away edge · OVR 38:82 ·
        // form 7.1:4.6" twice, under two headings, which reads as two separate findings.
        int analysisRenders = countOccurrences(src, "previewPayload?.analysisText");
        assertTrue(analysisRenders <= 1,
                "analysisText must be rendered at most once, and only when it is not already in the "
                        + "reasons list — found " + analysisRenders + " renderings");

        assertTrue(src.contains("!predictionReasons.includes(analysis)"),
                "the guard must compare against the reasons list, so the two renderings cannot drift "
                        + "apart again if the service changes what it puts in either.");
    }

    @Test
    @DisplayName("the helper is defined before it is used, or the card throws on load")
    void noTemporalDeadZone() throws IOException {
        String src = stripComments(read(MATCH_VIEW));

        int defined = src.indexOf("const numberOr");
        int firstUse = src.indexOf("numberOr(previewPayload");

        assertTrue(defined >= 0, "numberOr must be declared");
        assertTrue(firstUse >= 0, "numberOr must be used");
        assertTrue(defined < firstUse,
                "numberOr is declared after its first use. `const` is not hoisted, so loading this card "
                        + "would throw a ReferenceError and the whole Preview tab would be blank — a "
                        + "crash introduced by the fix for a different defect.");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private static int countOccurrences(String src, String needle) {
        int n = 0;
        int at = src.indexOf(needle);
        while (at >= 0) {
            n++;
            at = src.indexOf(needle, at + 1);
        }
        return n;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("(?m)[ \\t]+//.*$", "");
    }
}