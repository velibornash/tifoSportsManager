package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One club's ranking points for one season (P0-RANK-1).
 *
 * <p><b>Why a ledger at all.</b> The displayed total is a rolling window over four seasons, so the
 * per-season subtotals have to exist somewhere — they cannot be summed from the matches on demand
 * without re-running the whole replay every time a ranking is drawn.
 *
 * <p><b>It is a cache of a replay, not the source of truth.</b> {@code ClubRatingService} already walks
 * every played match in order and rebuilds ratings from scratch, which is why the existing ratings need
 * no ledger: they are recomputed rather than accumulated. The same is true here — this table is
 * rewritten from the match history by {@code P0-RANK-2}, so a corrupt row repairs itself on the next
 * run instead of being permanent.
 *
 * <p>{@code points} is a {@code double}, not an integer, because the owner's division weights
 * ({@code 0.85}, {@code 0.70}…) make a tier-3 result {@code 40 × 0.70 = 28.0} and a 1.25 competition
 * makes it {@code 50.0}. Rounding to whole points here would throw away the arithmetic the system is
 * built on.
 *
 * <p>Unique on (club, season): one row per club per season, so a replay that runs twice cannot leave
 * two rows to be added together — which is the exact bug class this repository keeps meeting.
 */
@Entity
@Table(name = "club_season_ranking_points",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_club_season_points",
                columnNames = {"team_id", "season_year"}),
        indexes = @Index(name = "ix_club_season_points_season", columnList = "season_year"))
public class ClubSeasonRankingPoints {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @jakarta.persistence.JoinColumn(name = "team_id", nullable = false)
    private Team team;

    /** The season this subtotal belongs to. Seasons are counted from 1 and there is no calendar year. */
    @Column(name = "season_year", nullable = false)
    private int seasonYear;

    /** That season's points, already scaled by competition and division. Not yet windowed. */
    @Column(name = "points", nullable = false)
    private double points;

    /**
     * The season's one-off achievement bonuses, kept <b>apart from the match points</b>.
     *
     * <p>Separate so the bonus pass can be <b>set</b> rather than added. It was originally added into the
     * same column, and pressing the button twice paid the trophy twice — 210.0 then 420.0 — because
     * there was no way to tell a season's match points from a bonus that had already been paid. One
     * column for each kind of points makes both re-runnable: the replay overwrites match points, the
     * bonus pass overwrites bonuses, and neither disturbs the other.
     */
    @Column(name = "bonus_points", nullable = false)
    private double bonusPoints = 0.0;

    protected ClubSeasonRankingPoints() {
    }

    public ClubSeasonRankingPoints(Team team, int seasonYear, double points) {
        this.team = team;
        this.seasonYear = seasonYear;
        this.points = points;
    }

    public Long getId() {
        return id;
    }

    public Team getTeam() {
        return team;
    }

    public int getSeasonYear() {
        return seasonYear;
    }

    public double getPoints() {
        return points;
    }

    public void setPoints(double points) {
        this.points = points;
    }

    public double getBonusPoints() {
        return bonusPoints;
    }

    public void setBonusPoints(double bonusPoints) {
        this.bonusPoints = bonusPoints;
    }

    /** Match points plus achievements: what the season is worth. */
    public double total() {
        return points + bonusPoints;
    }
}