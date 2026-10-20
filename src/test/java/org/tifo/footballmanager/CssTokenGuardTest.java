package org.tifo.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The components stylesheet stays on the theme tokens, not on bare colours (redesign.md R-4).
 *
 * <p>The palettes live as {@code html[data-theme]} custom-property overrides in
 * {@code dashboard.css}. That only re-skins what consumes {@code var(--token)}: a single bare
 * {@code #hex} in a component rule silently opts out of every palette, which is exactly how
 * "the app is configurable" degrades into "the app is configurable except most of it". So this
 * test reads the source, not the browser.
 *
 * <p>The allowlist is the ratchet, and every entry needs its reason written down. Special-purpose
 * colours are exempt on principle: pitch turf is grass in every palette, rating scales rate in
 * their own colours, and a badge carrying its own meaning keeps it. Anything else in the allowlist
 * is still-unmigrated debt that needs a token decided for it; delete its entry when a task folds it
 * into a token.
 *
 * <p>Broken deliberately once before being trusted: appending {@code .tmp-guard-probe {
 * color: #ff0000; }} to the sheet failed this test as written, removing it passed (2026-10-19).
 *
 * <p>The same run caught the real {@code #4f9fd0} straggler. A second, quieter hole closed later the
 * same day: the pattern only matched <b>six</b>-digit hexes, so {@code #222} and {@code #eee} walked
 * straight through it — and those were exactly the literals that made the light theme's drawer
 * unreadable. A guard that cannot see half the syntax it exists to police is decoration; the pattern
 * matches three digits too now, and the short form is compared in its long form so the allowlist
 * cannot be dodged by spelling. Broken deliberately again after that change: a planted {@code #f0a}
 * failed the test, removal passed.
 */
class CssTokenGuardTest {

    private static final Path COMPONENTS_CSS =
            Path.of("src/main/resources/static/css/dashboard/components.css");

    /** Bare hex found in lines that define custom properties is a definition, not a violation. */
    private static final Pattern BARE_HEX = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");

    /**
     * Special-purpose colours that deliberately do not follow the palette. The list is closed:
     * adding a bare hex anywhere else in {@code components.css} must fail this test.
     */
    private static final Set<String> ALLOWED = Set.of(
            // rating scale hot/cold ends and the "quality" middle step
            "#4caf50", "#64b5f6", "#f44336", "#ff9800", "#ff7043",
            // danger state text/fills on alice-on-red badges
            "#ff9d9d", "#ffb3b3",
            // gold/sponsor family: milestone golds and the bronze/silver placeholders
            "#ffd27a", "#fff7d6", "#f7e08a", "#c9992b", "#b8862a", "#e0a877",
            // gentleReadable light greys waiting on a token decision (R-4 leftovers)
            "#f2f7ff", "#c7d2e4", "#e8eefb", "#eaf6ff", "#eef1f5", "#cfd8dc", "#9aa4b2", "#8b98ad",
            // seating-type and brand backgrounds tied to club identity, not to the palette
            "#0d1620", "#0b1624", "#07121f", "#07101a", "#1a2740", "#96603a",
            // green depth steps waiting to hand their final gradient roles to pos-deep/pos-mid
            "#2f7d46", "#2a7240",
            // pitch markings: a spot on grass is white in every palette (the pitch itself is grass)
            "#ffffff"
    );

    @Test
    @DisplayName("dashboard/components.css consumes tokens; only the documented special colours stay literal")
    void componentsSheetOnlyUsesTokensAndDocumentedSpecialColours() throws IOException {
        assertTrue(Files.exists(COMPONENTS_CSS), "the components sheet must exist at " + COMPONENTS_CSS);
        List<String> offenders = new java.util.ArrayList<>();
        var lines = Files.readAllLines(COMPONENTS_CSS);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("--") && line.contains(":")) {
                continue; // a custom-property definition is where a literal is allowed to live
            }
            Matcher hex = BARE_HEX.matcher(line);
            while (hex.find()) {
                String raw = hex.group().toLowerCase();
                // An 8-digit hex carries its own alpha; it is always a decision, never a default.
                if (raw.length() == 9) {
                    offenders.add("line " + (i + 1) + ": 8-digit hex (with alpha) not allowed: " + line.trim());
                    continue;
                }
                // #abc and #aabbcc are the same colour; the allowlist is written in the long form
                // so that a short hex cannot slip past it by being spelled differently.
                String normalised = raw.length() == 4
                        ? "#" + raw.charAt(1) + raw.charAt(1) + raw.charAt(2) + raw.charAt(2) + raw.charAt(3) + raw.charAt(3)
                        : raw;
                if (!ALLOWED.contains(normalised)) {
                    offenders.add("line " + (i + 1) + ": " + raw + " in " + line.trim());
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("Components CSS went off the tokens with bare hex colours:\n" + String.join("\n", offenders)
                    + "\nEither switch these to palette tokens (dashboard.css :root) or, if the colour is "
                    + "special-purpose, name its reason and append it to the allowlist of CssTokenGuardTest.");
        }
    }

    @Test
    @DisplayName("accent alpha tints ride --accent-rgb, so Ember and Pitch re-tint them")
    void accentTintsRideTheAccentRgbToken() throws IOException {
        long varTints;
        try (Stream<String> lines = Files.lines(COMPONENTS_CSS)) {
            varTints = lines.filter(l -> l.contains("rgba(var(--accent-rgb)")).count();
        }
        // 13 carried the token when this guard landed (2026-10-19). Assert only that the static
        // accent colour literal was actually retired, rather than pinning today's count.
        assertTrue(varTints > 0, "the accent tints must use rgba(var(--accent-rgb), a)");
        try (Stream<String> lines = Files.lines(COMPONENTS_CSS)) {
            List<String> staticAccentRgb = lines
                    .filter(l -> l.matches(".*rgba\\(\\s*143\\s*,\\s*211\\s*,\\s*255.*"))
                    .collect(Collectors.toList());
            if (!staticAccentRgb.isEmpty()) {
                fail("midnight's raw accent rgb(143,211,255) re-appeared in components.css:\n"
                        + String.join("\n", staticAccentRgb)
                        + "\nUse rgba(var(--accent-rgb), a) so the other palettes re-tint it.");
            }
        }
    }
}
