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
 * One user's vote in one election (owner, 2026-09-28).
 *
 * <p>The owner specified one vote per user, changeable. That is a unique constraint on
 * (election, voter), not application logic: two clicks in the same second must not produce two
 * ballots. Changing a vote updates this row in place, so the count is always one ballot per voter
 * and a re-vote cannot inflate the total.
 *
 * <p>Tallying happens in Java rather than by counting rows per candidate, because a candidate who
 * withdraws must stay visible in the tally as withdrawn rather than quietly reducing their count -
 * the owner can then see whether withdrawing changed anything.
 */
@Entity
@Table(name = "national_team_vote",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_nt_vote_election_voter",
                columnNames = {"election_id", "voter_id"}))
public class NationalTeamVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "election_id", nullable = false)
    private NationalTeamElection election;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voter_id", nullable = false)
    private org.example.commonmanager.model.User voter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private NationalTeamCandidate candidate;

    @Column(name = "cast_at", nullable = false)
    private Instant castAt = Instant.now();

    /** How many times this voter has changed their mind. Useful for spotting a disputed result. */
    @Column(name = "change_count", nullable = false)
    private int changeCount;

    public Long getId() {
        return id;
    }

    public NationalTeamElection getElection() {
        return election;
    }

    public void setElection(NationalTeamElection election) {
        this.election = election;
    }

    public org.example.commonmanager.model.User getVoter() {
        return voter;
    }

    public void setVoter(org.example.commonmanager.model.User voter) {
        this.voter = voter;
    }

    public NationalTeamCandidate getCandidate() {
        return candidate;
    }

    public void setCandidate(NationalTeamCandidate candidate) {
        this.candidate = candidate;
    }

    public Instant getCastAt() {
        return castAt;
    }

    public void setCastAt(Instant castAt) {
        this.castAt = castAt;
    }

    public int getChangeCount() {
        return changeCount;
    }

    public void setChangeCount(int changeCount) {
        this.changeCount = changeCount;
    }
}
