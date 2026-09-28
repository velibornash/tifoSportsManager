package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S8.3 #5: {@code training-view.js} declared three functions twice in the same scope.
 *
 * <p>The backlog called it "two ~450-line parallel implementations" needing a merge. It was not that.
 * {@code render}, {@code renderGraph} and {@code openPlayerGraph} were each declared twice at the
 * same brace depth inside {@code createTrainingView}, and in JavaScript a later function declaration
 * <b>shadows</b> an earlier one without any warning. The first copies were unreachable: ~183 lines
 * of code that no call could ever reach.
 *
 * <p>They had also already diverged — the surviving copy of {@code renderGraph} builds a
 * player-profile hero the shadowed one never had — which is what made this look like a merge job.
 * Merging two versions of a function where one is provably dead is a way of losing the better one.
 * The fix was to delete the shadowed copy, not to reconcile the pair.
 *
 * <p>What makes this worth a test: a shadowed declaration is <b>valid JavaScript</b>. {@code node
 * --check} passes, the bundler is silent, and no linter in the project flagged it. The only honest
 * check is to count the names.
 */
class TrainingViewNoShadowedDeclarationsTest {

    private static final Path VIEW =
            Path.of("src/main/resources/static/js/pages/views/training-view.js");

    @Test
    @DisplayName("no function is declared twice in the same scope - a later one silently shadows it")
    void noFunctionIsDeclaredTwice() throws IOException {
        String code = stripComments(Files.readString(VIEW, StandardCharsets.UTF_8));

        // Declarations at the same indentation are the same scope. Anything nested deeper is
        // legitimately a different scope and is not counted.
        //
        // Indentation is matched with [ \t] and never \s: \s also matches newlines, so a greedy
        // \s+ can swallow the blank line above a declaration and the ^ anchor quietly stops
        // meaning anything. The first version of this test did exactly that and passed while a
        // shadowed copy was sitting right in front of it.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher decls = Pattern
                .compile("^([ \\t]+)(?:async[ \\t]+)?function[ \\t]+([A-Za-z0-9_]+)[ \\t]*\\(", Pattern.MULTILINE)
                .matcher(code);
        while (decls.find()) {
            counts.merge(decls.group(1) + "::" + decls.group(2), 1, Integer::sum);
        }

        Map<String, Integer> shadowed = new LinkedHashMap<>();
        counts.forEach((key, n) -> {
            if (n > 1) {
                shadowed.put(key.substring(key.indexOf("::") + 2), n);
            }
        });

        assertTrue(shadowed.isEmpty(),
                () -> "These functions are declared more than once at the same scope in "
                        + VIEW.getFileName() + ": " + shadowed + ". JavaScript lets the later "
                        + "declaration win, so every earlier copy is unreachable - valid syntax, no "
                        + "warning, no linter, and dead code. Delete the earlier one rather than "
                        + "merging the pair, since the copies have diverged and only one is live.");
    }

    @Test
    @DisplayName("the surviving renderGraph is the richer one, so the deletion kept the right copy")
    void theSurvivingCopyIsTheOneThatIsUsed() throws IOException {
        String code = Files.readString(VIEW, StandardCharsets.UTF_8);

        // The live copy builds a player-profile hero header; the shadowed one did not. If this ever
        // inverts, the wrong half of the pair was kept.
        assertTrue(code.contains("buildPlayerProfileHeroHtml"),
                "the surviving renderGraph is expected to be the version with the profile hero — if "
                        + "this is missing, the wrong copy survived the deletion");

        // And the call site outside the deleted block must still resolve to a real declaration.
        assertTrue(Pattern.compile("await openPlayerGraph\\s*\\(").matcher(code).find(),
                "the caller of openPlayerGraph must remain");
        assertTrue(Pattern.compile("function openPlayerGraph\\s*\\(").matcher(code).find(),
                "openPlayerGraph must still be declared exactly once");
    }

    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "\n").replaceAll("(?m)//.*$", "");
    }
}
