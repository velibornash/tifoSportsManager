package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A foreign player's right to work in this country (Sprint 3.5).
 *
 * <p>Serbia's rule is four non-EU players in the top flight and fewer below, and every one of them
 * needs a permit. That is the constraint that shapes a transfer window: a club with four foreign
 * players cannot sign a fifth however badly it wants him, so the quota has to shape the decision
 * rather than turn up afterwards as a validation error.
 *
 * <p>A permit is granted per season and per club. A player who moves needs a new one, because the
 * permit belongs to the registration, not to the man.
 */
@Entity
@Getter
@Setter
public class WorkPermit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    /** The club registering him. */
    @Column(name = "club_id", nullable = false)
    private Long clubId;

    private Integer season;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermitStatus status = PermitStatus.PENDING;

    /** What the application was refused for, or granted on the strength of. Shown to the manager. */
    @Column(length = 300)
    private String reason;

    private Instant decidedAt = Instant.now();

    public enum PermitStatus {
        /** Applied for, no decision yet. */
        PENDING,
        /** Registered. */
        GRANTED,
        /** Refused. The signing is blocked. */
        REFUSED
    }

    public boolean granted() {
        return status == PermitStatus.GRANTED;
    }

    public String describe() {
        return "Work permit " + status.name().toLowerCase()
                + (reason == null || reason.isBlank() ? "" : ": " + reason);
    }
}
