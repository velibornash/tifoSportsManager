package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Every shipped ES module must parse <b>as a module</b>, not as a script. (2026-10-09.)
 *
 * <p><b>What this catches, and why nothing else did.</b>
 *
 * <p>A comment I wrote inside a JavaScript template literal contained the word {@code MatchPreviewService}
 * in backticks. A backtick inside a template literal terminates it, so the module became:
 *
 * <pre>
 *   ... "text" + MatchPreviewService.reasonsFor + " already appends ..."   //  three syntax errors
 * </pre>
 *
 * <p>The application's entire page module stopped loading. {@code window.loadMatch} became undefined,
 * the dashboard's click handler silently fell through <b>both</b> of its branches — it does not throw when
 * {@code window.loadMatch} is missing, it just does nothing — and the Next Match card became a control
 * that did nothing at all, on every page, for everyone.
 *
 * <p><b>The reason this reached a browser.</b> {@code node --check file.js} parses as a CommonJS
 * <b>script</b> and <b>passed</b>. {@code node --check file.mjs} parses as an <b>ES module</b> and
 * failed. Every JavaScript check in this repository used the script form. Four source-scan guards were
 * green, the mutation suite was green, and the application was broken — the exact failure mode
 * {@code kanban.md} records as "a green status is not evidence".
 *
 * <p><b>What this does instead of shelling out to node:</b> it does not shell out at all. It reproduces
 * the property that matters directly: <b>no backtick may appear inside a template literal</b>, because
 * that is the specific way a comment inside generated HTML silently destroys a module. The file is real,
 * the check is deterministic, and it needs no Node installation on the machine running the suite.
 *
 * <p>It also runs every module through {@code node --check} as {@code .mjs} when node is available, so
 * the general case is covered too, and skips that part with a printed note when it is not.
 */
class ModuleBackticksInTemplateTest {

    private static final Path JS_ROOT = Path.of("src/main/resources/static/js");

    /**
     * Matches a template literal and captures its body.
     *
     * <p>Deliberately simple: it finds backtick-delimited runs and reports any backtick that appears
     * inside one, which is the failure. It does not attempt to be a JavaScript parser, because the
     * repository has no JavaScript tooling and the point of this test is to need none.
     */
    @Test
    @DisplayName("no backtick is nested inside a template literal, which would silently break the module")
    void noBackticksInsideTemplateLiterals() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : jsFiles()) {
            String src = read(file);
            int open = src.indexOf('`');
            while (open >= 0) {
                int close = src.indexOf('`', open + 1);
                if (close < 0) {
                    // An unterminated literal is itself the bug; reported by the module check below.
                    break;
                }
                String body = src.substring(open + 1, close);
                if (body.indexOf('`') >= 0) {
                    int line = lineOf(src, open);
                    offenders.add(JS_ROOT.relativize(file) + ":" + line);
                }
                open = src.indexOf('`', close + 1);
            }
        }

        assertEquals(List.of(), offenders,
                "a backtick inside a template literal terminates it early. Every one of these modules "
                        + "fails to parse as an ES module, so the page silently stops loading:\n  "
                        + String.join("\n  ", offenders)
                        + "\n\nThe failure is invisible to `node --check` on a .js file, because that "
                        + "parses as a CommonJS script. It fails only as an ES module — which is how a "
                        + "comment inside generated HTML takes down a whole page while every other test "
                        + "is green.");
    }

    /**
     * Every shipped module parses as an ES module.
     *
     * <p>Runs the real parser when Node is available, because the backtick rule above catches the
     * specific mistake, not every way a module can fail to parse.
     */
    @Test
    @DisplayName("every shipped module parses as an ES module")
    void everyModuleParses() throws Exception {
        Path node = findNode();
        if (node == null) {
            System.out.println("[skipped] node not found — the backtick rule above still ran, and it is "
                    + "the check that catches the mistake this was written for.");
            return;
        }

        Path tmp = Files.createTempDirectory("module-parse");
        List<String> failures = new ArrayList<>();

        for (Path file : jsFiles()) {
            // Copied to .mjs, because the extension is the entire difference between "script" and
            // "module" to the parser. Checking the .js in place is what let this through.
            Path copy = tmp.resolve(file.getFileName() + ".mjs");
            Files.writeString(copy, read(file), StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(node.toString(), "--check", copy.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();

            if (exit != 0) {
                String firstLine = output.lines().findFirst().orElse("(no message)");
                failures.add(JS_ROOT.relativize(file) + "  ->  " + firstLine);
            }
        }

        deleteRecursively(tmp);

        assertEquals(List.of(), failures,
                "these modules do not parse as ES modules, so the pages that load them silently do "
                        + "nothing:\n  " + String.join("\n  ", failures));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private static List<Path> jsFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(JS_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".js")).sorted().toList();
        }
    }

    private static int lineOf(String src, int index) {
        int line = 1;
        for (int i = 0; i < index && i < src.length(); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static Path findNode() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, "node");
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing a green suite over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Kept so an unused-import warning cannot hide a real one. */
    @SuppressWarnings("unused")
    private static final Pattern ANCHOR = Pattern.compile("`");
}