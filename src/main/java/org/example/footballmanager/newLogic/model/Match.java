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
@Table(name = "match", indexes = {
        // Four indexes, each with one named query that needed it and a measured before/after.
        // `ddl-auto=update` builds them from here; tools/create-match-indexes.sql is the same four
        // statements as CREATE INDEX CONCURRENTLY, for a database that already has rows.
        //
        // FindByCompetitionIdAndSeasonYear - the top-scorers and top-assists pages, per league.
        // 158.7 ms -> 0.19 ms. Both columns are equality, so their order is free; competition_id
        // leads because it is the selective one and the usual way in.
        @Index(name = "ix_match_competition_season", columnList = "competition_id, season_year"),
        // FindBySeasonYearAndWeekNumber - GoalEventRepository walks a season twelve times, once a week.
        // 156.4 ms -> 27.8 ms. Cannot be folded into the index above: this query has no competition_id,
        // and a season is 89,280 rows here, so leading on season_year alone would not be selective.
        @Index(name = "ix_match_season_week", columnList = "season_year, week_number"),
        // FindByHomeTeamIdOrAwayTeamId(AndPlayedTrue...) - the club's own match history, a request path.
        // 170.3 ms -> 0.26 ms. match_date trails because the same index serves the history page's
        // ORDER BY match_date DESC, so the rows arrive in order instead of being sorted.
        @Index(name = "ix_match_home_team_date", columnList = "home_team_id, match_date"),
        // The away half of the same OR. A predicate of `home = ? OR away = ?` needs an index on each
        // side; with only the home one the planner falls back to scanning the table.
        @Index(name = "ix_match_away_team_date", columnList = "away_team_id, match_date")
})
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