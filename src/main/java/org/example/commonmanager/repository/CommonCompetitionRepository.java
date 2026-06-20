package org.example.commonmanager.repository;

import org.example.commonmanager.model.CommonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommonCompetitionRepository extends JpaRepository<CommonCompetition, Long> {
    List<CommonCompetition> findBySportAndCountryCodeOrderByTierAscDivisionLevelAsc(String sport, String countryCode);
    List<CommonCompetition> findBySportAndCountryCodeAndTier(String sport, String countryCode, Integer tier);
    List<CommonCompetition> findBySport(String sport);
}
