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
 * One skill a player is being individually worked on, for one week (Sprint 4.1).
 *
 * <p>The headline training feature, and it is deliberately its own table rather than an entry in the
 * setup's JSON blob. The blob is a snapshot of a team's whole programme; a focus is a per-player,
 * per-week decision with a history. Keeping it as rows means "what did the manager work on with this
 * player in week 5" is a question the database can answer, and it survives the setup being rewritten
 * for week 6 — which is what actually happens, and is why the blob could not answer it.
 *
 * <p>One row per skill, so "one or two skills" is simply one or two rows.
 */
@Entity
@Getter
@Setter
public class PlayerTrainingFocus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    private Integer season;
    private Integer week;

    /**
     * The skill being worked on.
     *
     * <p>Any of the eight. A focus deliberately bypasses the role's allow-list, because the whole
     * point of an individual focus is that a manager may work on something unusual - a striker on
     * heading, a midfielder on goalkeeping - and the role buckets exist to give a sensible default,
     * not to forbid a decision.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SkillName skill;

    private Instant setAt = Instant.now();

    /** The pair that identifies a focus, for replacing one cleanly. */
    public static PlayerTrainingFocus of(Long teamId, Long playerId, Integer season, Integer week,
                                        SkillName skill) {
        PlayerTrainingFocus f = new PlayerTrainingFocus();
        f.setTeamId(teamId);
        f.setPlayerId(playerId);
        f.setSeason(season);
        f.setWeek(week);
        f.setSkill(skill);
        return f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerTrainingFocus other)) return false;
        return Objects.equals(playerId, other.playerId)
                && Objects.equals(season, other.season)
                && Objects.equals(week, other.week)
                && skill == other.skill;
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, season, week, skill);
    }
}
