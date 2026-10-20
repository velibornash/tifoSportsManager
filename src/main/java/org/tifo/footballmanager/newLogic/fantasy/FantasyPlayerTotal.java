package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;

/**
 * One player's fantasy total for a season, for the player-points table (owner, 2026-10-17).
 *
 * <p>A row of the read model rather than an entity: it is a sum across gameweeks, and there is nothing to
 * store. Carries the club and the tier so the table can be read without a second lookup per row — the whole
 * point of the view is to show where the points are coming from.
 */
public record FantasyPlayerTotal(String name,
                                 String clubName,
                                 Position position,
                                 Integer tier,
                                 Long points) {
}
