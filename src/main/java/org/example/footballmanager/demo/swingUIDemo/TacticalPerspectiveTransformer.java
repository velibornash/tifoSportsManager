package org.example.footballmanager.demo.swingUIDemo;

/**
 * Converts tactical-editor coordinates (always authored from HOME's view) to
 * physical demo coordinates for either team.
 *
 * The editor's progress axis and lateral axis are both mirrored for AWAY:
 * HOME CELL(r,c) -> AWAY Position(7-r, 6-c), where r/c are zero-based.
 */
public final class TacticalPerspectiveTransformer {
    private TacticalPerspectiveTransformer() {}

    public static Position toPhysical(Position homePerspective, String team) {
        if (!"AWAY".equals(team)) return homePerspective;
        return new Position(8 - homePerspective.getRow(),
                7 - homePerspective.getColumn());
    }

    public static Position toHomePerspective(Position physical, String team) {
        if (!"AWAY".equals(team)) return physical;
        return new Position(8 - physical.getRow(),
                7 - physical.getColumn());
    }
}
