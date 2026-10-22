package org.tifo.footballmanager.newLogic.util;

import java.time.LocalDateTime;

/**
 * One rendering of a date-time for the pages: {@code 2026-02-20 12:00}, and {@code N/A} for nothing.
 *
 * <p>The expression was written three times — a private helper in {@code TeamController}, an inline copy in
 * {@code CountryController} and another in {@code MatchDTO.from} — and the three agreed only because they
 * were copies of each other. The column is a page's date and time; the rule for it is one place.
 *
 * <p>Sixteen characters and the {@code T} replaced by a space is ISO-8601 truncated at the minute, so it
 * sorts as a string as well as it reads. {@code N/A} rather than an empty cell because a fixture that has
 * no date yet is a fact about the fixture, not a missing value to be rendered as blank.
 */
public final class DateTimes {

    private DateTimes() {
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime != null
                ? dateTime.toString().substring(0, 16).replace("T", " ")
                : "N/A";
    }
}
