package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No template literal may print the word {@code escapeHtml}.
 *
 * <p><b>What this exists for (codeCleaning §8.1).</b> The match preview in {@code tifo.js} was written with
 * the escaping calls <em>inside</em> the template literal but without the interpolation markers, so the
 * rendered page showed the club's name as the literal text {@code escapeHtml(homeName)} — eight times, in
 * the heading, both team names, both table headers and both prediction lines. It was a display bug rather
 * than a hole (the name was never printed, so nothing could be injected through it), and it was the kind of
 * bug that survives every check: the file parses, the function is called, the modal opens.
 *
 * <p><b>Why a scan and not a browser.</b> The symptom is in the rendered output, and a rendered-output check
 * for this file means driving a large SPA. The defect itself is a shape in the source — the text
 * {@code escapeHtml(} appearing as template <em>text</em> rather than inside a {@code ${…}} interpolation —
 * and a scan for that shape is exact, needs no infrastructure, and cannot be fooled by a value that happens
 * to be harmless.
 *
 * <p><b>Why the scan is not naive.</b> {@code escapeHtml(x)} inside an interpolation is correct and common —
 * {@code ${affiliateClub ? escapeHtml(affiliateClub) : '…'}} appears in {@code tifo.js} today. So the scan
 * has to know where the template text ends and the interpolation begins, and it tracks that properly:
 * it walks template literals, skips over every {@code ${…}} (brace by brace, so a nested object literal
 * does not end it early), and only then looks for the tell-tale text.
 */
class TemplateLiteralEscapingTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    /**
     * The parts of the static tree that belong to the other two games, and the one file that belongs to
     * the text manager.
     *
     * <p><b>{@code tifo.js} is not the MVP's.</b> Its own header says
     * <i>"tifo.js - Clean Sheet Text Mode (in-memory)"</i> and it is loaded by {@code tifo.html}, not by
     * any of this game's pages. It sits in the same folder as this game's own {@code js/dashboard.js},
     * which is how it was read as MVP work and nearly fixed from the wrong board.
     *
     * <p>It does have the same defect — the template literals print {@code escapeHtml(homeName)} as text,
     * eight times. **That belongs on the text manager's board, not this one**, and an MVP test that fails on
     * it sends the next person to the wrong codebase.
     */
    private static final List<String> NOT_OURS = List.of(
            "tifo.js",
            "americanfootballmanager/",
            "basketballmanager/",
            "commonmanager/");

    @Test
    @DisplayName("no template literal prints the word escapeHtml")
    void noTemplateLiteralPrintsTheEscapingCall() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(STATIC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                String relative = STATIC.relativize(file).toString();
                if (NOT_OURS.stream().anyMatch(relative::contains)) {
                    continue;
                }
                for (Hit hit : literalEscapeHtmlTexts(Files.readString(file, StandardCharsets.UTF_8))) {
                    offenders.add(relative + " line " + hit.line() + ": " + hit.text());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "these template literals print the escaping call as text instead of calling it:\n"
                        + String.join("\n", offenders)
                        + "\n\nThe name is missing its ${…} markers, so the page shows the word "
                        + "\"escapeHtml\" where the club's name should be. The same shape with a server "
                        + "value in it is an injection, not a typo.");
    }

    @Test
    @DisplayName("the scan finds the shape it is guarding")
    void theScanFindsTheShape() {
        // A scan that finds nothing proves nothing, so it is shown a template literal that has the
        // defect and a template literal that does not.
        String withTheDefect = "let x = `<p><strong>escapeHtml(homeName)</strong></p>`;";
        String withoutIt = "let x = `<p><strong>${escapeHtml(homeName)}</strong></p>`;";

        assertTrue(literalEscapeHtmlTexts(withTheDefect).size() == 1,
                "the scan missed the shape it exists to find");
        assertTrue(literalEscapeHtmlTexts(withoutIt).isEmpty(),
                "the scan flagged a correct interpolation as a defect — a scan that cries wolf is worse "
                        + "than no scan");
    }

    @Test
    @DisplayName("an interpolation that contains an object literal is not mistaken for template text")
    void aNestedObjectLiteralDoesNotEndTheInterpolation() {
        // The braces of a nested object literal have to be counted, or the scan thinks the
        // interpolation ended early and reads the rest of it as template text.
        String nested = "let x = `<p>${obj.map(o => ({ a: o.a }))}</p>`;";
        String withTheDefectAfter = "let x = `<p>${obj.map(o => ({ a: o.a }))}</p>escapeHtml(name)`;";

        assertTrue(literalEscapeHtmlTexts(nested).isEmpty(),
                "a nested object literal was mistaken for the end of the interpolation");
        assertTrue(literalEscapeHtmlTexts(withTheDefectAfter).size() == 1,
                "and text after it was not read as template text");
    }

    // ── the scan ─────────────────────────────────────────────────────────────────────────────────

    private record Hit(int line, String text) {
    }

    /**
     * Every {@code escapeHtml(x)} that appears as template <b>text</b> rather than inside a
     * {@code ${…}} interpolation.
     */
    private List<Hit> literalEscapeHtmlTexts(String source) {
        List<Hit> hits = new ArrayList<>();
        int i = 0;
        int line = 1;
        int n = source.length();

        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c != '`') {
                i++;
                continue;
            }
            // A template literal starts here.
            i++;
            while (i < n) {
                char t = source.charAt(i);
                if (t == '\\') {
                    i += 2;
                    continue;
                }
                if (t == '\n') {
                    line++;
                    i++;
                    continue;
                }
                if (t == '`') {
                    i++;
                    break;
                }
                if (t == '$' && i + 1 < n && source.charAt(i + 1) == '{') {
                    // Skip the whole interpolation, counting braces so a nested object literal
                    // does not end it early.
                    int depth = 1;
                    int j = i + 2;
                    while (j < n && depth > 0) {
                        char b = source.charAt(j);
                        if (b == '{') {
                            depth++;
                        } else if (b == '}') {
                            depth--;
                        } else if (b == '\n') {
                            line++;
                        }
                        j++;
                    }
                    i = j;
                    continue;
                }
                if (source.startsWith("escapeHtml(", i)) {
                    int end = source.indexOf(')', i);
                    if (end > 0 && end - i < 80) {
                        hits.add(new Hit(line, source.substring(i, end + 1)));
                        i = end + 1;
                        continue;
                    }
                }
                i++;
            }
        }
        return hits;
    }
}
