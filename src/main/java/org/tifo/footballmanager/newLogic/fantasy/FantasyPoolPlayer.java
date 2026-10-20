package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;

/**
 * A player as the fantasy game sees him: what he is, what he costs, and where he plays.
 *
 * <p>{@link #position} is the real one, {@link #fantasyPosition} is the one he is priced and counted as —
 * a winger is a midfielder. Both are carried, because the picker shows the real position and the rules
 * count the fantasy one, and collapsing them into one field is how a winger ends up in an attacker's slot.
 *
 * <p>{@link #points} is what he has scored in fantasy so far this season, and it is zero until a gameweek is
 * scored. Carried on the pool row rather than fetched separately so the picker can sort by it without a
 * second read and a client-side join.
 *
 * <p>{@link #price} is not stored anywhere. It is derived from the tier, the position and the player's
 * rank inside his own band every time the pool is built, so a rating that moves moves the price with it
 * and there is no second copy of the truth to keep in step.
 */
public record FantasyPoolPlayer(long playerId,
                                String name,
                                Position position,
                                Position fantasyPosition,
                                int rating,
                                int tier,
                                long clubId,
                                String clubName,
                                double price,
                                int points) {
}
