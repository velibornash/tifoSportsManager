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
 * No {@code @Transactional} on a method Spring's proxy cannot see.
 *
 * <p><b>Why this exists.</b> {@code @Transactional} is applied by a proxy, and the proxy only wraps
 * calls that <em>go through it</em>. A {@code private} method, and a {@code protected} method called
 * from inside its own class, are invoked on {@code this} — the proxy is never consulted, and the
 * annotation is inert. The method still runs, still writes, and still commits, because Spring Data's
 * repositories open their own transactions; it simply does not run inside the transaction the annotation
 * claims.
 *
 * <p><b>That is not a style note, and it has already cost a defect.</b> On 2026-10-19 a rewind that
 * the API refused — "400 Season counts from 1, was 0" — had already moved the clock by exactly the number
 * of seconds asked for, leaving the world before the season it was in. The cause was the ordering: the
 * write committed and the check that should have come first threw afterwards, and the
 * {@code @Transactional} on the method was what made the code <em>look</em> like it could be undone.
 *
 * <p><b>So the annotation is the trap, and the rule is that it does not stay.</b> A method that genuinely
 * needs its own transaction must be reachable through the proxy — public, or called from another bean —
 * or must use a {@code TransactionTemplate}, which is the pattern {@code JobRunner} already uses for
 * exactly this reason.
 *
 * <p><b>Why a scan and not a runtime check.</b> There is no runtime symptom: the method works, it just
 * does not work the way it says. The only way to see it is to read the modifier and the annotation
 * together, which is what this does — over the whole of {@code src/main/java}.
 */
class TransactionalOnPrivateOrProtectedTest {

    private static final Path JAVA = Path.of("src/main/java/org/example/footballmanager");

    @Test
    @DisplayName("no @Transactional sits on a private or protected method")
    void noTransactionalOnAnInvisibleMethod() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String hit : transactionalOnPrivateOrProtected(
                        Files.readString(file, StandardCharsets.UTF_8))) {
                    offenders.add(JAVA.relativize(file) + ": " + hit);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "these methods carry a @Transactional that cannot apply:\n"
                        + String.join("\n", offenders)
                        + "\n\nSpring's proxy never sees a private method, or a protected one called from "
                        + "inside its own class, so the annotation is inert: the method still writes and "
                        + "still commits, and the next reader is told the work can be undone. Make the "
                        + "method reachable through the proxy, or use a TransactionTemplate.");
    }

    @Test
    @DisplayName("the scan finds the shape it is guarding")
    void theScanFindsTheShape() {
        assertTrue(transactionalOnPrivateOrProtected(
                        "class A {\n    @Transactional\n    private void hidden() { } }").size() == 1,
                "the scan missed a private method carrying the annotation");
        assertTrue(transactionalOnPrivateOrProtected(
                        "class A {\n    @Transactional\n    protected void hidden() { } }").size() == 1,
                "the scan missed a protected method carrying the annotation");
        assertTrue(transactionalOnPrivateOrProtected(
                        "class A {\n    @Transactional\n    public void visible() { } }").isEmpty(),
                "the scan flagged a public method, which Spring's proxy does see");
        assertTrue(transactionalOnPrivateOrProtected(
                        "class A {\n    @Transactional(readOnly = true)\n    private void hidden() { } }").size() == 1,
                "the scan missed the annotation when it carries arguments");
        assertTrue(transactionalOnPrivateOrProtected(
                        "class A {\n    @Transactional\n    @Override\n    private void hidden() { } }").size() == 1,
                "the scan missed the annotation when another annotation follows it");
    }

    /**
     * Every {@code @Transactional} that sits on a private or protected method.
     *
     * <p>The annotation and the modifier have to be adjacent enough to belong to the same method: the
     * scan takes the next declaration line after the annotation and looks at its visibility.
     */
    private List<String> transactionalOnPrivateOrProtected(String source) {
        List<String> hits = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("@Transactional")) {
                continue;
            }
            // Skip any further annotations (or javadoc-free modifiers) to find the declaration.
            String declaration = null;
            for (int j = i + 1; j < lines.length && j <= i + 4; j++) {
                String candidate = lines[j].trim();
                if (candidate.isEmpty() || candidate.startsWith("@") || candidate.startsWith("//")) {
                    continue;
                }
                declaration = candidate;
                break;
            }
            if (declaration == null) {
                continue;
            }
            if (declaration.startsWith("private ") || declaration.startsWith("protected ")) {
                hits.add(declaration.length() > 80 ? declaration.substring(0, 80) : declaration);
            }
        }
        return hits;
    }
}
