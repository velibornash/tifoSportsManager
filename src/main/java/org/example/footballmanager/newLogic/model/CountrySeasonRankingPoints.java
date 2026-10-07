package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One national side's ranking points for one season (P0-RANK-1).
 *
 * <p>The national counterpart of {@link ClubSeasonRankingPoints}, and a separate table rather than a
 * nullable both-subjects table with one of the two foreign keys always null — an invariant the database
 * cannot enforce and every reader has to check.
 *
 * <p>{@link NationalTeamLevel} is part of the key, so a country's senior and U-21 sides are two
 * independent totals. They are never pooled: a nation is not one entity that scored well at both levels
 * any more than a club is one entity that also fields a reserve side.
 */
@Entity
@Table(name = "country_season_ranking_points",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_country_level_season_points",
                columnNames = {"country_id", "level", "season_year"}),
        indexes = @Index(name = "ix_country_level_season_points_season", columnList = "season_year"))
public class CountrySeasonRankingPoints {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @jakarta.persistence.JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    private NationalTeamLevel level;

    @Column(name = "season_year", nullable = false)
    private int seasonYear;

    @Column(name = "points", nullable = false)
    private double points;

    /**
     * The season's one-off achievement bonuses, apart from the match points.
     *
     * <p>Separate for the same reason as the club ledger's: so the bonus pass can set rather than add,
     * and pressing it twice does not pay two trophies.
     */
    @Column(name = "bonus_points", nullable = false)
    private double bonusPoints = 0.0;

    protected CountrySeasonRankingPoints() {
    }

    public CountrySeasonRankingPoints(Country country, NationalTeamLevel level, int seasonYear, double points) {
        this.country = country;
        this.level = level;
        this.seasonYear = seasonYear;
        this.points = points;
    }

    public Long getId() {
        return id;
    }

    public Country getCountry() {
        return country;
    }

    public NationalTeamLevel getLevel() {
        return level;
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