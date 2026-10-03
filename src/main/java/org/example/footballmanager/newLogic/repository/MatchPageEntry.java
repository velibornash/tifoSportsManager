package org.example.footballmanager.newLogic.repository;

import java.time.LocalDateTime;

/**
 * One row of the recovery window's keyset page: which match, and when it was played.
 *
 * <p><b>Both halves are the cursor.</b> The page query walks the window in {@code (match_date, id)}
 * order, so resuming needs the last row's <i>date</i> as well as its id — keyset paging on the id alone
 * cannot use an index on the date, and measured 206 ms a page against 0.35 ms for the composite key on
 * an 89,280-match season.
 *
 * @param id        the match id, which is what the zone loads are then asked for by
 * @param matchDate the date the page resumes after; the window filter is strictly {@code > after}
 */
public record MatchPageEntry(Long id, LocalDateTime matchDate) {
}
