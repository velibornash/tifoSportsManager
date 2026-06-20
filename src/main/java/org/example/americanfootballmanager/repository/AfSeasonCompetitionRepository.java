package org.example.americanfootballmanager.repository;

import org.example.americanfootballmanager.model.AfSeasonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AfSeasonCompetitionRepository extends JpaRepository<AfSeasonCompetition, Long> {
    Optional<AfSeasonCompetition> findByCompetitionIdAndSeasonSeasonYear(Long competitionId, Integer seasonYear);
}
