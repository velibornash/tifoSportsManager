package org.example.footballtextmanager.model;

/**
 * Squad-wide training emphasis, chosen once and applied at the end of every round.
 *
 * <p>Deliberately <b>one choice for the whole squad</b>, not per-player schedules. The mode is a
 * text manager and the brief was explicit: do not overbuild match preparation, but the basic
 * skills should be able to change a result. A single selector does that with one decision per
 * round; a per-player programme would add a dozen controls and a dozen ways to get it wrong.
 *
 * <p>{@link #BALANCED} is the default so a manager who never touches the page still develops
 * their squad, just slowly and evenly.
 */
public enum CSTrainingFocus {

    /** Small gain in every attribute. The default. */
    BALANCED("Balanced", "Steady all-round work. No weak spot, no specialist."),

    ATTACK("Attack", "Pace, shooting and technique. Forwards and wingers benefit most."),

    DEFENCE("Defence", "Defending, stamina and goalkeeping. Back line and keeper benefit most."),

    CREATION("Creation", "Passing, playmaking and technique. Midfield and wide men benefit most.");

    private final String label;
    private final String note;

    CSTrainingFocus(String label, String note) {
        this.label = label;
        this.note = note;
    }

    public String getLabel() {
        return label;
    }

    public String getNote() {
        return note;
    }

    /** Lenient lookup: an unknown or absent name means "no preference", not an error. */
    public static CSTrainingFocus from(String name) {
        if (name == null || name.isBlank()) return BALANCED;
        for (CSTrainingFocus focus : values()) {
            if (focus.name().equalsIgnoreCase(name.trim())) return focus;
        }
        return BALANCED;
    }
}