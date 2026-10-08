package org.example.footballmanager.newLogic.model;

/**
 * The stadium's sections: four sides and four corners (owner, 2026-10-07).
 *
 * <p>A ground is not one block of capacity. It is eight separately plannable pieces, and the owner's
 * rule treats each the same way: you pick what kind of seating goes there, how much capacity to add,
 * and only a roof (not the whole structure) for that one section.
 */
public enum StandPosition {
    NORTH("North"),
    SOUTH("South"),
    EAST("East"),
    WEST("West"),
    NORTH_EAST("North-east corner"),
    NORTH_WEST("North-west corner"),
    SOUTH_EAST("South-east corner"),
    SOUTH_WEST("South-west corner");

    private final String label;

    StandPosition(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
