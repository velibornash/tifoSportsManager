package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.PlayerTrainingFocus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerTrainingFocusRepository extends JpaRepository<PlayerTrainingFocus, Long> {

    List<PlayerTrainingFocus> findByTeamIdAndSeasonAndWeek(Long teamId, Integer season, Integer week);

    List<PlayerTrainingFocus> findByPlayerIdAndSeasonAndWeek(Long playerId, Integer season, Integer week);

    Optional<PlayerTrainingFocus> findByPlayerIdAndSeasonAndWeekAndSkill(
            Long playerId, Integer season, Integer week,
            org.example.footballmanager.newLogic.model.SkillName skill);
}
