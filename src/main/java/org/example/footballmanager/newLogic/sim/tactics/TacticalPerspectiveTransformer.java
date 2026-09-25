package org.example.footballmanager.newLogic.sim.tactics;

import org.example.footballmanager.newLogic.sim.model.Position;

/**
 * Converts tactical-editor coordinates (HOME perspective) to physical coordinates.
 * HOME: direct. AWAY: mirror both axes — Position(9-row, 8-col).
 *
 * Field convention: HOME goal line at row 1.0, AWAY goal line at row 8.0, so the
 * field centre row is 4.5 and reflection over it maps row r -&gt; 9-r.
 *
 * The column axis is centred on 4.0, not 3.5: the touchlines are col 1.0 and
 * 7.0, the goal mouth is 3.5-4.5 and the penalty spots sit at 4.0, so 4.0 is the
 * only symmetry axis of the pitch and the mirror must be col c -&gt; 8-c. It used
 * to be 7-c (reflecting over 3.5), which pushed AWAY's right-sided players
 * (MR/DR at col 6.5) to col 0.5 — outside the touchline, where the field clamp
 * then pinned them 0.6 cells (8.4 m) inside the sideline, while HOME's stayed
 * 0.5 cells from it. The AWAY shape was therefore not the mirror of the HOME
 * shape in the corners.
 */
public final class TacticalPerspectiveTransformer {
    private TacticalPerspectiveTransformer() {}

    /** HOME-perspective editor position -&gt; physical field position for a team. */
    public static Position toPhysical(Position homePerspective, String team) {
        if (team == null || "HOME".equals(team)) return homePerspective;
        return new Position(9 - homePerspective.getRow(),
                8 - homePerspective.getColumn());
    }

    /** Physical field position -&gt; HOME-perspective editor position for a team. */
    public static Position toHomePerspective(Position physical, String team) {
        if (team == null || "HOME".equals(team)) return physical;
        return new Position(9 - physical.getRow(),
                8 - physical.getColumn());
    }
}