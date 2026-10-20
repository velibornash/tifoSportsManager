package org.example.footballmanager.newLogic.model;

/**
 * Where on the pitch a player did his work (owner, 2026-09-29).
 *
 * <p>The owner: "morale / form should be a zone compute, recovery is happening each day." So a striker
 * who spent a match in the box and a keeper who never left the six-yard box cannot recover the same,
 * and must not be modelled the same.
 *
 * <p><b>Nine zones, not a grid of cells.</b> The engine has cells; the database does not need them.
 * What recovery and form care about is where a player operated - own third, middle, opposition third
 * - and which side of the pitch, because that is what a manager would say about a performance. Sixty-odd
 * cells would make every query a join and every explanation unreadable without making the result
 * any better.
 *
 * <p>Zones are from the perspective of the team the player is on, not an absolute direction. "Defensive
 * third" means the third they were defending in, which is the only version that means the same thing
 * to both teams in a match.
 */
public enum Zone {

    /*
     * The order of the two numbers is (third, lane), so the SECOND number varies down each row.
     *
     * It was the other way round. Every constant was written lane-first, and the constructor assigned
     * the first argument to `third`, so the nine constants carried each name's third and lane
     * swapped: DEFENSIVE_CENTRE had third 1, lane 0, which is the coordinates of a defensive LEFT.
     *
     * Nothing caught it because the zone table was empty. `of()` is only called when a match has been
     * played and a load row written, and `workRate()` only reads a row's own zone - so with no writer
     * anywhere, a keeper standing on his line was never classified at all, and when he finally was, the
     * name said MIDFIELD_LEFT and the cost model charged him the busiest rate on the pitch.
     */
    DEFENSIVE_LEFT(0, 0),
    DEFENSIVE_CENTRE(0, 1),
    DEFENSIVE_RIGHT(0, 2),
    MIDFIELD_LEFT(1, 0),
    MIDFIELD_CENTRE(1, 1),
    MIDFIELD_RIGHT(1, 2),
    ATTACKING_LEFT(2, 0),
    ATTACKING_CENTRE(2, 1),
    ATTACKING_RIGHT(2, 2);

    /** Third of the pitch, 0 own .. 2 opposition. */
    private final int third;
    /** 0 left, 1 centre, 2 right - from the team's own perspective. */
    private final int lane;

    Zone(int third, int lane) {
        this.third = third;
        this.lane = lane;
    }

    public int third() {
        return third;
    }

    public int lane() {
        return lane;
    }

    /** How hard a minute of work in this zone costs the body. */
    public double workRate() {
        return switch (this) {
            case MIDFIELD_CENTRE -> 1.0;
            case MIDFIELD_LEFT, MIDFIELD_RIGHT -> 0.95;
            case ATTACKING_CENTRE -> 0.9;
            case ATTACKING_LEFT, ATTACKING_RIGHT -> 0.85;
            case DEFENSIVE_CENTRE -> 0.8;
            case DEFENSIVE_LEFT, DEFENSIVE_RIGHT -> 0.75;
        };
    }

    /**
     * Where a cell falls.
     *
     * <p>Takes the engine's own coordinates - row 1..7 running from a team's own goal line, column
     * 1..6 across - so a zone is derived where the position data already is rather than being a second
     * coordinate system somebody has to keep in step.
     */
    public static Zone of(double row, double column) {
        int third = row <= 2.34 ? 0 : (row <= 4.66 ? 1 : 2);
        int lane = column <= 2.33 ? 0 : (column <= 4.67 ? 1 : 2);
        for (Zone zone : values()) {
            if (zone.third == third && zone.lane == lane) {
                return zone;
            }
        }
        return MIDFIELD_CENTRE;
    }
}
