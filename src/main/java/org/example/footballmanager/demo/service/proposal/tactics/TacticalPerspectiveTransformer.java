package org.example.footballmanager.demo.service.proposal.tactics;

import org.example.footballmanager.demo.service.proposal.model.Position;

/**
 * Converts tactical-editor coordinates (HOME perspective) to physical coordinates.
 * HOME: direct. AWAY: mirror both axes — Position(9-row, 7-col).
 *
 * Field convention: HOME goal line at row 1.0, AWAY goal line at row 8.0,
 * so the field centre row is 4.5 and reflection over it maps row r -> 9-r.
 * Columns reflect over col 3.5 (goal-mouth centre): col c -> 7-c.
 */
public final class TacticalPerspectiveTransformer {
    private TacticalPerspectiveTransformer() {}

    /** HOME-perspective editor position -> physical field position for a team. */
    public static Position toPhysical(Position homePerspective, String team) {
        if (team == null || "HOME".equals(team)) return homePerspective;
        return new Position(9 - homePerspective.getRow(),
                7 - homePerspective.getColumn());
    }

    /** Physical field position -> HOME-perspective editor position for a team. */
    public static Position toHomePerspective(Position physical, String team) {
        if (team == null || "HOME".equals(team)) return physical;
        return new Position(9 - physical.getRow(),
                7 - physical.getColumn());
    }
}