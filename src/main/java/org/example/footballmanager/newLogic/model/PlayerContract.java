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

import java.time.Instant;

/**
 * A player's contract (Sprint 3.1).
 *
 * <p>There was no contract at all: a player's wage was a field on the player and nothing tracked
 * when it ended. That is why the free-agent market was <em>impossible by construction</em> — a
 * transfer required a selling team, and a player could never stop having one.
 *
 * <p>Expiry is what makes the market move. A contract that ends turns the player into a free agent
 * who can be signed by anyone, which is the mechanism that lets a club be rebuilt rather than only
 * bought from.
 */
@Data
@Entity(name = "PlayerContract")
public class PlayerContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Player player;

    /** The club that holds the contract. Null means the player is a free agent. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    private Double weeklyWage;

    /** Contract length in months when signed. */
    private Integer lengthMonths;

    private Integer signedSeason;

    /** Season the contract expires at the end of. */
    private Integer expirySeason;

    /**
     * Release clause, in euros. Null means no clause, which is a clause the selling club will not
     * accept — a real distinction, not a null check.
     */
    private Double releaseClause;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SquadRole squadRole;

    private Integer squadNumber;

    /** Set when this player is on loan, naming the club he belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Team onLoanFrom;

    private Instant signedAt = Instant.now();

    public PlayerContract() { }

    /** Whether this contract has run out at the end of the given season. */
    public boolean isExpired(int season) {
        return expirySeason != null && season > expirySeason;
    }

    /**
     * Whether a buying club can simply pay the release clause instead of negotiating.
     *
     * <p>A club with no clause and one that declines to discuss are different, so a null clause is
     * not treated as "free to take".
     */
    public boolean hasReleaseClause() {
        return releaseClause != null && releaseClause > 0;
    }

    /** Total wages still to be paid on this contract. */
    public double remainingWages(int currentSeason) {
        if (weeklyWage == null || expirySeason == null) return 0;
        int seasonsLeft = expirySeason - currentSeason;
        return Math.max(0, seasonsLeft) * weeklyWage * 52.0;
    }
}
