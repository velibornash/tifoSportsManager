package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3: daily recovery must not walk every player who ever played.
 *
 * <p>{@code applyDailyRecovery} builds a map of who worked in the recovery window, then asked
 * {@code findByLastPlayedAtIsNotNull()} — every player who has ever played — and skipped everyone not
 * in the map. The map is built on the line above the loop, so the loop already knew exactly who it
 * could touch: the load was every player in the world, once a game day, to recover the few thousand who
 * actually turned up.
 *
 * <p>It is also the whole of what the daily job's cost used to be. The board records the job logging
 * *"7408 player(s) recovered… 42 minutes"*, and the 42 minutes were mostly this loop.
 *
 * <h2>Why a source scan and not a statement count</h2>
 *
 * <p>Both instruments were measured on this task and neither could see it — a whole-table load and a
 * by-id load each issue exactly one query, and Hibernate's entity counter reads zero inside a
 * {@code @Transactional} test because the rows are already managed. A scan asks the question directly
 * and cannot be disarmed by a statistics switch.
 *
 * <p>Matched on the <b>declaration</b>, not the bare method name: the first version of the scan found
 * the call site in a neighbouring method, balanced braces from whatever came next, read a block that
 * belonged to something else, and passed against the very code it was written to catch.
 *
 * <p>The behavioural half is {@code ZoneLoadRecoveryPersistenceTest}, which clears the persistence
 * context and re-reads morale from the database — so it fails if recovery stops being written, and it
 * is the only version of that assertion that can.
 */
class DailyRecoveryScopeTest extends BaseTest {

    private static final Path ZONE_LOAD_SERVICE =
            Path.of("src/main/java/org/example/footballmanager/newLogic/service/ZoneLoadService.java");

    @Test
    @DisplayName("daily recovery no longer walks every player who ever played")
    void dailyRecoveryDoesNotWalkTheWholePlayerTable() throws IOException {
        String code = stripComments(Files.readString(ZONE_LOAD_SERVICE, StandardCharsets.UTF_8));
        String body = methodBody(code, "applyDailyRecovery");

        assertTrue(body != null,
                "applyDailyRecovery is not in ZoneLoadService any more. If it was renamed or moved this "
                        + "test is measuring nothing — check where daily recovery lives before removing it.");

        Matcher wholeTable = Pattern
                .compile("players\\s*\\.\\s*findByLastPlayedAtIsNotNull\\s*\\(\\s*\\)")
                .matcher(body);
        assertTrue(!wholeTable.find(),
                "applyDailyRecovery calls findByLastPlayedAtIsNotNull() again — every player who has ever "
                        + "played, once a game day, to recover the few who turned up. The map of who worked "
                        + "is built on the line above, so the loop can be handed exactly that population: "
                        + "findByIdInAndLastPlayedAtIsNotNull(workedSinceWindow.keySet()).");

        Matcher byId = Pattern
                .compile("findByIdInAndLastPlayedAtIsNotNull\\s*\\(\\s*workedSinceWindow\\.keySet\\s*\\(\\s*\\)")
                .matcher(body);
        assertTrue(byId.find(),
                "applyDailyRecovery no longer asks for its population by id. Which query replaced the whole "
                        + "table matters more than that it did: anything that is not the recovery window's own "
                        + "population is the same defect wearing a different name.");
    }

    /**
     * The window is read as a projection, not as entities.
     *
     * <p><b>This assertion exists because {@link ZoneLoadProjectionTest} could not catch its own
     * wiring.</b> That file proves the projection is arithmetically correct and that the repository
     * method returns rows — and reverting {@code applyDailyRecovery} to the entity query left it
     * <b>2/2 green</b>, because it never looks at the service. A guard that cannot fail is worse
     * than none, so the wiring is checked here where the other half of this method is.
     *
     * <p>The cost it protects against is measured: on a real matchday (155 matches, 18,853 zone-load
     * rows) the entity query with its {@code JOIN FETCH} of the match took <b>21.0 ms</b> against the
     * projection's <b>10.8 ms</b>, and materialised a managed entity per row for an arithmetic sum over
     * four numbers.
     */
    @Test
    @DisplayName("the recovery window is read as a projection, not as entities")
    void theRecoveryWindowIsReadAsAProjection() throws IOException {
        String code = stripComments(Files.readString(ZONE_LOAD_SERVICE, StandardCharsets.UTF_8));
        String body = methodBody(code, "applyDailyRecovery");

        assertTrue(body != null, "applyDailyRecovery is not in ZoneLoadService any more.");

        Matcher entities = Pattern.compile("loads\\s*\\.\\s*findLoadsPlayedSince\\s*\\(").matcher(body);
        assertTrue(!entities.find(),
                "applyDailyRecovery reads the window as entities again. That loads a managed "
                        + "PlayerZoneLoad per row plus every column of the joined match, for a sum over four "
                        + "numbers — measured at 21.0 ms against 10.8 ms on a real matchday's 18,853 rows. "
                        + "findLoadMinutesPlayedSince returns exactly what the arithmetic reads.");

        Matcher projection = Pattern
                .compile("loads\\s*\\.\\s*findLoadMinutesPlayedSince\\s*\\(")
                .matcher(body);
        assertTrue(projection.find(),
                "applyDailyRecovery no longer reads the window through the projection.");
    }

    @Test
    @DisplayName("the window is measured on the game clock, not the wall clock")
    void theWindowIsMeasuredOnTheGameClock() throws IOException {
        // The other half of this method, and the one that is a correctness bug rather than a slow query.
        // Recovery means "how much did he play yesterday", and yesterday is a fact about the calendar the
        // manager is living in. Measured against the wall clock, a manager who plays a twelve-week season
        // in an evening has every match he has ever played inside a two-day window, so the question
        // "what did he do in the last two days" answers with the entire season.
        String code = stripComments(Files.readString(ZONE_LOAD_SERVICE, StandardCharsets.UTF_8));
        String body = methodBody(code, "applyDailyRecovery");

        assertTrue(body != null, "applyDailyRecovery is not in ZoneLoadService any more.");
        assertTrue(body.contains("inGameNow()"),
                "applyDailyRecovery no longer reads the game clock for its window. LocalDateTime.now() here "
                        + "means the recovery window is two real days, which for a manager playing a season in "
                        + "an evening is the whole season.");
    }

    // --- helpers, matching the pattern the other scope guards use ---

    /** The text of one method's body, braces balanced, or null when there is no such method. */
    private String methodBody(String code, String method) {
        Matcher declaration = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(\\s*\\)\\s*\\{").matcher(code);
        if (!declaration.find()) {
            return null;
        }
        int open = code.indexOf('{', declaration.start());
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i);
                }
            }
        }
        return null;
    }

    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
