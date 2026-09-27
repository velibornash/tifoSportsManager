package org.example.footballmanager.newLogic.dto.junior;

import lombok.Data;

/**
 * One junior, as a manager sees him.
 *
 * <p><b>There is deliberately no raw {@code talent} field.</b> It used to be here, populated with
 * {@code round2(junior.getTalent())} for every caller, which handed every manager the exact ceiling
 * of every prospect in his own academy on the morning they arrived — and a rival's, if the id was
 * guessed. The owner's rule is that talent is paid information, and inside the academy it is paid
 * information <i>as a range that firms up</i> (Sprint 5.2).
 *
 * <p>Removing the field rather than nulling it conditionally is the point. A field that is
 * "sometimes populated" is one caller away from leaking, and there is no way to grep for that. With
 * the field gone, the only way talent reaches the browser is through one of the three below, and each
 * is set on purpose.
 *
 * <p>All three are {@code null} for a viewer without a PLUS subscription, which is not the same as
 * zero: a zero talent would render as a real, terrible player rather than as an absence.
 */
@Data
public class JuniorAcademyItemDTO {
    private Long id;
    private String name;
    private int age;
    private int academySkill;
    private double academySkillExact;
    private double lastWeeklyDelta;
    private String status;
    private int arrivalSeasonNumber;
    private int arrivalWeekNumber;
    private Long promotedPlayerId;
    private boolean archived;

    /** The age he signed for the academy. Null means "never recorded" — see {@code Junior.arrivalAge}. */
    private Integer arrivalAge;

    /**
     * The position he was signed for. Null only for a junior created before positions were recorded.
     */
    private String position;

    /**
     * The reported talent band, lower bound. Null when the viewer may not see talent at all.
     *
     * <p>Width at intake is {@code ±(1 + rnd(0..3))}, narrowing to {@code ±1} by promotion, and a
     * better youth coach narrows it faster. See {@code TalentRange}.
     */
    private Double talentLow;

    /** The reported talent band, upper bound. */
    private Double talentHigh;

    /**
     * The exact talent, with decimals — <b>only once he has been promoted</b>.
     *
     * <p>Null for an active junior even to a PLUS viewer: the whole point of the academy is that the
     * ceiling is something you discover by watching him, not something you are told on arrival.
     */
    private Double talentExact;

    /** The half-width currently in force, for the UI to render "6.4 – 9.2" without recomputing it. */
    private Double talentRangeHalfWidth;
}
