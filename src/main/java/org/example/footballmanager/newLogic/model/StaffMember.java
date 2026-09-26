package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Data;

import java.util.List;

/**
 * A member of a club's staff (Sprint 2.3).
 *
 * <p>There was no staff entity at all: the staff directory was a hardcoded array in the browser and
 * coaching had no simulation effect whatsoever, so the most expensive thing a club buys did nothing.
 *
 * <p>Attributes are 1-20 and are consumed by Sprint 4 (development), Sprint 5 (scouting) and the
 * injury system (fitness). They are seeded here from the club's reputation and the division tier, so
 * a Superliga club gets a genuinely better coaching staff than a village club.
 */
@Data
@Entity(name = "StaffMember")
public class StaffMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private StaffRole role;

    private String name;
    private Integer age;

    /** Season the contract runs out at the end of. */
    private Integer contractEndSeason;

    /** Weekly wage. Summed into STAFF_WAGES by the weekly settlement. */
    private Double weeklyWage;

    /** 1-20. How much this member improves players. */
    private Integer development;
    /** 1-20. How well this member prepares the team tactically. */
    private Integer tactical;
    /** 1-20. Motivation, discipline, man-management. */
    private Integer motivation;
    /** 1-20. Goalkeeping coaching. */
    private Integer goalkeeping;
    /** 1-20. Fitness and injury prevention. */
    private Integer fitness;
    /** 1-20. Scouting and recruitment judgement. */
    private Integer scouting;

    // ---------------------------------------------------------------------
    // Coaching skills - the same eight a player has (owner, 2026-09-27).
    //
    // <p>A single "development" rating could not express the decision the owner wants: hire a cheap
    // coach who is excellent at the two skills you actually train, or a dear one who can rotate
    // across all eight. Those are different coaches and the club has to be able to tell them apart,
    // because {@code canImprove} asks the coach's rating <i>for the skill being trained this week</i>.
    //
    // <p>Nullable, like every rating here, so staff hired before this existed still work. What the
    // training system reads is {@link #coachRating}, which falls back rather than throwing.
    // ---------------------------------------------------------------------

    /** Skill ratings, 1-20, matching {@code SkillName} minus fatigue. */
    private Integer skillStamina;
    private Integer skillGoalkeeper;
    private Integer skillDefender;
    private Integer skillPace;
    private Integer skillTechnique;
    private Integer skillPlaymaker;
    private Integer skillPassing;
    private Integer skillStriker;

    /** Total of the six attributes, 6-120. Cheap to sort and compare on. */
    /**
     * The coach's rating for one skill, 1-20.
     *
     * <p>Falls back in a defined order rather than defaulting to zero, so a coach with no ratings
     * recorded yet is a mediocre coach rather than a catastrophic one — a club should not see every
     * player's development stop because a backfill has not run.
     */
    public int coachRating(SkillName skill) {
        if (skill == null) return 0;
        return switch (skill) {
            case STAMINA -> first(skillStamina, development, fitness);
            case GOALKEEPER -> first(skillGoalkeeper, goalkeeping, development);
            case DEFENDER -> first(skillDefender, development);
            case PACE -> first(skillPace, development);
            case TECHNIQUE -> first(skillTechnique, development);
            case PLAYMAKER -> first(skillPlaymaker, motivation, development);
            case PASSING -> first(skillPassing, tactical, development);
            case STRIKER -> first(skillStriker, development);
            case FATIGUE -> first(skillStamina, fitness, development);
        };
    }

    /**
     * Sets one coaching skill by name, mirroring the eight fields.
     *
     * <p>There are eight stored columns rather than a map, so they behave like any other entity
     * attribute and can be queried and indexed. That makes a name-based setter worth having: hiring
     * and seeding both want to say "this coach is good at passing" rather than
     * "setSkillPassing", and they should not have to switch on the skill themselves.
     *
     * <p>An unknown skill is ignored rather than throwing, so adding one to {@code SkillName} later
     * cannot break staff seeding.
     */
    public void setSkill(SkillName skill, int value) {
        if (skill == null) return;
        int clamped = Math.max(1, Math.min(20, value));
        switch (skill) {
            case STAMINA -> skillStamina = clamped;
            case GOALKEEPER -> skillGoalkeeper = clamped;
            case DEFENDER -> skillDefender = clamped;
            case PACE -> skillPace = clamped;
            case TECHNIQUE -> skillTechnique = clamped;
            case PLAYMAKER -> skillPlaymaker = clamped;
            case PASSING -> skillPassing = clamped;
            case STRIKER -> skillStriker = clamped;
            case FATIGUE -> { /* fatigue is a condition, not something a coach teaches */ }
        }
    }

    /** The first value actually recorded, clamped into range. */
    private int first(Integer... candidates) {
        for (Integer candidate : candidates) {
            if (candidate != null) {
                return Math.max(1, Math.min(20, candidate));
            }
        }
        return 1;
    }

    /** Every coaching skill, in the order of {@code SkillName} minus fatigue. */
    public List<Integer> coachSkillRatings() {
        return List.of(coachRating(SkillName.STAMINA), coachRating(SkillName.GOALKEEPER),
                coachRating(SkillName.DEFENDER), coachRating(SkillName.PACE),
                coachRating(SkillName.TECHNIQUE), coachRating(SkillName.PLAYMAKER),
                coachRating(SkillName.PASSING), coachRating(SkillName.STRIKER));
    }

    /** The sum of the eight coaching skills. The main driver of what a coach costs. */
    public int coachSkillSum() {
        return coachSkillRatings().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * How many of the eight skills this coach is maxed at (20).
     *
     * <p>Weighted separately from the sum, and deliberately so: a coach who is excellent at
     * everything is worth more than one with the same total spread evenly, because he can keep a
     * squad improving without the manager rotating the programme around his weak spots. That is
     * what makes "cheap specialist" and "dear generalist" real alternatives.
     */
    public int maxedSkillCount() {
        return (int) coachSkillRatings().stream().filter(v -> v >= 20).count();
    }

    public int overall() {
        return nz(development) + nz(tactical) + nz(motivation)
                + nz(goalkeeping) + nz(fitness) + nz(scouting);
    }

    private int nz(Integer v) {
        return v == null ? 1 : v;
    }
}
