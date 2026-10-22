package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.TeamTrainingSetup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamTrainingSetupRepository extends JpaRepository<TeamTrainingSetup, Long> {
    List<TeamTrainingSetup> findByTeamId(Long teamId);
    Optional<TeamTrainingSetup> findByTeamIdAndSeasonNumberAndWeekNumber(Long teamId, Integer seasonNumber, Integer weekNumber);

    /**
     * Every club's weekly training setup for one week - one query.
     *
     * <p>The same reason as the intensity overrides: the form-and-morale sweep needs to know how hard
     * each club trained its players, and the club's setting is the default a player inherits when he
     * has no override of his own.
     */
    List<TeamTrainingSetup> findBySeasonNumberAndWeekNumber(Integer seasonNumber, Integer weekNumber);
}