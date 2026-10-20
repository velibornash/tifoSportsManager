package org.tifo.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Table(name = "match_player_stats",
        indexes = {
                @Index(name = "ix_match_player_stats_player_match", columnList = "player_id,match_id"),
        })
@Entity(name = "MatchPlayerStats")
public class MatchPlayerStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Match match;

    @ManyToOne
    private Player player;

    private int goals;
    private int assists;
    private int yellowCards;
    private int redCards;
    private int minutesPlayed;

    /**
     * The slot this player actually occupied in this match.
     *
     * <p><b>Owner rule, 2026-10-12 (T-MATCH-8):</b> a player set to train in one slot is credited only
     * with the minutes he spent in <em>that</em> slot, while remaining free to be fielded anywhere. The
     * half about being fielded anywhere already holds and must keep holding; the half about which minutes
     * count could not be built at all, because nothing recorded the slot.
     *
     * <p>The simulation has carried the slot the whole time — {@code ProposalMatchOutcome.PlayerOutcome}
     * has a {@code role} and two other readers of it — and it was simply not written down here. So this is
     * a persistence change, not a simulation change.
     *
     * <p>Null means "not recorded": rows written before this column existed, and any match whose outcome
     * carried no role. Training treats null as "count every minute", because silently counting nothing for
     * every existing player would change how all of them develop.
     */
    @Column(name = "position_played")
    private Position positionPlayed;
    private int rating;
    private int interceptions;
    private int saves;
    private boolean cleanSheet;

    private Integer shots;
    private Integer passesAttempted;
    private Integer passesCompleted;
}