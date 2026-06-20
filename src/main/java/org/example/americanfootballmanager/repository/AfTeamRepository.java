package org.example.americanfootballmanager.repository;

import org.example.americanfootballmanager.model.AfTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AfTeamRepository extends JpaRepository<AfTeam, Long> {

    @Query("SELECT t FROM AfTeam t WHERE t.competition.id = :competitionId")
    List<AfTeam> findByCompetitionId(@Param("competitionId") Long competitionId);

    Optional<AfTeam> findByName(String name);

    @Query("SELECT t FROM AfTeam t WHERE t.competition.id = :competitionId ORDER BY t.id")
    List<AfTeam> findByCompetitionIdOrderById(@Param("competitionId") Long competitionId);
}
