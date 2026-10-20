package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enforces the owner's standing rule from 2026-09-27: a value that exists must be wired to something
 * that reads it. In practice that rule kept failing for the <b>gate</b> rather than the value — the
 * behaviour was right, but each controller had open-coded the entitlement check instead of asking
 * {@link PlusFeatureService}, so the service's own methods sat there with no callers while the rule
 * was still being applied correctly. That is the failure mode this test exists to catch.
 *
 * <p>It is a source scan rather than a runtime assertion on purpose. A spy can prove a method was
 * called during one test; it cannot prove the method is reachable from the product at all. The defect
 * being guarded against is "nobody calls this", which is a question about the source tree.
 *
 * <p>Scope is deliberately narrow: <b>only</b> {@link PlusFeatureService}. A general "no unused public
 * method" test would fail on hundreds of framework and Spring Data methods, and a rule that cries wolf
 * gets deleted.
 */
class PlusGateHasCallersTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path GATE_FILE =
            SOURCE_ROOT.resolve("org/example/footballmanager/newLogic/service/PlusFeatureService.java");

    @Test
    @DisplayName("every public method on the PLUS gate is called from somewhere in the product")
    void everyGateMethodHasACaller() throws IOException {
        List<String> dead = new ArrayList<>();

        for (Method method : PlusFeatureService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            if (isCalledElsewhere(method.getName())) {
                continue;
            }
            dead.add(method.getName());
        }

        if (!dead.isEmpty()) {
            fail("These PlusFeatureService methods have no caller anywhere under src/main/java.\n"
                    + "Every gate method must be the path a screen actually uses — either wire it up, or\n"
                    + "delete it if the behaviour turned out to be unnecessary.\n"
                    + "\n"
                    + "A method with no callers usually means a controller re-derived the entitlement\n"
                    + "check by hand, which is how the rule ended up living in several places at once.\n"
                    + "\n"
                    + "Dead: " + String.join(", ", dead));
        }
    }

    /**
     * Whether {@code methodName} is genuinely called anywhere in the product — by another class, or by
     * another method of the gate itself, since a helper only the gate uses is still a live helper.
     *
     * <p>Comments are stripped first. Without that, a Javadoc {@code @link} or a passing mention in a
     * comment would read as a call site and let a dead method pass: the gate's own documentation
     * discusses most of its methods, which is precisely the case that fooled the first version of
     * this test into calling {@code isOwnPlayer} dead while {@code canSee} was calling it.
     */
    private boolean isCalledElsewhere(String methodName) throws IOException {
        String call = methodName + "(";
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(p -> p.equals(GATE_FILE)
                            ? withoutDeclaration(this.codeOf(p), methodName).contains(call)
                            : this.codeOf(p).contains(call));
        }
    }

    /**
     * Drops the gate's own declaration of {@code methodName}, so the signature does not count as a
     * call to itself.
     *
     * <p>The first version of this test had exactly that hole, and it made {@code hasPlusSubscription}
     * look wired when nothing called it: {@code public boolean hasPlusSubscription(User user) }
     * contains its own name followed by a bracket. A test that certifies dead code is worse than no
     * test, because it is taken as permission.
     */
    private String withoutDeclaration(String code, String methodName) {
        StringBuilder out = new StringBuilder(code.length());
        for (String line : code.split("\\R", -1)) {
            boolean isDeclaration = (line.contains("public ") || line.contains("private ")
                    || line.contains("protected "))
                    && line.matches(".*\\b" + java.util.regex.Pattern.quote(methodName) + "\\s*\\(.*");
            out.append(isDeclaration ? "\n" : line).append('\n');
        }
        return out.toString();
    }

    /** The file's source with comments blanked out, so only real call sites remain. */
    private String codeOf(Path file) {
        String src;
        try {
            src = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
                continue;
            }
            if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
                continue;
            }
            out.append(src.charAt(i));
            i++;
        }
        return out.toString();
    }
}
