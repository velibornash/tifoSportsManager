package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * How long and how hard a player worked in one zone, in one match (owner, 2026-09-29).
 *
 * <p>One row per player per zone per match. Not a total on the player, because the whole point is that
 * the breakdown matters: ninety minutes in midfield is not ninety minutes spread across the pitch, and
 * a single fatigue number cannot tell those apart.
 *
 * <p>{@code minutes} and {@code intensity} are kept apart on purpose. A player can cover a lot of
 * ground slowly (intensity 0.3, 100 minutes) or sprint repeatedly (intensity 0.9, 40 minutes), and
 * those leave very different traces on a leg.
 */
@Entity
@Table(name = "player_zone_load",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_zone_load_player_match_zone",
                columnNames = {"player_id", "match_id", "zone"}),
        indexes = @Index(name = "ix_zone_load_player", columnList = "player_id"))
public class PlayerZoneLoad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @jakarta.persistence.JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @jakarta.persistence.JoinColumn(name = "match_id", nullable = false)
    private Match match;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone", nullable = false, length = 24)
    private Zone zone;

    @Column(name = "minutes", nullable = false)
    private double minutes;

    /** 0 to 1. Kept separate from minutes on purpose - see the class comment. */
    @Column(name = "intensity", nullable = false)
    private double intensity;

    public Long getId() {
        return id;
    }

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public Match getMatch() {
        return match;
    }

    public void setMatch(Match match) {
        this.match = match;
    }

    public Zone getZone() {
        return zone;
    }

    public void setZone(Zone zone) {
        this.zone = zone;
    }

    public double getMinutes() {
        return minutes;
    }

    public void setMinutes(double minutes) {
        this.minutes = minutes;
    }

    public double getIntensity() {
        return intensity;
    }

    public void setIntensity(double intensity) {
        this.intensity = intensity;
    }

    /**
     * The work this row represents, in equivalent full-effort minutes.
     *
     * <p>Minutes scaled by intensity and by the zone's own work rate, so a minute in a busy central
     * area counts for more than a minute holding a shape at the back.
     */
    public double effectiveMinutes() {
        return minutes * intensity * (zone == null ? 1.0 : zone.workRate());
    }
}
