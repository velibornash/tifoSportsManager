package org.tifo.footballmanager.newLogic.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dead code in the sim package stays dead.
 *
 * <p><b>What was here (found 2026-10-19, E-8).</b> Three things in the engine had no caller at all:
 * a helper whose rule had been removed, a method that did a job another component owns, and a
 * constant for a movement nobody makes.
 *
 * <p>Dead code is not a style problem. It is a <b>lie about the engine</b>: a reader who finds
 * {@code bestDelivery} reads its javadoc — *"(used by the final-row rule)"* — and concludes there is a
 * final-row rule. There is not. It was removed on 2026-10-18, and the reason it was removed is
 * written in the class it lived in, 300 lines away.
 *
 * <p>The same is true of the other two. {@code findSafePosition} read as an obstacle-avoidance step,
 * and collision is the ball engine's job. {@code IDLE_DRIFT_SPEED} read as a movement that exists.
 * Neither does.
 *
 * <p>So the guard is a structural scan: the names must not come back. It cannot check that the whole
 * package is free of dead code — that is not a thing a test can do without a compiler — but it can
 * hold the three that were found, and each of them comes back with a rule attached, which is exactly
 * how the first one arrived.
 */
class SimPackageHasNoDeadCodeTest {

    private static final Path SIM = Path.of("src/main/java/org/example/footballmanager/newLogic/sim");

    @Test
    @DisplayName("the helper whose rule was removed does not come back")
    void theRemovedDeliveryHelperIsGone() throws IOException {
        assertNotPresent("bestDelivery",
                "it existed to serve the final-row rule, which was removed on 2026-10-18 as dead code "
                        + "itself. A reader finds the helper, reads '(used by the final-row rule)' and "
                        + "believes there is a final-row rule. There is not.");
    }

    @Test
    @DisplayName("the obstacle-avoidance step the ball engine owns does not come back")
    void theRemovedSafePositionIsGone() throws IOException {
        assertNotPresent("findSafePosition",
                "collision is the ball engine's job - the method's own javadoc said so while it sat "
                        + "there unused. A public method with no caller is an API nobody can use and "
                        + "everybody has to read.");
    }

    @Test
    @DisplayName("the idle-drift constant does not come back")
    void theRemovedIdleDriftIsGone() throws IOException {
        assertNotPresent("IDLE_DRIFT_SPEED",
                "players do not drift toward the ball - they are pace-capped and they walk to their "
                        + "tactical target. The constant describes a movement the engine does not make.");
    }

    private void assertNotPresent(String name, String why) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SIM)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // Whole words only: "bestDeliveryOption" is a different name, and the scan is
                // about the helper, not about every string that starts with it.
                if (Pattern.compile("\\b" + name + "\\b").matcher(source).find()) {
                    offenders.add(SIM.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                name + " is back in " + offenders + " - " + why);
    }
}
