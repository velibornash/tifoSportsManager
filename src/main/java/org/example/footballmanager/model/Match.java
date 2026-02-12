package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.example.footballmanager.model.event.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Team homeTeam;

    @ManyToOne
    private Team awayTeam;

    @ManyToOne
    @JoinColumn(name = "home_lineup_id")
    private Lineup homeLineup;

    @ManyToOne
    @JoinColumn(name = "away_lineup_id")
    private Lineup awayLineup;

    private int homeGoals;
    private int awayGoals;

    private LocalDateTime matchDate;

    private boolean played;
    private boolean started;

    // Formacije kao string
    private String homeFormation;
    private String awayFormation;

    // Helper: sve evente po tipu
    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<MatchEvent> allMatchEvents = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<GoalEvent> goals = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ShotOnTargetEvent> shotsOnTarget = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ShotOffTargetEvent> shotsOffTarget = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<YellowCardEvent> yellowCards = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<RedCardEvent> redCards = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PenaltyEvent> penalties = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<FreeKickEvent> freeKicks = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<OffsideEvent> offsides = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<InjuryEvent> injuries = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ChanceEvent> chances = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<SubstitutionEvent> substitutions = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<VARReviewEvent> varReviews = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<CornerEvent> corners = new HashSet<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<MatchEndedEvent> matchEndEvents = new HashSet<>();

    @Lob
    private String eventJson;
}
