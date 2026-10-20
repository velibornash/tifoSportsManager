package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BbTeamRepository extends JpaRepository<BbTeam, Long> {

    @Query("SELECT t FROM BbTeam t WHERE t.competition.id = :competitionId")
    List<BbTeam> findByCompetitionId(@Param("competitionId") Long competitionId);

    Optional<BbTeam> findByName(String name);

    @Query("SELECT t FROM BbTeam t WHERE t.competition.id = :competitionId ORDER BY t.id")
    List<BbTeam> findByCompetitionIdOrderById(@Param("competitionId") Long competitionId);
}