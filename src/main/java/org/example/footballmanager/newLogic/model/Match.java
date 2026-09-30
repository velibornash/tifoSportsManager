package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;


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

    /**
     * A knockout tie that finished level and was settled from the spot: kicks taken by each side.
     *
     * <p>Null means no shootout, and the two cases are different facts. A league match drawn 2-2 has no
     * shootout and never will. A cup tie drawn 2-2 has one, and without recording it the tie has no
     * winner — which is how a knockout round stopped being drawable: the winner lookup returned null,
     * the club dropped out of the competition, and the next round came up short.
     *
     * <p>Separate columns rather than added to the scoreline. A cup tie that finished 1-1 and was won 4-3
     * on penalties is a 1-1 match, and writing the shootout into the goal columns would report it as a
     * 5-4 win to anyone reading the table, the replay or the scoreline on the page.
     */
    private Integer homePenaltyGoals;
    private Integer awayPenaltyGoals;
    private double possessionHome;
    private double possessionAway;
    private LocalDateTime matchDate;
    private Integer seasonYear;
    private Integer roundNumber;
    private Integer weekNumber;
    // The calendar day this match was played on, copied from its fixture. Without it the only date a
    // match carried was the wall-clock one, so "which day of the season was that?" had no answer for a
    // played match - the fixture knew, the match did not.
    private Integer dayNumber;
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

    @Column(columnDefinition = "text")
    private String statsJson;

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