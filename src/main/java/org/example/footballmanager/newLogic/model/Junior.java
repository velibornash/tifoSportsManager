package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "Junior")
public class Junior {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private int age;
    private double talent;
    private int academySkill;
    private double academySkillExact;
    private double lastWeeklyDelta;
    private int arrivalSeasonNumber;
    private int arrivalWeekNumber;

    /**
     * The age he arrived at, recorded rather than derived.
     *
     * <p>Necessary because {@link #age} moves once a year at the season boundary, so "how long has the
     * club been watching him" cannot be recovered from it afterwards. It is the clock the talent
     * report narrows against (Sprint 5.2), and it is the reason a report does not narrow week by week:
     * nothing about a player changes between week 3 and week 30.
     */
    private Integer arrivalAge;

    /**
     * The half-width of his talent report on the day he arrived — between {@code +1} and {@code +4},
     * rolled once (Sprint 5.2).
     *
     * <p>Stored rather than re-rolled because a range that changed every week would be theatre: the
     * report would move because it was re-drawn, not because anything was learned.
     *
     * <p><b>Nullable means "never rolled".</b> It does not default to zero, and reading null as a
     * tight range would hand every pre-existing junior in the database a false ±0 report on day one.
     */
    private Double talentRangeHalfWidth;

    /**
     * The position he was signed for (Sprint 5.3, owner 2026-09-27).
     *
     * <p>This used to be rolled at <b>promotion</b>, which meant a manager paid for a school, signed a
     * fifteen-year-old with no position listed anywhere, and then watched a goalkeeper come out of the
     * academy. A prospect is signed for a position; that is the whole basis on which a scouting
     * decision is made.
     *
     * <p>The <b>distribution</b> is deliberately unchanged — the same 12% goalkeepers and the same
     * three-way split of the rest — because the owner ruled that graduation must not move. Only the
     * <i>timing</i> changes: the roll happens at intake instead of at graduation, so the same
     * probabilities produce the same number of each kind of player.
     *
     * <p>Nullable means "recorded before positions existed". Reading null as a real position would
     * invent one, and reading it as "unknown" at promotion would quietly re-roll it — which is the one
     * outcome this field exists to prevent.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "position", length = 8)
    private Position position;

    /**
     * Effort in training, 1-20 (Sprint 5.3, owner 2026-09-27).
     *
     * <p>Visible and sortable on purpose. A manager should be able to rank an academy by effort, and
     * discovering that his best prospect is not the hardest worker is <i>interesting</i> — it is a
     * scouting judgement rather than a frustration. Hiding it would remove the only thing a manager can
     * act on.
     */
    private Integer workRate;

    /** How he is wired. Feeds development, and is carried into the senior squad. */
    @Enumerated(EnumType.STRING)
    @Column(name = "personality", length = 16)
    private Personality personality;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_foot", length = 8)
    private PreferredFoot preferredFoot;


    /**
     * Height in centimetres at intake.
     *
     * <p>Changes very little while he is in the academy: a fifteen-year-old is still growing, a
     * nineteen-year-old is finished. See {@code YouthAcademyService.developBody}.
     */
    private Double height;

    /** Weight in kilograms now — what the club has actually got him to. */
    private Double weight;

    /**
     * The weight his body wants to be, rolled once at intake.
     *
     * <p>This is what makes the gym meaningful. The gym does not hand a club a better body; it lets the
     * club <b>correct</b> the one it was given, by pulling {@link #weight} toward this figure faster.
     * A club with no gym simply lets a heavy prospect stay heavy, which is a real risk.
     */
    private Double naturalWeight;

    private Boolean archived = false;

    @Enumerated(EnumType.STRING)
    private JuniorStatus status = JuniorStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_player_id")
    private Player promotedPlayer;
}