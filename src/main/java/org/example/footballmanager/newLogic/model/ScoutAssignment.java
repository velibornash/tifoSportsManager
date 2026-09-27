package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Data;

/**
 * A scout sent to a country (Sprint 5, S5.1).
 *
 * <p>Sprint 5 exists because the academy could only ever produce what it generated itself. There was
 * no way to look at a player the club had not already produced, and {@code Country.youthRating} had
 * been seeded and read by nothing since Sprint 2.1 — a hook built for this feature and left hanging.
 *
 * <p><b>This is intel, not a transfer.</b> The owner's ruling on 2026-09-27 was that a scouted
 * prospect is reported on, never acquired. That decision is load-bearing for the shape of this entity:
 * an assignment produces information, so it has no fee, no contract, no registration and no claim on
 * a squad slot. It also means this sprint does not need the non-EU quota that S3.5 never built —
 * nothing here registers a foreign player, so nothing here can breach a quota.
 *
 * <p>An assignment is <b>not</b> a per-week transaction. It is a standing order: the club pays the
 * scout's wage through the ordinary {@code STAFF_WAGES} ledger line whether or not he is abroad, and
 * the assignment records where his attention is pointed. Revoking it stops the reports; it does not
 * stop the wage.
 */
@Data
@Entity(name = "ScoutAssignment")
public class ScoutAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    /**
     * The scout doing the watching. Constrained to {@link StaffRole#SCOUT} by
     * {@code ScoutingService}, not by the schema — a foreign key cannot express "a staff member whose
     * role happens to be SCOUT", and a constraint that lives only in the service is one a future
     * caller can forget. The service check is the contract; this javadoc is the reminder.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    private StaffMember scout;

    @ManyToOne(fetch = FetchType.LAZY)
    private Country country;

    /**
     * When the club committed to this posting. Recorded rather than derived from an audit column so
     * that a report can say "third season watching Brazil", which is the thing that makes a scouting
     * network feel like an investment instead of a toggle.
     */
    @Column(name = "assigned_season_number")
    private Integer assignedSeasonNumber;

    @Column(name = "active")
    private Boolean active = true;
}
