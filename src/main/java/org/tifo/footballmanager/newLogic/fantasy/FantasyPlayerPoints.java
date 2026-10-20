package org.tifo.footballmanager.newLogic.fantasy;

/**
 * A player's fantasy points for a season (owner, 2026-10-18).
 *
 * <p>What the picker's Points column shows. A projection rather than an entity: it is a sum across
 * gameweeks and there is nothing to store.
 */
public record FantasyPlayerPoints(Long playerId, Long points) {
}
