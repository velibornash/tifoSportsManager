package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity(name = "Competition")
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

    /**
     * How many non-EU players this competition lets a club register.
     *
     * <p>Serbia's rule is four in the top flight and fewer below, and it is the constraint that
     * shapes a season's transfer planning: a club with four foreign players cannot sign a fifth
     * however badly it wants him, so the quota has to be part of the decision rather than a
     * post-hoc validation error.
     *
     * <p>Null means "not set", in which case {@code WorkPermitService} falls back to the tier
     * default. See the open question in sprintBacklog about the exact per-tier figures.
     */
    private Integer foreignPlayerLimit;
    private Integer relegationSpots;
    private Integer reputationWeight;
    private Boolean hasSeeding;
    private Integer seededTeamsCount;
}