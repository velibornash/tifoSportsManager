package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Per-season ranking-point subtotals for clubs (P0-RANK-1). */
public interface ClubSeasonRankingPointsRepository extends JpaRepository<ClubSeasonRankingPoints, Long> {

    /** Every season a club has a subtotal for, which is what the rolling window reads. */
    List<ClubSeasonRankingPoints> findByTeamId(Long teamId);

    Optional<ClubSeasonRankingPoints> findByTeamIdAndSeasonYear(Long teamId, int seasonYear);

    void deleteByTeamId(Long teamId);
}
