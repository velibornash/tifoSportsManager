package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.example.footballmanager.newLogic.model.Lineup;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "Match")
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
    private boolean homeResultRevealed = true;
    private boolean awayResultRevealed = true;
    private String homeFormation;
    private String awayFormation;

    @Column(columnDefinition = "text")
    private String eventJson;

    @Column(columnDefinition = "text")
    private String lineupJson;

    private Long replayId;
    private Boolean finished;

    public boolean isFinished() {
        return finished != null && finished;
    }

    public Boolean getFinished() {
        return finished;
    }

    public void setFinished(Boolean finished) {
        this.finished = finished;
    }

    @Transient
    private boolean userMatch;

    public boolean isUserMatch() { return userMatch; }
    public void setUserMatch(boolean userMatch) { this.userMatch = userMatch; }

    // Fluent accessors for MatchSimulator compatibility
    public Long id() { return this.id; }
    public Team homeTeam() { return homeTeam; }
    public Team awayTeam() { return awayTeam; }
}