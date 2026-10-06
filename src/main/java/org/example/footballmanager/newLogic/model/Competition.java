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

    /**
     * Which national side this competition is for, or null for a club competition.
     *
     * <p>Senior and U-21 are separate competitions rather than tabs on one, so a competition has to
     * say which it is. Reading it off the name is the alternative and is how "Serbia U-21 Women"
     * ends up rated as a senior side.
     */
    @Enumerated(EnumType.STRING)
    private NationalTeamLevel nationalLevel;

    /**
     * Whether this competition is a qualifying round or the tournament proper, or null.
     *
     * <p>It exists so a national rating can be weighted by stage without parsing a name: the owner
     * wants a World Cup match to count more than a qualifying one, and a world cup and a qualifier
     * are both {@code TOURNAMENT} rows differing only in this.
     */
    @Enumerated(EnumType.STRING)
    private NationalStage nationalStage;
}