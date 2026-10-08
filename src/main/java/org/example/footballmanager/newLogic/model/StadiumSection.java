package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One of a stadium's eight sections (owner, 2026-10-07).
 *
 * <p>Four sides and four corners. A club builds each one piece at a time: a seating type, a capacity to
 * add, and only a roof over that section (not over everything). A section carries <b>its own ticket
 * price</b>, and the recommended one.
 *
 * <p>The ground's total capacity is the sum across these eight, so {@link Stadium#getCapacity} is
 * recomputed from them rather than stored separately — a capacity the sections cannot add up to is a lie,
 * and a column no code totals is how the two invariably drift apart.
 */
@Data
@Entity
@Table(name = "stadium_section",
        uniqueConstraints = @UniqueConstraint(name = "uk_section_stadium_position",
                columnNames = {"stadium_id", "position"}))
public class StadiumSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stadium_id", nullable = false)
    private Stadium stadium;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StandPosition position;

    /** Null until this section has been built out. */
    @Enumerated(EnumType.STRING)
    @Column
    private SeatingType seatingType;

    /** The capacity this one section contributes to the whole ground. */
    @Column(nullable = false)
    private Integer capacity = 0;

    /** A roof over only this section, not the whole ground. */
    @Column(nullable = false)
    private boolean roof = false;

    /** What the club charges for a seat in this section. Null while there are no seats to sell. */
    private Double ticketPrice;

    /** The recommended price for a seat in this section, before any markup. Null while the section is unbuilt. */
    private Double recommendedPrice;

    /** The in-game week the section can take fans into again after work starts, null when it is open. */
    private Integer closedUntilWeek;

    /** The season the above week belongs to. */
    private Integer closedUntilSeason;

    /** True while the section is shut, judged on its own closure and not on any global state. */
    public boolean isClosed() {
        return closedUntilWeek != null;
    }
}
