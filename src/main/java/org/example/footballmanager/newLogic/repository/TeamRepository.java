package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByCompetitionId(Long competitionId);
    List<Team> findByCountryId(Long countryId);
    List<Team> findByHumanControlledTrue();
    Optional<Team> findByName(String name);

    @Query("SELECT t FROM org.example.footballmanager.newLogic.model.Team t WHERE t.competition.id = :competitionId AND t.type = org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB")
    List<Team> findClubsByCompetitionId(@Param("competitionId") Long competitionId);
}