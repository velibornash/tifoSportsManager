package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * One player in one manager's fantasy squad (owner, 2026-10-17).
 *
 * <p><b>Tier and position are not stored, and that is deliberate.</b> Both are read from the player every
 * time the squad is checked, so the three-per-tier rule is applied against the pyramid as it stands. A
 * stored tier would let a promoted club's players keep counting as a cheap fifth-division slot, which is
 * the obvious way to break the rule the owner cares most about. Within a fantasy season neither moves —
 * promotion happens between seasons and fantasy resets with them.
 *
 * <p><b>{@link #purchasePrice} is stored, because it is a fact about this manager and not about the
 * player.</b> It is what he paid. A player's current price is derived and moves with his rating, and
 * keeping the purchase price is what makes a later price rise a gain rather than a silent rewrite of what
 * the squad cost.
 */
@Getter
@Setter
@Entity
@Table(name = "fantasy_pick",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fantasy_pick_team_player",
                columnNames = {"fantasy_team_id", "player_id"}),
        indexes = @Index(name = "idx_fantasy_pick_team", columnList = "fantasy_team_id"))
public class FantasyPick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "fantasy_team_id", nullable = false)
    private FantasyTeam fantasyTeam;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** In the eleven. The bench is one reserve goalkeeper and three outfield substitutes. */
    @Column(name = "starter", nullable = false)
    private boolean starter;

    /**
     * Where he sits on the bench: 0 is the reserve goalkeeper, 1 to 3 are the substitutes in the order
     * they would come on. Null for a starter, who has no place on a bench.
     */
    @Column(name = "bench_order")
    private Integer benchOrder;

    /** The captain scores double. Exactly one pick carries this, and he has to be a starter. */
    @Column(name = "captain", nullable = false)
    private boolean captain;

    /** What this manager paid for him. A fact about the squad, not about the player. */
    @Column(name = "purchase_price", nullable = false)
    private double purchasePrice;
}
