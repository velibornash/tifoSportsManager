package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.PlayerDiscipline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerDisciplineRepository extends JpaRepository<PlayerDiscipline, Long> {

    /** The club-wide row, where red-card bans live. */
    Optional<PlayerDiscipline> findByPlayerIdAndSeasonAndCompetitionIsNull(Long playerId, Integer season);

    /** The competition row, where the yellow accumulation lives. */
    Optional<PlayerDiscipline> findByPlayerIdAndSeasonAndCompetitionId(
            Long playerId, Integer season, Long competitionId);

    List<PlayerDiscipline> findByPlayerIdAndSeason(Long playerId, Integer season);
}
