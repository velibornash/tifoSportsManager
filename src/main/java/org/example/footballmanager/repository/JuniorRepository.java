package org.example.footballmanager.repository;

import org.example.footballmanager.model.Junior;
import org.example.footballmanager.model.JuniorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JuniorRepository extends JpaRepository<Junior, Long> {
    List<Junior> findByTeamIdOrderByAcademySkillExactDesc(Long teamId);
    @Query("SELECT j FROM Junior j WHERE j.team.id = :teamId AND (j.archived = false OR j.archived IS NULL) ORDER BY j.academySkillExact DESC")
    List<Junior> findVisibleByTeamId(@Param("teamId") Long teamId);
    List<Junior> findByTeamIdAndArchivedTrueOrderByArrivalSeasonNumberDescAcademySkillExactDesc(Long teamId);
    List<Junior> findByStatus(JuniorStatus status);
    long countByTeamIdAndArrivalSeasonNumberAndArrivalWeekNumber(Long teamId, int arrivalSeasonNumber, int arrivalWeekNumber);
}
