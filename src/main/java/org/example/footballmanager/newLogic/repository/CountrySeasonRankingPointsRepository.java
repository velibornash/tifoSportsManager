package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Per-season ranking-point subtotals for national sides, senior and U-21 separately (P0-RANK-1). */
public interface CountrySeasonRankingPointsRepository
        extends JpaRepository<CountrySeasonRankingPoints, Long> {

    /** Only this level's seasons. A country's senior and U-21 totals must never be added together. */
    List<CountrySeasonRankingPoints> findByCountryIdAndLevel(Long countryId, NationalTeamLevel level);

    Optional<CountrySeasonRankingPoints> findByCountryIdAndLevelAndSeasonYear(
            Long countryId, NationalTeamLevel level, int seasonYear);

    void deleteByCountryIdAndLevel(Long countryId, NationalTeamLevel level);

    /** Every country's rows at one level, in one read — for ranking the whole world. */
    List<CountrySeasonRankingPoints> findAllByLevel(NationalTeamLevel level);

    boolean existsByCountryIdAndLevel(Long countryId, NationalTeamLevel level);
}
