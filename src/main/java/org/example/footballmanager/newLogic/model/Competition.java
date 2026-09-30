package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity(name = "Competition")
@Table(indexes = {
        @Index(name = "ix_competition_country_type", columnList = "country_id,type"),
        @Index(name = "ix_competition_tier", columnList = "type,tier")
})
@Getter
@Setter
public class Competition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    private CompetitionType type;
    @Enumerated(EnumType.STRING)
    private CompetitionScope scope;
    @Enumerated(EnumType.STRING)
    private CompetitionTeamType teamType;
    @ManyToOne(fetch = FetchType.EAGER)
    private Country country;
    private Integer tier;
    private Integer divisionLevel;
    private Integer teamsPerCompetition;
    private Boolean hasPlayoff;
    private Boolean hasPlayout;
    private Integer promotionSpots;

    private Integer relegationSpots;
    private Integer reputationWeight;
    private Boolean hasSeeding;
    private Integer seededTeamsCount;
}