package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Who manages a national side, and for how long (owner, 2026-09-28).
 *
 * <p>Until this existed, the country page showed the viewer's own name as selector, computed at read
 * time. That made every user in a country believe they ran the national team, and made "only the
 * selector can see the squad" impossible to enforce - there was no selector to check against.
 *
 * <p>Appointments are recorded rather than derived so that a mandate has a length. The owner's rule is
 * one season per mandate; {@link #mandateEndsAt} carries that boundary, and a null value means
 * "until replaced" rather than "forever", which is the honest reading until the election dates exist.
 *
 * <p>One active appointment per country and level, enforced by a unique constraint rather than by
 * application logic, so a concurrent pair of election results cannot both write an "active" row.
 */
@Entity
@Table(name = "national_team_appointment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_nt_appointment_country_level_active",
                columnNames = {"country_id", "level", "active"}))
public class NationalTeamAppointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private NationalTeamLevel level;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "selector_user_id", nullable = false)
    private org.example.commonmanager.model.User selector;

    /**
     * Whether this appointment still holds the job.
     *
     * <p>Part of the unique constraint. SQLite and H2 both treat {@code false} as a distinct value, so
     * exactly one inactive row is allowed per country and level - which is not enough to keep an audit
     * trail. The constraint is on the active flag deliberately anyway: the failure it prevents (two
     * selectors at once) is worse than the history it costs.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "appointed_at", nullable = false)
    private Instant appointedAt = Instant.now();

    @Column(name = "mandate_ends_at")
    private Instant mandateEndsAt;

    /**
     * Whether the appointment came from a vote or was placed by hand.
     *
     * <p>The seeded appointments are manual. A screen must not imply an election result that has not
     * happened, so this is carried through to the payload and rendered as "provisional" when false.
     */
    @Column(name = "elected", nullable = false)
    private boolean elected;

    public Long getId() {
        return id;
    }

    public Country getCountry() {
        return country;
    }

    public void setCountry(Country country) {
        this.country = country;
    }

    public NationalTeamLevel getLevel() {
        return level;
    }

    public void setLevel(NationalTeamLevel level) {
        this.level = level;
    }

    public org.example.commonmanager.model.User getSelector() {
        return selector;
    }

    public void setSelector(org.example.commonmanager.model.User selector) {
        this.selector = selector;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getAppointedAt() {
        return appointedAt;
    }

    public void setAppointedAt(Instant appointedAt) {
        this.appointedAt = appointedAt;
    }

    public Instant getMandateEndsAt() {
        return mandateEndsAt;
    }

    public void setMandateEndsAt(Instant mandateEndsAt) {
        this.mandateEndsAt = mandateEndsAt;
    }

    public boolean isElected() {
        return elected;
    }

    public void setElected(boolean elected) {
        this.elected = elected;
    }
}
