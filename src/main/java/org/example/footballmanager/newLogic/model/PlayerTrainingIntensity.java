package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Objects;

/**
 * A player's intensity override for one week (Sprint 4.2).
 *
 * <p>The club sets a default for the week; a row here means one player is working at a different
 * level from his team-mates. That is the case a manager actually needs: the squad rests after a
 * European night, except the young striker who needs minutes.
 *
 * <p>A row per player per week, so "what was he on in week 5" stays answerable — the same reasoning as
 * {@link PlayerTrainingFocus}, and for the same reason.
 */
@Entity
@Getter
@Setter
public class PlayerTrainingIntensity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    private Integer season;
    private Integer week;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrainingIntensity intensity;

    private Instant setAt = Instant.now();

    public static PlayerTrainingIntensity of(Long teamId, Long playerId, Integer season, Integer week,
                                             TrainingIntensity intensity) {
        PlayerTrainingIntensity i = new PlayerTrainingIntensity();
        i.setTeamId(teamId);
        i.setPlayerId(playerId);
        i.setSeason(season);
        i.setWeek(week);
        i.setIntensity(intensity);
        return i;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerTrainingIntensity other)) return false;
        return Objects.equals(playerId, other.playerId)
                && Objects.equals(season, other.season)
                && Objects.equals(week, other.week);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, season, week);
    }
}
