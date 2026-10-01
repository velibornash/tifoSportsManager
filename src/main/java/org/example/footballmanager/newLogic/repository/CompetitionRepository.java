package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    List<Competition> findByCountryId(Long countryId);
    List<Competition> findByTypeAndScope(CompetitionType type, CompetitionScope scope);
    Optional<Competition> findByName(String name);

    /**
     * Every competition with this name, tolerating duplicates.
     *
     * <p>{@link #findByName} throws {@code NonUniqueResultException} on a second row, and the first
     * wrong version of the international cups created global rows under the same names the per-tier
     * ones use — so a lookup by name has to be able to survive them. The World page walks this list
     * rather than scanning every competition in the world to find one name in it.
     */
    List<Competition> findAllByName(String name);

    /**
     * Every league division in one tier, with its country.
     *
     * <p>An index lookup, because the alternative was {@code findAll().stream().filter(...)}: the World
     * page asked for all five tiers fifteen times over and each ask re-read the whole competition table
     * — 1,557 rows, thirty times, for a page load. The country is fetched because the caller groups by
     * it and a lazy proxy would be one query per division.
     */
    @EntityGraph(attributePaths = {"country"})
    List<Competition> findByTypeAndTier(CompetitionType type, Integer tier);

    Optional<Competition> findByNameAndCountryIsoCode(String name, String isoCode);
    List<Competition> findByCountryIsoCodeAndType(String isoCode, CompetitionType competitionType);
    List<Competition> findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc(String isoCode, CompetitionType competitionType);
    List<Competition> findByCountryIsoCodeAndTypeAndTierOrderByDivisionLevelAscIdAsc(String isoCode, CompetitionType competitionType, Integer tier);
}
