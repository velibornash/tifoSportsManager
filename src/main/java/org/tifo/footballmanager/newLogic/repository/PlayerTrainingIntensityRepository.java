package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.PlayerTrainingIntensity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerTrainingIntensityRepository extends JpaRepository<PlayerTrainingIntensity, Long> {

    Optional<PlayerTrainingIntensity> findByPlayerIdAndSeasonAndWeek(Long playerId, Integer season,
                                                                    Integer week);

    List<PlayerTrainingIntensity> findByTeamIdAndSeasonAndWeek(Long teamId, Integer season, Integer week);

    /**
     * Every intensity override set anywhere in the world for one week - one query.
     *
     * <p>The form-and-morale sweep asks how hard each player was trained, and it asks it for the whole
     * world at once. Asked per player it would be seven thousand round trips a week; asked per team it
     * would be fifteen thousand. This is the one query that answers it.
     */
    List<PlayerTrainingIntensity> findBySeasonAndWeek(Integer season, Integer week);
}
