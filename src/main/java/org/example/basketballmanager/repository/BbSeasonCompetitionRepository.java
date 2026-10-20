package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbSeasonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BbSeasonCompetitionRepository extends JpaRepository<BbSeasonCompetition, Long> {
    Optional<BbSeasonCompetition> findByCompetitionIdAndSeasonSeasonYear(Long competitionId, Integer seasonYear);
}
