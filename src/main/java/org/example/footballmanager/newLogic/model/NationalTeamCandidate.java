package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Someone standing for selector (owner, 2026-09-28).
 *
 * <p>A candidate may withdraw. Withdrawal is a flag rather than a delete so that a vote already cast
 * for them can be seen and re-cast rather than silently vanishing from the tally.
 *
 * <p>One candidacy per user per election, so re-registering after withdrawing revives the existing
 * row instead of creating a second ballot line.
 */
@Entity
@Table(name = "national_team_candidate",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_nt_candidate_election_user",
                columnNames = {"election_id", "user_id"}))
public class NationalTeamCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "election_id", nullable = false)
    private NationalTeamElection election;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private org.example.commonmanager.model.User user;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    @Column(name = "withdrawn", nullable = false)
    private boolean withdrawn;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    public Long getId() {
        return id;
    }

    public NationalTeamElection getElection() {
        return election;
    }

    public void setElection(NationalTeamElection election) {
        this.election = election;
    }

    public org.example.commonmanager.model.User getUser() {
        return user;
    }

    public void setUser(org.example.commonmanager.model.User user) {
        this.user = user;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public boolean isWithdrawn() {
        return withdrawn;
    }

    public void setWithdrawn(boolean withdrawn) {
        this.withdrawn = withdrawn;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(Instant withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }
}
