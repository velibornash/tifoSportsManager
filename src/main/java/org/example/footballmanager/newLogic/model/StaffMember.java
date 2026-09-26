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

    /** Total of the six attributes, 6-120. Cheap to sort and compare on. */
    public int overall() {
        return nz(development) + nz(tactical) + nz(motivation)
                + nz(goalkeeping) + nz(fitness) + nz(scouting);
    }

    private int nz(Integer v) {
        return v == null ? 1 : v;
    }
}
