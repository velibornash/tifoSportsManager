package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The slot a player trains in, and whether that training is advanced.
 *
 * <p><b>Owner rule, 2026-10-12 (T-MATCH-8):</b> *"ako je igrač na trening strani stavljen da trenira u
 * npr MID slotu ... u formulu za računanje treninga mu ulaze SAMO minuti provedeni na MID slotu ali on
 * apsolutno može da igra na bilo kom slotu u meču."*
 *
 * <p>Two halves, and only one of them is about this class. The half about **which minutes count** is here.
 * The other half — a manager may still field the player anywhere — is a property of nothing at all: it
 * must stay true, and a test exists so that fixing this half cannot quietly break it.
 *
 * <p><b>This was a bare POJO for its whole life</b>, so the `training` table it was drawn for sat in the
 * database with no entity, no repository and no reader: a player's training position could be written
 * nowhere and counted for nothing. That is the whole of why training credited a player with minutes played
 * in any slot — there was no slot recorded to compare against.
 *
 * <p>Mapped onto the existing `training` table rather than a new one: the columns are already there, one
 * per player, and renaming them would be a migration for no gain.
 */
@Entity
@Table(name = "training")
public class TrainingAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The player this assignment is about. One row per player — the table's unique constraint says so. */
    @Column(name = "player_id")
    private Long playerId;

    /**
     * The slot his training counts toward.
     *
     * <p>Stored in the column the table already has, {@code formation}. The name is older than the rule:
     * it was written for a formation string and now holds one {@link Position}. Left as it is rather than
     * migrated, because a rename buys nothing a reader cannot work out and costs a migration.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "formation")
    private Position trainingPosition;

    /** Advanced training: the same slot, done properly. The case the owner singled out. */
    @Column(name = "advanced")
    private boolean advanced;

    /**
     * Minutes he spent in the training slot last week.
     *
     * <p>Derived, never stored: it is whatever {@code MatchPlayerStats} says once the slot has been
     * filtered by {@link #trainingPosition}. Writing it down would be a second copy of a number the
     * match records already hold, and it would go stale the moment a result was corrected.
     */
    @Transient
    private int minutesPlayedLastWeek;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public void setPlayerId(Long playerId) {
        this.playerId = playerId;
    }

    public Position getTrainingPosition() {
        return trainingPosition;
    }

    public void setTrainingPosition(Position trainingPosition) {
        this.trainingPosition = trainingPosition;
    }

    public boolean isAdvanced() {
        return advanced;
    }

    public void setAdvanced(boolean advanced) {
        this.advanced = advanced;
    }

    public int getMinutesPlayedLastWeek() {
        return minutesPlayedLastWeek;
    }

    public void setMinutesPlayedLastWeek(int minutesPlayedLastWeek) {
        this.minutesPlayedLastWeek = minutesPlayedLastWeek;
    }
}
