package org.example.footballmanager.newLogic.sim.tactics;

import org.example.footballmanager.newLogic.sim.model.Position;

/**
 * The out-of-possession shape, derived rather than authored.
 *
 * <p>The tactical data ships two sets of rules, one for each possession context, and for a long time
 * <b>all 506 of them were identical</b>. The feature existed, the data was there, the possession
 * context was threaded all the way through the lookup, and the two contexts produced exactly the same
 * positions — so a team defending looked precisely like the same team attacking, and every match was
 * played in a single tactical phase. This is the fourth time in this sprint that something has been
 * fully built and quietly doing nothing.
 *
 * <p><b>Why derive it instead of hand-authoring 506 more rules.</b> Authoring them would have been
 * 506 arbitrary numbers, unverifiable one by one, free to drift back into agreement with the
 * attacking shape at the next edit, and impossible to review. Deriving it means the two shapes are
 * different <b>by construction</b> and cannot become identical by accident. It also means a manager's
 * attacking shape is the single thing to design, which is the honest way to express a tactical idea.
 *
 * <p>Three movements, in this order, because that is the order a team actually performs them:
 *
 * <ol>
 *   <li><b>Drop</b> — the block falls back toward its own goal, and the further forward a player was
 *       playing, the further he has to come. A striker at the top of the pitch does not walk a single
 *       step backwards; a full-back already at home barely moves.</li>
 *   <li><b>Compact</b> — the block narrows, so the gaps an attacker can aim at get smaller. This is
 *       what "closing down the space" means.</li>
 *   <li><b>Shift</b> — the block slides toward the ball, so the near side presses and the far side
 *       covers. Partial by design: a block that slid all the way would abandon the other side
 *       entirely, which is how teams concede from the far post.</li>
 * </ol>
 *
 * <p>All of it happens in <b>editor (HOME) perspective</b>, before the AWAY mirror is applied, so the
 * two teams get identical football from one piece of arithmetic.
 */
final class DefensiveShape {

    private DefensiveShape() {
    }

    /** How much of a player's distance from his own goal he gives back when defending. */
    private static final double DROP_SHARE = 0.16;

    /** How much of the block's width survives, the rest being taken in. */
    private static final double WIDTH_KEPT = 0.80;

    /** How far toward the ball the block slides, as a share of the ball's distance from the middle. */
    private static final double SHIFT_TOWARD_BALL = 0.18;

    /** Nobody defends from inside his own six-yard box, and nobody stands on the touchline. */
    private static final double MIN_ROW = 1.2;
    private static final double MIN_COL = 1.0;
    private static final double MAX_COL = 7.0;

    /** The middle of the pitch's width in editor perspective, from the usable cols 1-7. */
    private static final double CENTRE_COL = 4.0;

    private static final String GOALKEEPER = "GK";

    /**
     * Where this player should be when his team does not have the ball.
     *
     * @param attackingCell where the same player stands in possession — the shape being defended from
     * @param ball          the exact ball position, not its cell: the block's shift needs a
     *                      continuous column, and quantising it to a cell makes the whole defensive
     *                      line jump one step at a time
     * @param role           the player's role, so a goalkeeper can be left alone
     */
    static Position derive(Position attackingCell, Position ball, String role) {
        if (attackingCell == null) {
            return null;
        }
        // A goalkeeper's job out of possession is to hold his line, and the in-possession rule
        // already keeps him near his goal. Dropping him with the outfield would have a keeper
        // standing on the edge of his own six-yard box, which is a bug a screenshot would catch.
        if (GOALKEEPER.equals(role)) {
            // Clamped as well, even though the caller normally clamps. "He does not move" must not
            // become "he is not on the pitch": an outfield-derived clamp would still be correct, and
            // a pass-through that quietly returns an impossible position is a hole for whatever
            // calls this next.
            return new Position(
                    Math.max(MIN_ROW, Math.min(7.5, attackingCell.getRow())),
                    Math.max(MIN_COL, Math.min(MAX_COL, attackingCell.getColumn())));
        }

        double row = attackingCell.getRow();
        double col = attackingCell.getColumn();

        // 1. Drop toward our own goal, which is row 1 in editor perspective.
        row -= DROP_SHARE * (row - 1.0);

        // 2. Compact: take the block's width in around the middle.
        col = CENTRE_COL + (col - CENTRE_COL) * WIDTH_KEPT;

        // 3. Slide toward the ball, so the near side presses and the far side covers.
        if (ball != null) {
            col += SHIFT_TOWARD_BALL * (ball.getColumn() - CENTRE_COL);
        }

        return new Position(
                Math.max(MIN_ROW, Math.min(7.5, row)),
                Math.max(MIN_COL, Math.min(MAX_COL, col)));
    }

    /** Whether two positions are close enough that treating them as the same shape is reasonable. */
    static boolean sameShape(Position a, Position b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Math.abs(a.getRow() - b.getRow()) < 0.01
                && Math.abs(a.getColumn() - b.getColumn()) < 0.01;
    }
}
