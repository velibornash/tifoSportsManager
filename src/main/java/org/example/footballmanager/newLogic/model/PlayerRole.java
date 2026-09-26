package org.example.footballmanager.newLogic.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A player's detailed role on the pitch, as distinct from his {@link Position}.
 *
 * <p>These are two different questions and mixing them up causes real bugs. <b>Position</b> is the
 * broad unit — five of them — and answers "which third of the pitch is he in". <b>Role</b> is the
 * specific job — fifteen of them — and answers "what is he actually for". A centre back and a full
 * back are both DEF, but a club that needs a left back has a different problem from one that needs a
 * centre back, and training a full back is not training a centre back.
 *
 * <p>So: formations, squad balance and the shape of a club speak <b>position</b>; training,
 * scouting, the transfer market and the depth chart speak <b>role</b>. Every role maps to exactly
 * one position, so anything that genuinely only needs the broad unit can keep asking for it.
 */
public enum PlayerRole {

    GOALKEEPER(Position.GK, "Goalkeeper"),

    // Defenders, outside in
    LEFT_BACK(Position.DEF, "Left back"),
    LEFT_WING_BACK(Position.DEF, "Left wing back"),
    CENTRE_BACK(Position.DEF, "Centre back"),
    RIGHT_CENTRE_BACK(Position.DEF, "Right centre back"),
    RIGHT_WING_BACK(Position.DEF, "Right wing back"),
    RIGHT_BACK(Position.DEF, "Right back"),

    // Defensive midfield
    DEFENSIVE_MIDFIELDER(Position.MID, "Holding midfielder"),
    CENTRE_MIDFIELDER(Position.MID, "Centre midfielder"),
    DEEP_PLAYMAKER(Position.MID, "Deep playmaker"),
    ATTACKING_MIDFIELDER(Position.MID, "Attacking midfielder"),

    // Wide players
    WINGER(Position.WNG, "Winger"),
    INSIDE_FORWARD(Position.WNG, "Inside forward"),
    WIDE_MIDFIELDER(Position.WNG, "Wide midfielder"),

    // Forwards
    STRIKER(Position.ATT, "Striker"),
    SECOND_STRIKER(Position.ATT, "Second striker"),
    FALSE_NINE(Position.ATT, "False nine");

    private final Position position;
    private final String label;

    PlayerRole(Position position, String label) {
        this.position = position;
        this.label = label;
    }

    /** The broad unit this role belongs to. */
    public Position position() {
        return position;
    }

    /** A human-readable name, for the squad screen. */
    public String label() {
        return label;
    }

    /** Every role belonging to a position. */
    public static List<PlayerRole> of(Position position) {
        return Arrays.stream(values())
                .filter(r -> r.position == position)
                .toList();
    }

    /**
     * A sensible role for a position, when nothing better is known.
     *
     * <p>Used to fill in a role that was never set, so the detail is always populated — an unknown
     * role is what makes a squad screen read "null" and a training plan meaningless.
     */
    public static PlayerRole defaultFor(Position position) {
        if (position == null) return CENTRE_MIDFIELDER;
        return switch (position) {
            case GK -> GOALKEEPER;
            case DEF -> CENTRE_BACK;
            case MID -> CENTRE_MIDFIELDER;
            case ATT -> STRIKER;
            case WNG -> WINGER;
        };
    }

    /** The role with this name, if there is one. */
    public static Optional<PlayerRole> byName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String clean = name.trim().toUpperCase().replace(' ', '_');
        return Arrays.stream(values()).filter(r -> r.name().equals(clean)).findFirst();
    }

    /**
     * How well this role fits a position.
     *
     * <p>Zero means it is the wrong job entirely. A squad-balance check that treats a winger as a
     * centre back is how a club ends up with three full backs and no striker.
     */
    public boolean fits(Position other) {
        return other != null && this.position == other;
    }
}
