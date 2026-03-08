package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.example.footballmanager.model.event.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team awayTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_lineup_id")
    private Lineup homeLineup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "away_lineup_id")
    private Lineup awayLineup;

    private int homeGoals;
    private int awayGoals;
    private double possessionHome;
    private double possessionAway;
    private LocalDateTime matchDate;
    private Integer seasonYear;
    private Integer roundNumber;
    private Integer weekNumber;
    @ManyToOne(fetch = FetchType.LAZY)
    private Competition competition;

    @ManyToOne(fetch = FetchType.LAZY)
    private Stadium stadium;

    private Integer attendance;

    private boolean played;
    private boolean started;
    private String homeFormation;
    private String awayFormation;

    @OneToMany(mappedBy = "match", fetch = FetchType.LAZY)
    private Set<MatchEvent> allMatchEvents = new HashSet<>();

    @Lob
    private String eventJson;
    private Boolean finished;

    // --- helper metode po tipu eventa ---
    public Set<GoalEvent> getGoals() {
        Set<GoalEvent> goals = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof GoalEvent) goals.add((GoalEvent) e);
        return goals;
    }

    public Set<ChanceEvent> getChances() {
        Set<ChanceEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof ChanceEvent) list.add((ChanceEvent) e);
        return list;
    }

    public Set<YellowCardEvent> getYellowCards() {
        Set<YellowCardEvent> cards = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof YellowCardEvent) cards.add((YellowCardEvent) e);
        return cards;
    }

    public Set<RedCardEvent> getRedCards() {
        Set<RedCardEvent> cards = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof RedCardEvent) cards.add((RedCardEvent) e);
        return cards;
    }

    public Set<PenaltyEvent> getPenalties() {
        Set<PenaltyEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof PenaltyEvent) list.add((PenaltyEvent) e);
        return list;
    }

    public Set<FreeKickEvent> getFreeKicks() {
        Set<FreeKickEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof FreeKickEvent) list.add((FreeKickEvent) e);
        return list;
    }

    public Set<OffsideEvent> getOffsides() {
        Set<OffsideEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof OffsideEvent) list.add((OffsideEvent) e);
        return list;
    }

    public Set<InjuryEvent> getInjuries() {
        Set<InjuryEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof InjuryEvent) list.add((InjuryEvent) e);
        return list;
    }

    public Set<SubstitutionEvent> getSubstitutions() {
        Set<SubstitutionEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof SubstitutionEvent) list.add((SubstitutionEvent) e);
        return list;
    }

    public Set<VARReviewEvent> getVarReviews() {
        Set<VARReviewEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof VARReviewEvent) list.add((VARReviewEvent) e);
        return list;
    }

    public Set<CornerEvent> getCorners() {
        Set<CornerEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof CornerEvent) list.add((CornerEvent) e);
        return list;
    }

    public Set<ShotOnTargetEvent> getShotsOnTarget() {
        Set<ShotOnTargetEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof ShotOnTargetEvent) list.add((ShotOnTargetEvent) e);
        return list;
    }

    public Set<ShotOffTargetEvent> getShotsOffTarget() {
        Set<ShotOffTargetEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof ShotOffTargetEvent) list.add((ShotOffTargetEvent) e);
        return list;
    }

    public Set<MatchEndedEvent> getMatchEndEvents() {
        Set<MatchEndedEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof MatchEndedEvent) list.add((MatchEndedEvent) e);
        return list;
    }

    public Set<MatchStartEvent> getMatchStartEvents() {
        Set<MatchStartEvent> list = new HashSet<>();
        for (MatchEvent e : allMatchEvents) if (e instanceof MatchStartEvent) list.add((MatchStartEvent) e);
        return list;
    }

    public boolean isFinished() {
        return finished != null && finished;
    }

    public Boolean getFinished() {
        return finished;
    }

    public void setFinished(Boolean finished) {
        this.finished = finished;
    }
}
