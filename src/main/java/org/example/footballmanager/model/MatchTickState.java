package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "match_tick_states")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class MatchTickState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private Match match;

    @Column(name = "tick", nullable = false)
    private int tick;  // Tick number (e.g. 0..900)

    @Column(name = "minute")
    private int minute;  // Optional helper for minute-based queries

    @Column(name = "player_positions_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String playerPositionsJson;  // JSON array of PlayerPositionDTO

    @Column(name = "ball_position_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String ballPositionJson;     // JSON object of BallPositionDTO

    @Column(name = "current_carrier_id")
    private Integer currentCarrierId;    // Optional: id of the ball carrier at this tick

    @Column(name = "ball_in_transit", nullable = false)
    private boolean ballInTransit;      // NEW: whether ball is being passed/in flight

    @Column(name = "pending_receiver_id")
    private Integer pendingReceiverId;  // NEW: id of player who will receive the ball if ballInTransit

    // Convenience constructor
    public MatchTickState(Match match, int tick, String playersJson, String ballJson, Integer carrierId) {
        this(match, tick, playersJson, ballJson, carrierId, false, null);
    }

    // Updated constructor with ballInTransit
    public MatchTickState(Match match, int tick, String playersJson, String ballJson, Integer carrierId, boolean ballInTransit) {
        this(match, tick, playersJson, ballJson, carrierId, ballInTransit, null);
    }

    // Full constructor with pendingReceiverId
    public MatchTickState(Match match, int tick, String playersJson, String ballJson, Integer carrierId, boolean ballInTransit, Integer pendingReceiverId) {
        this.match = match;
        this.tick = tick;
        this.minute = tick / 27;
        this.playerPositionsJson = playersJson;
        this.ballPositionJson = ballJson;
        this.currentCarrierId = carrierId;
        this.ballInTransit = ballInTransit;
        this.pendingReceiverId = pendingReceiverId;
    }
}
