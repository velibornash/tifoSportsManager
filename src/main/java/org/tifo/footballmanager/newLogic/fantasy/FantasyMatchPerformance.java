package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;

/**
 * What one player did in one match, flattened so the points rules are a pure function.
 *
 * <p>Not the entity and not the DTO. The rules need seven numbers and a position, and taking the entity
 * would drag a match, a club and a competition into a class whose whole job is arithmetic.
 *
 * @param conceded goals his side let in. Needed for the clean sheet, and not derivable from the stat row:
 *        {@code MatchPlayerStats.cleanSheet} is set for goalkeepers and defenders only, so a midfielder's
 *        clean sheet has to be worked out from the scoreline.
 * @param bonusPoints 3, 2, 1 or 0, worked out by the caller from the whole match's ratings.
 */
public record FantasyMatchPerformance(Position position,
                                      int minutesPlayed,
                                      int goals,
                                      int assists,
                                      int yellowCards,
                                      int redCards,
                                      int saves,
                                      int conceded,
                                      int rating,
                                      int bonusPoints) {
}
