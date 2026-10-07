package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** Per-season ranking-point subtotals for clubs (P0-RANK-1). */
public interface ClubSeasonRankingPointsRepository extends JpaRepository<ClubSeasonRankingPoints, Long> {

    /** Every season a club has a subtotal for, which is what the rolling window reads. */
    List<ClubSeasonRankingPoints> findByTeamId(Long teamId);

    Optional<ClubSeasonRankingPoints> findByTeamIdAndSeasonYear(Long teamId, int seasonYear);

    void deleteByTeamId(Long teamId);

    /**
     * Every ledger row for the clubs of one country, in one read.
     *
     * <p>By the club's competition's country, which is how a club belongs to a country here - a club is
     * anything whose competition is a league, and the pyramid never sets {@code Team.type}, so the type
     * cannot be used and the country comes from the division.
     */
    @Query("select c from ClubSeasonRankingPoints c where c.team.competition.country.id = :countryId")
    List<ClubSeasonRankingPoints> findAllByCompetitionCountryId(Long countryId);
}
