package org.example.footballmanager.newLogic.model.tactics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.example.footballmanager.newLogic.model.Team;

import java.time.LocalDateTime;

/**
 * One named tactic belonging to a club.
 *
 * <p><b>Why this exists when {@code TeamTacticsProfile} already did.</b> That table carries a unique
 * constraint on {@code team_id}, so a club can hold exactly one profile. A tactic library is the opposite:
 * a manager keeps a 4-4-2 for the derby and a 4-3-3 for the cup, and picks between them per match. With
 * one row per club there is nothing to pick, so the per-match selection downstream has nothing to select
 * from.
 *
 * <p><b>The default.</b> Exactly one tactic per club is the default, which is what a match falls back to
 * when the manager has chosen nothing for that fixture. "The manager forgetting" is not an error state —
 * the game has to play — so the default is a normal part of the model rather than an exception to it.
 *
 * <p><b>One default per club is enforced by the service</b>, which clears the previous default in the same
 * transaction that sets the next. Not by a partial unique index: that is PostgreSQL-specific syntax, the
 * test database is H2, and a constraint one engine understands and another silently ignores is worse than
 * an invariant written down and tested. {@link #isDefault} is the marker; the rule around it lives in
 * {@code TacticLibraryService} beside the code that has to honour it.
 *
 * <p><b>Names are unique per club</b>, and that is a database constraint because it is the one that makes
 * the seeding idempotent: a club cannot end up with two tactics called "4-3-3" because a seeding job was
 * run twice.
 */
@Data
@Entity
@Table(name = "tactic",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tactic_team_name", columnNames = {"team_id", "name"})
        },
        indexes = {
                // Every read of a club's tactics goes through this, and the simulation reads it per match.
                @Index(name = "ix_tactic_team", columnList = "team_id")
        })
public class Tactic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    /** What the manager calls it in the list: "4-4-2 vs Javor", "Cup 4-3-3". */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String formation;

    @Column(nullable = false)
    private String style;

    @Column(name = "rules_json", columnDefinition = "text")
    private String rulesJson;

    @Column(name = "set_pieces_json", columnDefinition = "text")
    private String setPiecesJson;

    /** The tactic a match uses when the manager has chosen nothing for that fixture. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultTactic;

    @Column(nullable = false)
    private Long version = 1L;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}