package org.example.footballmanager.repository;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.SeasonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface SeasonCompetitionRepository extends JpaRepository<SeasonCompetition, Long> {
    Optional<SeasonCompetition> findByCompetitionAndSeasonYear(Competition league, Integer seasonYear);
}
