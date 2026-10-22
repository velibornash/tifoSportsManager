package org.tifo.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.util.DisplayNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three one-line rules that were written eleven, three and two times.
 *
 * <p>None of them is subtle, which is exactly why they were copied: a display name, a formatted date and
 * eight fields of a prediction payload. The copies drifted in the small ways copies do — four of the
 * eleven name fallbacks trimmed and seven did not — and the drift showed up as a manager being shown by
 * his email address next to his own post while his name appeared on the squad page.
 *
 * <p><b>What is checked.</b> The rules themselves ({@link DisplayNames}) by behaviour, and the absence of
 * their old shapes by source. A shape guard and a unit test, because "the copies are gone" and "the rule
 * is right" are two different claims.
 */
class PresentationRulesAreDeclaredOnceTest {

    @Test
    @DisplayName("a manager is shown by his chosen name, trimmed, or by his login")
    void theDisplayNameRule() {
        User user = new User();
        user.setUsername("kecko");

        assertNull(DisplayNames.of(null), "no user, no name - and no exception while building a page");

        assertFalse(DisplayNames.hasChosen(user));
        assertEquals("kecko", DisplayNames.of(user), "a manager who never chose a name is shown by his login");

        user.setDisplayName("   ");
        assertEquals("kecko", DisplayNames.of(user), "a blank name is not a name");
        assertFalse(DisplayNames.hasChosen(user));

        user.setDisplayName("  Velibor  ");
        assertEquals("Velibor", DisplayNames.of(user), "the chosen name wins, trimmed - it used to be trimmed "
                + "on some screens and not on others");
        assertTrue(DisplayNames.hasChosen(user));
    }

    @Test
    @DisplayName("no file builds the name fallback, the date string or the prediction payload again")
    void theCopiesAreGone() throws IOException {
        List<String> nameFallbacks = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        List<String> predictions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ProductionSource.root())) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                String where = ProductionSource.root().relativize(file).toString();
                for (String line : Files.readAllLines(file)) {
                    // A comment that names the old shape is documentation, not a copy of it.
                    String stripped = line.strip();
                    if (stripped.startsWith("*") || stripped.startsWith("/*") || stripped.startsWith("//")) {
                        continue;
                    }
                    if (line.contains("getDisplayName() :") || line.contains("? user.getDisplayName()")
                            || line.contains("return user.getDisplayName().trim();")) {
                        nameFallbacks.add(where);
                    }
                    if (line.contains("substring(0, 16)") && !where.endsWith("DateTimes.java")) {
                        dates.add(where);
                    }
                    if (line.contains("toPredictionMap")) {
                        predictions.add(where);
                    }
                }
            }
        }
        assertEquals(List.of(), nameFallbacks,
                "the display-name fallback was written out once per screen. Call DisplayNames.of(user) - "
                        + "the copies disagreed about trimming, which is why one page showed an email "
                        + "address where another showed a name");
        assertEquals(List.of(), dates,
                "the page's date string was built by hand. Call DateTimes.format(dateTime) - "
                        + "DateTimes is the one place allowed to truncate an ISO timestamp at the minute");
        assertEquals(List.of(), predictions,
                "the prediction payload was built twice, byte for byte. Call "
                        + "PredictionPayload.toMap(prediction) - two builders for one card are two "
                        + "answers waiting to differ");
    }
}
