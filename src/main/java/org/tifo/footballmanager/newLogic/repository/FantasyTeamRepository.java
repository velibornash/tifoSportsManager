package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.FantasyTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FantasyTeamRepository extends JpaRepository<FantasyTeam, Long> {

    Optional<FantasyTeam> findByUserIdAndCountryIdAndSeasonYear(Long userId, Long countryId, Integer seasonYear);

    /** Every entry in one country's league, for the standings. Ordered by id so the order is stable. */
    List<FantasyTeam> findByCountryIdAndSeasonYearOrderByIdAsc(Long countryId, Integer seasonYear);

    /**
     * Which countries have a fantasy league running this season (owner, 2026-10-17).
     *
     * <p>The scoring job asks this rather than walking all 48 countries: only a country somebody has
     * entered needs scoring, and a country with no entries has no totals to get wrong.
     */
    @org.springframework.data.jpa.repository.Query(
            "select distinct t.country.id from FantasyTeam t where t.seasonYear = :seasonYear")
    List<Long> findCountryIdsWithEntries(@org.springframework.data.repository.query.Param("seasonYear")
                                         Integer seasonYear);
}
