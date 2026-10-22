/**
 * The order the squad is listed in — one map, one rule.
 *
 * <p>Seven copies of `{ GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 }` lived in four files: four in
 * `tifo.js`, one in `training-view.js`, one in `match-view.js`, and a seventh named `ROLE_ORDER` in
 * `lineup-board.js`. They also disagreed about what to do with a position the map does not hold —
 * `?? 5` in five places, `?? 9` in two — which is the kind of difference nobody notices until a player
 * with a new position is listed in the wrong half of a squad.
 *
 * <p>A goalkeeper is first and a forward is last because that is how a team sheet reads, not because
 * the engine ranks them. Nothing here is a strength: it is a display order.
 */

/** The display rank of each position. Unknown positions sort after every known one. */
export const POSITION_ORDER = Object.freeze({ GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 });

/**
 * The display rank of a position, case-insensitively.
 *
 * @param {string} position
 * @param {number} unknown the rank given to a position the map does not hold; callers that append
 *        other rows to the list pass a larger number so those rows land at the end
 */
export function positionRank(position, unknown = 5) {
    return POSITION_ORDER[String(position ?? '').toUpperCase()] ?? unknown;
}

/**
 * A comparator for `Array.prototype.sort` that orders by position, then leaves the order alone.
 *
 * @param {number} unknown see {@link positionRank}
 */
export function byPosition(unknown = 5) {
    return (a, b) => positionRank(a?.position, unknown) - positionRank(b?.position, unknown);
}
