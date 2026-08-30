package org.example.basketballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bb_players")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Position position;

    @Column(nullable = false)
    private Integer height;

    @Column(nullable = false)
    private Integer weight;

    @Column(name = "jersey_number")
    private Integer jerseyNumber;

    @Column(nullable = false)
    @Builder.Default
    private Boolean injured = false;

    @Column(name = "injury_days_remaining")
    @Builder.Default
    private Integer injuryDaysRemaining = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer fatigue = 0;

    @Column(name = "skill_pace", nullable = false)
    private Integer skillPace;

    @Column(name = "skill_steals", nullable = false)
    private Integer skillSteals;

    @Column(name = "skill_blocks", nullable = false)
    private Integer skillBlocks;

    @Column(name = "skill_free_throws", nullable = false)
    private Integer skillFreeThrows;

    @Column(name = "skill_two_pt_shot", nullable = false)
    private Integer skillTwoPtShot;

    @Column(name = "skill_three_pt_shot", nullable = false)
    private Integer skillThreePtShot;

    @Column(name = "skill_rebounding", nullable = false)
    private Integer skillRebounding;

    @Column(name = "skill_playmaking", nullable = false)
    private Integer skillPlaymaking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private BbTeam team;

    @Embedded
    private BbPlayerStats stats;

    public enum Position {
        PG, SG, SF, PF, C
    }

    public int getOverall() {
        return switch (position) {
            case PG -> (skillPace * 20 + skillSteals * 10 + skillBlocks * 5 + skillFreeThrows * 5 + skillTwoPtShot * 10 + skillThreePtShot * 25 + skillRebounding * 5 + skillPlaymaking * 25) / 100;
            case SG -> (skillPace * 15 + skillSteals * 10 + skillBlocks * 5 + skillFreeThrows * 10 + skillTwoPtShot * 20 + skillThreePtShot * 25 + skillRebounding * 5 + skillPlaymaking * 10) / 100;
            case SF -> (skillPace * 15 + skillSteals * 10 + skillBlocks * 10 + skillFreeThrows * 5 + skillTwoPtShot * 25 + skillThreePtShot * 10 + skillRebounding * 15 + skillPlaymaking * 10) / 100;
            case PF -> (skillPace * 10 + skillSteals * 5 + skillBlocks * 20 + skillFreeThrows * 5 + skillTwoPtShot * 25 + skillThreePtShot * 5 + skillRebounding * 25 + skillPlaymaking * 5) / 100;
            case C -> (skillPace * 5 + skillSteals * 5 + skillBlocks * 25 + skillFreeThrows * 10 + skillTwoPtShot * 20 + skillThreePtShot * 0 + skillRebounding * 30 + skillPlaymaking * 5) / 100;
        };
    }
}
