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
 * A selector election for one country and one level (owner, 2026-09-28).
 *
 * <p>The owner's rules, in the order they were given:
 *
 * <ul>
 *   <li>Registration opens week 12 day 1 of the previous season, and runs again during week 1.
 *   <li>Voting runs week 1 day 1 (20:45) to week 1 day 7 midday.
 *   <li>One vote per user of the country, changeable while voting is open.
 *   <li>A candidate may vote, including for themselves.
 *   <li>Votes are secret until the result is declared.
 *   <li>An admin can annul, and the whole thing repeats every season.
 *   <li>The winner holds the job for one season.
 * </ul>
 *
 * <p>One election per country, level and season, enforced by constraint rather than by application
 * logic, so two concurrent "start an election" calls cannot both create one.
 */
@Entity
@Table(name = "national_team_election",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_nt_election_country_level_season",
                columnNames = {"country_id", "level", "season_year"}))
public class NationalTeamElection {

    /** Where an election is in its life. Registration precedes voting; nothing can go back. */
    public enum Status {
        /** Candidates may sign up. Votes are not accepted. */
        REGISTRATION,
        /** Votes are accepted and may be changed. */
        VOTING,
        /** Result declared, winner appointed. Terminal. */
        DECIDED,
        /** Cancelled by an admin. Terminal. */
        ANNULLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private NationalTeamLevel level;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.REGISTRATION;

    @Column(name = "registration_opens_at", nullable = false)
    private Instant registrationOpensAt;

    /** Voting opens when registration closes, unless the owner opens it by hand. */
    @Column(name = "voting_opens_at")
    private Instant votingOpensAt;

    @Column(name = "voting_closes_at")
    private Instant votingClosesAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** The candidate who won, once declared. Null until then, and after an annulment. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_user_id")
    private org.example.commonmanager.model.User winner;

    /**
     * Set when an admin forced the state rather than the clock driving it.
     *
     * <p>Exists so the panel can say "opened early by an administrator" instead of implying the
     * normal week-1 window is running when it is not.
     */
    @Column(name = "manual_override", nullable = false)
    private boolean manualOverride;

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

    public Integer getSeasonYear() {
        return seasonYear;
    }

    public void setSeasonYear(Integer seasonYear) {
        this.seasonYear = seasonYear;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getRegistrationOpensAt() {
        return registrationOpensAt;
    }

    public void setRegistrationOpensAt(Instant registrationOpensAt) {
        this.registrationOpensAt = registrationOpensAt;
    }

    public Instant getVotingOpensAt() {
        return votingOpensAt;
    }

    public void setVotingOpensAt(Instant votingOpensAt) {
        this.votingOpensAt = votingOpensAt;
    }

    public Instant getVotingClosesAt() {
        return votingClosesAt;
    }

    public void setVotingClosesAt(Instant votingClosesAt) {
        this.votingClosesAt = votingClosesAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public org.example.commonmanager.model.User getWinner() {
        return winner;
    }

    public void setWinner(org.example.commonmanager.model.User winner) {
        this.winner = winner;
    }

    public boolean isManualOverride() {
        return manualOverride;
    }

    public void setManualOverride(boolean manualOverride) {
        this.manualOverride = manualOverride;
    }
}
