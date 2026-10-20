package org.example.footballmanager.newLogic.fantasy;

import org.example.footballmanager.newLogic.model.Position;

/**
 * One raw row of a country's fantasy pool, straight out of the database.
 *
 * <p>Deliberately flat: the id, the name, the rating the nudge ranks on, the position, the tier and the
 * club. Everything the price needs and nothing else, so the pool query stays one statement instead of
 * walking a player to his club to his competition to his country row by row.
 */
public record FantasyPoolRow(Long playerId,
                             String name,
                             int rating,
                             Position position,
                             Integer tier,
                             Long clubId,
                             String clubName) {
}
