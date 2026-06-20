package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    List<Competition> findByCountryId(Long countryId);
    List<Competition> findByTypeAndScope(org.example.footballmanager.newLogic.model.CompetitionType type, org.example.footballmanager.newLogic.model.CompetitionScope scope);
    Optional<Competition> findByName(String name);
}