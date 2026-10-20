package org.example.footballmanager.newLogic.util;

import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a caller-supplied sort name and direction into a {@link Sort}, or refuses.
 *
 * <p><b>Why this exists.</b> Four controllers took a {@code sortBy} request parameter straight into
 * {@code Sort.by(sortBy)}. That hands the caller the column name, which means:
 *
 * <ul>
 *   <li>they can order by a column the page never intended to expose and never knew existed;</li>
 *   <li>they can order by a column with no index, so a two-character request turns into a full sort of the
 *       whole table — on a world with 14,880 clubs and every player of every club in every country;</li>
 *   <li>a name that is not a property fails deep inside Hibernate, where the message names the entity and its
 *       columns, which is a schema description handed to whoever asked.</li>
 * </ul>
 *
 * <p>So the name must be in an allow-list, and an unknown one is a **400** naming the allowed columns rather
 * than a 500 quoting the schema.
 */
public final class SortWhitelist {

    private SortWhitelist() {
    }

    /**
     * A sort for the given column if it is allowed, else a 400.
     *
     * @param allowed the column names this endpoint permits
     * @throws IllegalArgumentException when the column is not on the list
     */
    public static Sort of(String sortBy, String direction, String parameterName, Set<String> allowed) {
        String column = sortBy == null ? "" : sortBy.trim();
        String normalised = allowed.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(column))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sort column '" + column + "'. Allowed: " + String.join(", ", allowed) + "."));

        boolean descending = direction != null && "desc".equalsIgnoreCase(direction.trim());
        return descending ? Sort.by(normalised).descending() : Sort.by(normalised).ascending();
    }
}
