package org.tifo.footballmanager.newLogic.model;

import org.tifo.footballmanager.ProductionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "clock row is missing" fallback is <b>now</b>, not a calendar date.
 *
 * <p>Five classes answered "when does the season start" with their own copy of
 * {@code LocalDateTime.of(2026, 7, 1, 12, 0)}. A season is twelve weeks counted from 1 and there is no
 * calendar year in this world, so that literal was never the start of a season: it was the day the
 * repository happened to be written. Once real time passed it, a world whose clock row was still being
 * created stamped its fixtures into the past.
 *
 * <p>The first test measures the answer; the second holds the shape — one copy of the rule, in
 * {@link GameTimeline}, and no fresh calendar-year literal anywhere in the production tree.
 */
class SeasonStartFallbackTest {

    @Test
    @DisplayName("the fallback is today, not 2026-07-01")
    void theFallbackIsToday() {
        LocalDate today = LocalDate.now(GameTimeline.GAME_ZONE);
        assertEquals(today, GameTimeline.seasonStartFallback().toLocalDate(),
                "the fallback must be the game's today: a new world starts at season 1 week 1 day 1, "
                        + "so its anchor is now, which is the same rule SeasonService.getOrCreateClock applies");
        assertEquals(0, GameTimeline.seasonStartFallback().getSecond(),
                "the fallback drops seconds, like the anchor the clock writes");
    }

    @Test
    @DisplayName("no production source carries a calendar-year season-start literal")
    void noCalendarYearLiterals() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(ProductionSource.root())) {
            offenders = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(SeasonStartFallbackTest::looksLikeACalendarYearSeasonStart)
                    .map(Path::toString)
                    .toList();
        }
        assertTrue(offenders.isEmpty(),
                "a calendar-year season-start literal is back in the production tree: " + offenders
                        + ". A season is twelve weeks counted from 1 — use GameTimeline.seasonStartFallback()");
    }

    private static boolean looksLikeACalendarYearSeasonStart(Path file) {
        try {
            return Files.readString(file).contains("LocalDateTime.of(2026, 7, 1");
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }
}
