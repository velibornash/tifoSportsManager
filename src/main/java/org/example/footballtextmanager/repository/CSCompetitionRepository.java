package org.example.footballtextmanager.repository;

import org.example.footballtextmanager.model.CSCompetition;
import org.example.footballtextmanager.model.CSCompetitionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CSCompetitionRepository extends JpaRepository<CSCompetition, Long> {
    Optional<CSCompetition> findByNameAndCsCountry_IsoCode(String name, String isoCode);

    List<CSCompetition> findByCsCountry_IsoCodeAndType(String isoCode, CSCompetitionType CSCompetitionType);

    List<CSCompetition> findByCsCountry_IsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc(String isoCode, CSCompetitionType CSCompetitionType);

    List<CSCompetition> findByCsCountry_IsoCodeAndTypeAndTierOrderByDivisionLevelAscIdAsc(String isoCode, CSCompetitionType CSCompetitionType, Integer tier);
}
