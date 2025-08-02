package org.example.footballmanager.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Tactics;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
public class Match {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Team homeTeam;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Team awayTeam;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Lineup homeLineup;

    @ManyToOne(cascade = CascadeType.PERSIST)
    private Lineup awayLineup;

    private Integer homeGoals = 0;
    private Integer awayGoals = 0;

    private LocalDateTime matchDate;

    private String homeFormation;
    private String awayFormation;

    @Column(columnDefinition = "TEXT")
    private String eventJson; // Polje za čuvanje JSON događaja

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<GoalEvent> goals = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<FreeKickEvent> freeKicks = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ShotOnTargetEvent> shotsOnTarget = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ShotOffTargetEvent> shotsOffTarget = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<PenaltyEvent> penalties = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<YellowCardEvent> yellowCards = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<RedCardEvent> redCards = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ChanceEvent> chances = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<InjuryEvent> injuries = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<SubstitutionEvent> substitutions = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<CornerEvent> corners = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<VARReviewEvent> vars = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<OffsideEvent> offsides = new ArrayList<>();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<MatchEndedEvent> ended = new ArrayList<>();

    public List<GoalEvent> getGoalEvents() {
        return goals.stream()
                .filter(e -> e instanceof GoalEvent)
                .map(e -> (GoalEvent) e)
                .toList();
    }

    @Transient
    public List<MatchEvent> getAllMatchEvents() {
        List<MatchEvent> all = new ArrayList<>();
        all.addAll(goals);
        all.addAll(freeKicks);
        all.addAll(shotsOnTarget);
        all.addAll(shotsOffTarget);
        all.addAll(penalties);
        all.addAll(yellowCards);
        all.addAll(redCards);
        all.addAll(chances);
        all.addAll(injuries);
        all.addAll(substitutions);
        all.addAll(corners);
        all.addAll(vars);
        all.addAll(offsides);
        all.addAll(ended);
        return all;
    }

    private boolean played = false;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "aggression", column = @Column(name = "home_tactics_aggression")),
            @AttributeOverride(name = "pressing", column = @Column(name = "home_tactics_pressing")),
            @AttributeOverride(name = "counterAttack", column = @Column(name = "home_tactics_counter_attack")),
            @AttributeOverride(name = "ballControl", column = @Column(name = "home_tactics_ball_control"))
    })
    private Tactics homeTactics;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "aggression", column = @Column(name = "away_tactics_aggression")),
            @AttributeOverride(name = "pressing", column = @Column(name = "away_tactics_pressing")),
            @AttributeOverride(name = "counterAttack", column = @Column(name = "away_tactics_counter_attack")),
            @AttributeOverride(name = "ballControl", column = @Column(name = "away_tactics_ball_control"))
    })
    private Tactics awayTactics;
}