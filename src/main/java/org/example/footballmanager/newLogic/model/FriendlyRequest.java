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

/**
 * A club asking another club for a friendly (owner-defined, 2026-09-26).
 *
 * <p>The owner was explicit that friendlies are <b>not</b> scheduled for you. A week has four slots
 * and an empty one is an opportunity, not an obligation: a club may ask any other club for a
 * friendly, the other club may accept or refuse, and a friendly does not reduce the training
 * session budget. That is why this is a request with a state machine rather
 * than a fixture that simply appears on a calendar.
 *
 * <p>A request is for one specific week and one specific slot, because a club can fill open slots
 * separately and a club in the week-11 playoff cannot use the playoff slot.
 */
@Entity
@Getter
@Setter
public class FriendlyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The club that wants to play. */
    @Column(name = "requester_team_id", nullable = false)
    private Long requesterTeamId;

    /** The club being asked. */
    @Column(name = "opponent_team_id", nullable = false)
    private Long opponentTeamId;

    private Integer season;

    /** The week of the season the friendly would be played in. */
    private Integer week;

    /** Calendar slot number: 1 = day 1, 2 = day 3, 3 = day 5, 4 = day 7. */
    private Integer slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FriendlyStatus status = FriendlyStatus.PENDING;

    /** Why the club said no, when it did. Shown to the manager so refusals are not silent. */
    @Column(length = 200)
    private String declineReason;

    private Long acceptedFixtureId;

    public enum FriendlyStatus {
        /** Asked for, no answer yet. */
        PENDING,
        /** Agreed. A fixture exists. */
        ACCEPTED,
        /** Refused. The requester must look elsewhere. */
        DECLINED,
        /** Withdrawn by the club that asked. */
        CANCELLED,
        /** The week came and went with no answer. */
        EXPIRED
    }

    /** Whether the other club has answered. */
    public boolean isAnswered() {
        return status != FriendlyStatus.PENDING;
    }

    /** A one-line description for the UI. */
    public String describe() {
        return "Week " + week + " slot " + slot + " — " + status.name().toLowerCase();
    }
}
