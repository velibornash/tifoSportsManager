package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    List<Competition> findByCountryId(Long countryId);
    List<Competition> findByTypeAndScope(CompetitionType type, CompetitionScope scope);
    Optional<Competition> findByName(String name);

    Optional<Competition> findByNameAndCountryIsoCode(String name, String isoCode);
    List<Competition> findByCountryIsoCodeAndType(String isoCode, CompetitionType competitionType);
    List<Competition> findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc(String isoCode, CompetitionType competitionType);
    List<Competition> findByCountryIsoCodeAndTypeAndTierOrderByDivisionLevelAscIdAsc(String isoCode, CompetitionType competitionType, Integer tier);
}
