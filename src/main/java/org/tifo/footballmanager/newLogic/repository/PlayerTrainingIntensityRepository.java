package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.PlayerTrainingIntensity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerTrainingIntensityRepository extends JpaRepository<PlayerTrainingIntensity, Long> {

    Optional<PlayerTrainingIntensity> findByPlayerIdAndSeasonAndWeek(Long playerId, Integer season,
                                                                    Integer week);

    List<PlayerTrainingIntensity> findByTeamIdAndSeasonAndWeek(Long teamId, Integer season, Integer week);
}
