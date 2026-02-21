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
@NoArgsConstructor          // dodaje prazan konstruktor
@AllArgsConstructor         // dodaje konstruktor sa svim poljima
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
    private int tick;  // broj tick-a (npr. 0..900)

    @Column(name = "minute")
    private int minute;  // opciono, za lakše pretrage po minutu

    @Column(name = "player_positions_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)                  // ← OVO JE KLJUČNO
    private String playerPositionsJson;  // JSON niz PlayerPositionDTO

    @Column(name = "ball_position_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)                  // ← OVO JE KLJUČNO
    private String ballPositionJson;     // JSON objekat BallPositionDTO

    @Column(name = "current_carrier_id")
    private Integer currentCarrierId;    // opciono – ID nosioca lopte u tom trenutku


    // Konstruktor za lakše kreiranje
    public MatchTickState(Match match, int tick, String playersJson, String ballJson, Integer carrierId) {
        this.match = match;
        this.tick = tick;
        this.minute = tick / 10;
        this.playerPositionsJson = playersJson;
        this.ballPositionJson = ballJson;
        this.currentCarrierId = carrierId;
    }
}