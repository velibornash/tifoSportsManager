package org.example.footballmanager.demo.service.tactics;

import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.model.TeamSide;

/**
 * Converts tactical-editor coordinates (HOME perspective) to physical coordinates.
 * HOME: direct. AWAY: mirror both axes — Position(9-row, 7-col).
 *
 * Field convention (rows 1-7, cols 1-6): red 1 = [1.0,2.0) center 1.5 … red 7
 * = [7.0,8.0) center 7.5; only rows 0/8 and cols 0/7 are out of bounds. A cell
 * CENTRE must mirror to a cell CENTRE of the opposing half. Reflecting over the
 * field centre row (4.5) gives 7.5 <-> 1.5, i.e. 9-row; columns reflect over
 * col 3.5 giving 7-col.
 */
public final class TacticalPerspectiveTransformer {
    private TacticalPerspectiveTransformer() {}

    public static Position toPhysical(Position homePerspective, TeamSide team) {
        if (team == TeamSide.HOME) return homePerspective;
        // AWAY: mirror both axes — Position(9-row, 7-col).
        // Field convention (rows 1-7, cols 1-6):
        //   HOME: row 1 (goal), 7 (attacking side)
        //   AWAY: row 8 (goal), 2 (attacking side) mirrored via 9-row
        //   col 1 left, 6 right mirrored via 7-col
        return new Position(9 - homePerspective.getRow(),
                7 - homePerspective.getColumn());
    }

    public static Position toHomePerspective(Position physical, TeamSide team) {
        if (team == TeamSide.HOME) return physical;
        return new Position(9 - physical.getRow(),
                7 - physical.getColumn());
    }

    public static Position toPhysical(Position homePerspective, String team) {
        return toPhysical(homePerspective, TeamSide.fromString(team));
    }

    public static Position toHomePerspective(Position physical, String team) {
        return toHomePerspective(physical, TeamSide.fromString(team));
    }
}
