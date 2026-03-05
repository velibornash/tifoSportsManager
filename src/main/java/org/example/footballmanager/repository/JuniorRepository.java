package org.example.footballmanager.repository;

import org.example.footballmanager.model.Junior;
import org.example.footballmanager.model.JuniorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JuniorRepository extends JpaRepository<Junior, Long> {
    List<Junior> findByTeamIdOrderByAcademySkillExactDesc(Long teamId);
    List<Junior> findByStatus(JuniorStatus status);
    long countByTeamIdAndArrivalSeasonNumberAndArrivalWeekNumber(Long teamId, int arrivalSeasonNumber, int arrivalWeekNumber);
}
