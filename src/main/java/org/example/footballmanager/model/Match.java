package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.example.footballmanager.model.event.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    @OneToOne(cascade = CascadeType.PERSIST)
    private Lineup homeLineup;

    @OneToOne(cascade = CascadeType.PERSIST)
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
    @OneToMany(cascade = CascadeType.ALL)
    private List<MatchEvent> allMatchEvents = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<GoalEvent> goals = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<ShotOnTargetEvent> shotsOnTarget = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<ShotOffTargetEvent> shotsOffTarget = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<YellowCardEvent> yellowCards = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<RedCardEvent> redCards = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<PenaltyEvent> penalties = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<FreeKickEvent> freeKicks = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<OffsideEvent> offsides = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<InjuryEvent> injuries = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<ChanceEvent> chances = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<SubstitutionEvent> substitutions = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<VARReviewEvent> varReviews = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<CornerEvent> corners = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    private List<MatchEndedEvent> matchEndEvents = new ArrayList<>();

    @Lob
    private String eventJson;
}
