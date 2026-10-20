package org.example.footballmanager.newLogic.dto.junior;

import lombok.Data;

/**
 * A club's junior school: whether it runs, what it costs, and what may be done to it this week.
 *
 * <p>The window flags are the reason this is a DTO rather than two booleans on the academy state. A
 * manager needs to know <b>that</b> the school can only be opened in week 1 and closed in week 12,
 * not just whether the button happens to be disabled — a control that is greyed out for reasons the
 * screen never states reads as a bug.
 */
@Data
public class JuniorSchoolStateDTO {

    private Long teamId;
    private String teamName;

    /** Whether the school is running. */
    private boolean active;

    /** The season it was last opened, or null if it never has been. */
    private Integer sinceSeason;

    private int seasonNumber;
    private int weekNumber;

    /** Whether the school may be opened right now. */
    private boolean canOpen;

    /** Whether the school may be closed right now. */
    private boolean canClose;

    /** The one-off activation fee. Null when the school is already running and has been paid for. */
    private Double activationFee;

    /** The weekly upkeep while running. */
    private double weeklyUpkeep;

    private int activeJuniors;

    /** One sentence on the current state, including which week each action is available in. */
    private String note;
}
