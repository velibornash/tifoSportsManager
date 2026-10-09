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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No loader consumes a JSON body without checking whether the request succeeded.
 *
 * <p><b>Why this is a correctness matter and not a style rule.</b> {@code authFetch} throws on every
 * non-2xx. So an unguarded {@code await response.json()} never reaches the {@code if (!response.ok)}
 * guard written for it — the throw skips it and escapes to whatever called the loader. {@code pages.js} is
 * the router every page goes through, and its last line replaces the page with a two-word card:
 * {@code buildEmptyState("API Error")}. So a 403 carrying the sentence *"Only the owning club can accept
 * incoming offers"* and a 500 from an unreachable database reached the manager as **the same two words**.
 *
 * <p>The worst instances found were not single loaders but {@code Promise.all} groups, where one
 * non-2xx rejected the whole set:
 *
 * <ul>
 *   <li>the ZOX match screen fetched preview, statistics and report unchecked — so a manager who was
 *       refused the post-match report also lost the <em>pre-match preview</em>, which he was allowed</li>
 *   <li>the transfer centre fetched market, overview and squad unchecked — so a 403 on the global market
 *       also emptied the list of his own players</li>
 *   <li>the statistics screen guarded the team directory and left the two leaderboards unguarded, in one
 *       {@code Promise.all}</li>
 * </ul>
 *
 * <p><b>What counts as guarded.</b> An {@code .ok} check on any variable in scope, a {@code .catch()},
 * an enclosing {@code try}, or one of the two shared readers {@code readJsonOrThrow} / {@code readJsonOr}.
 *
 * <p><b>Why a source scan.</b> The property is about which guard sits next to which call in a file that
 * has no test runner — this repository has <b>no JavaScript test infrastructure at all</b>, so
 * {@code package.json} holds one unrelated dependency and there is no {@code *.test.js}. Executing the
 * module is the only other option and it needs a browser per file. The scan reads the real files.
 */
class LoadersCheckResponseOkTest {

    private static final Path JS_ROOT = Path.of("src/main/resources/static/js");

    /** `.json()` that is not on a comment line. */
    private static final Pattern JSON_CALL = Pattern.compile("\\.json\\(\\)");

    /** Any variable's `.ok` — not just `response.ok` or `res.ok`. */
    private static final Pattern OK_CHECK = Pattern.compile("\\b[A-Za-z_$][\\w$]*\\.ok\\b");

    private static final List<String> SHARED_READERS = List.of("readJsonOrThrow", "readJsonOr");

    /** Endpoints where a failure genuinely is fatal, and throwing is the correct behaviour. */
    private static final List<String> LEGITIMATELY_FATAL = List.of(
            "/auth/me", "/auth/login", "/auth/register",
            "/api/server-time", "/api/game-clock");

    /** How far above a call to look for an {@code .ok} check or a {@code .catch}. */
    private static final int GUARD_WINDOW = 20;

    /**
     * Whether an {@code if (!x.ok) { ... return; }} above this line already returned.
     *
     * <p>Needed because a fixed window cannot see it. {@code training-view.js:407} sits **23 lines** below
     * its guard: the block in between is a twenty-line error page. A window wide enough to cover that is
     * wide enough to be satisfied by an unrelated {@code .ok} on some other variable, which is worse than
     * useless.
     *
     * <p>So this walks each {@code if (... .ok ...)} above the call, brace-matches its block forward,
     * and asks whether that block returns or throws before reaching the call. That is the actual
     * control-flow question, and answering it is cheaper than guessing a window.
     */
    private static boolean guardedByAnEarlierOkBlock(List<String> lines, int callLine) {
        Pattern okIf = Pattern.compile("if\\s*\\([^)]*\\b[A-Za-z_$][\\w$]*\\.ok\\b");
        for (int j = callLine; j >= 0 && callLine - j <= 60; j--) {
            if (isComment(lines, j)) continue;
            if (!okIf.matcher(lines.get(j)).find()) continue;

            // Brace-match forward from the `{` that opens this if, and look for return/throw at its
            // own depth. If the block leaves without one, control reaches the call and the guard is
            // decoration.
            int open = lines.get(j).indexOf('{');
            if (open < 0) continue;
            int depth = 0;
            for (int k = j; k < callLine; k++) {
                String line = lines.get(k);
                boolean isCommentLine = isComment(lines, k);
                for (int c = line.length() - 1; c >= 0; c--) {
                    char ch = line.charAt(c);
                    if (ch == '}') {
                        if (depth == 0) break;   // the if-block closed before the call
                        depth--;
                    } else if (ch == '{') {
                        depth++;
                    }
                }
                if (depth == 0) break;
                if (!isCommentLine && depth == 1
                        && Pattern.compile("\\b(return|throw)\\b").matcher(line).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("every loader checks the response before reading its body")
    void noUnguardedJsonCall() throws IOException {
        List<String> unguarded = new ArrayList<>();

        for (Path file : applicationJsFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!JSON_CALL.matcher(line).find()) continue;
                if (isComment(lines, i)) continue;
                // Comments are stripped before the scan for the same reason as everywhere else in this
                // repository: P0-RANK-4's guard failed on the javadoc explaining the very term it
                // banned, and the fix was to strip comments rather than delete the explanation.
                String context = stripComments(lines, i, GUARD_WINDOW);
                if (OK_CHECK.matcher(context).find()) continue;
                if (context.contains(".catch(")) continue;
                if (anySharedReader(context)) continue;
                if (insideATryBlock(lines, i)) continue;
                if (guardedByAnEarlierOkBlock(lines, i)) continue;
                if (isFatalByDesign(lines, i)) continue;

                unguarded.add(shortPath(file) + ":" + (i + 1) + "  " + line.strip());
            }
        }

        assertEquals(List.of(), unguarded,
                "these loaders read a JSON body with nothing checking the response first:\n  "
                        + String.join("\n  ", unguarded)
                        + "\n\nauthFetch throws on every non-2xx, so an unguarded .json() never reaches the "
                        + "guard written for it — the throw skips it and escapes to the router, which "
                        + "replaces the page with a two-word \"API Error\" card. Use readJsonOrThrow where "
                        + "the screen cannot be drawn without the payload, or readJsonOr where one panel "
                        + "failing must not empty the others.");
    }

    @Test
    @DisplayName("the two shared readers exist, so the rule is one import and not six copies")
    void theSharedReadersExist() throws IOException {
        String utils = Files.readString(
                Path.of("src/main/resources/static/js/pages/views/utils.js"), StandardCharsets.UTF_8);

        assertTrue(utils.contains("export async function readJsonOrThrow"),
                "readJsonOrThrow must exist in utils.js: without one shared place to put the guard, every "
                        + "loader grows its own and half of them forget");
        assertTrue(utils.contains("export async function readJsonOr"),
                "and readJsonOr for the tolerant half, because a missing country catalogue must not blank "
                        + "a transfer screen that already has its market rows");
    }

    @Test
    @DisplayName("the tolerant reader reports which panel went empty instead of failing silently")
    void theTolerantReaderSaysSomething() throws IOException {
        String utils = Files.readString(
                Path.of("src/main/resources/static/js/pages/views/utils.js"), StandardCharsets.UTF_8);

        int start = utils.indexOf("export async function readJsonOr(");
        assertTrue(start > 0, "readJsonOr must exist");
        String body = utils.substring(start, utils.indexOf("\n}", start));

        assertTrue(body.contains("console.warn"),
                "a panel that renders empty must say which one and why. A fallback returned without a word "
                        + "is indistinguishable from a competition that genuinely has nothing in it — and "
                        + "\"no milestones\" is a fact about the world, not a failed request.");
    }

    @Test
    @DisplayName("no debug logging is left beside a fetch")
    void noDebugLoggingLeftBehind() throws IOException {
        List<String> logged = new ArrayList<>();
        Pattern logLine = Pattern.compile("^\\s*console\\.log\\(");

        for (Path file : applicationJsFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (isComment(lines, i)) continue;
                if (!logLine.matcher(lines.get(i)).find()) continue;
                // The fetch is BELOW the log it belongs to - `console.log('Loading X')` sits on the line
                // above `authFetch`. The first version of this looked upwards, so it found the previous
                // statement, matched nothing, and passed against three planted logs. A window in the
                // wrong direction is not a narrow window; it is no window.
                String context = stripComments(lines, i, -4);
                if (context.contains("authFetch") || context.contains("fetch(")) {
                    logged.add(shortPath(file) + ":" + (i + 1) + "  " + lines.get(i).strip());
                }
            }
        }

        assertEquals(List.of(), logged,
                "debug logging beside a fetch:\n  " + String.join("\n  ", logged)
                        + "\n\nThese were the fingerprint of the defect: a \"Loading X\" and a \"Response "
                        + "status:\" pair is what an unguarded loader looked like while it was being "
                        + "written, and eleven of them were still in the tree.");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private List<Path> applicationJsFiles() throws IOException {
        try (Stream<Path> files = Files.walk(JS_ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".js"))
                    // The frozen demo engine and its viewer are reference assets, not product code.
                    .filter(p -> !p.toString().contains("/demo/"))
                    // tifo.js is the TEXT-football mode, a separate product opened from its own lobby
                    // card. It is not the graphical football UI this guard is about, and it has never
                    // been through this review. Excluded by name, with the reason, rather than by a
                    // wildcard that would hide the next file.
                    .filter(p -> !p.getFileName().toString().equals("tifo.js"))
                    .toList();
        }
    }

    private static String shortPath(Path p) {
        return Path.of("src/main/resources/static/js").relativize(p).toString();
    }

    private static boolean isComment(List<String> lines, int i) {
        String t = lines.get(i).trim();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
    }

    private static boolean anySharedReader(String context) {
        return SHARED_READERS.stream().anyMatch(context::contains);
    }

    /**
     * A window around a line, with comment lines removed. A negative {@code window} looks <b>down</b>.
     *
     * <p>The direction matters and getting it wrong is not a narrow window but no window at all, which is
     * how the debug-log guard passed against three planted logs.
     */
    private static String stripComments(List<String> lines, int i, int window) {
        StringBuilder sb = new StringBuilder();
        int from = window < 0 ? i : Math.max(0, i - window);
        int to = window < 0 ? Math.min(lines.size(), i - window) : i;
        for (int j = from; j <= to; j++) {
            String t = lines.get(j).trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            sb.append(lines.get(j)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Whether the call sits inside a {@code try} that is still open at that line.
     *
     * <p>Walks backwards tracking brace depth. The first block reached at depth zero is the enclosing
     * one; if it opened with {@code try}, the call is handled.
     */
    private static boolean insideATryBlock(List<String> lines, int i) {
        // A `try {` on the same line as the call: `try { return await r.json(); } catch { return null; }`
        // is one line long and the brace walker below starts to the right of it.
        String sameLine = lines.get(i).substring(0, lines.get(i).indexOf(".json()"));
        if (Pattern.compile("\\btry\\s*\\{").matcher(sameLine).find()) return true;

        int depth = 0;
        for (int j = i; j >= 0; j--) {
            String line = lines.get(j);
            for (int c = line.length() - 1; c >= 0; c--) {
                char ch = line.charAt(c);
                if (ch == '}') {
                    depth++;
                } else if (ch == '{') {
                    if (depth == 0) {
                        String before = line.substring(0, c);
                        return Pattern.compile("\\btry\\b").matcher(before).find();
                    }
                    depth--;
                }
            }
        }
        return false;
    }

    /**
     * Whether the call is one of the few where throwing is the correct answer.
     *
     * <p>{@code /auth/me} with no manager is not a panel that should render empty — there is no club, no
     * league and no country, so the application genuinely cannot continue. Refusing to fake one is
     * better than rendering a dashboard of zeroes.
     */
    private static boolean isFatalByDesign(List<String> lines, int i) {
        String window = String.join("\n", lines.subList(Math.max(0, i - 6), i + 1));
        return LEGITIMATELY_FATAL.stream().anyMatch(window::contains);
    }
}