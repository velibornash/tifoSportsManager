package org.example.footballmanager.demo.service.tactics;

import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.model.TeamSide;

/**
 * Converts tactical-editor coordinates (HOME perspective) to physical coordinates.
 * HOME: direct. AWAY: mirror both axes — Position(8-row, 7-col).
 */
public final class TacticalPerspectiveTransformer {
    private TacticalPerspectiveTransformer() {}

    public static Position toPhysical(Position homePerspective, TeamSide team) {
        if (team == TeamSide.HOME) return homePerspective;
        return new Position(8 - homePerspective.getRow(),
                7 - homePerspective.getColumn());
    }

    public static Position toHomePerspective(Position physical, TeamSide team) {
        if (team == TeamSide.HOME) return physical;
        return new Position(8 - physical.getRow(),
                7 - physical.getColumn());
    }

    public static Position toPhysical(Position homePerspective, String team) {
        return toPhysical(homePerspective, TeamSide.fromString(team));
    }

    public static Position toHomePerspective(Position physical, String team) {
        return toHomePerspective(physical, TeamSide.fromString(team));
    }
}
