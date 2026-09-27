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