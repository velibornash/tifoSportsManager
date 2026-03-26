package org.example.footballmanager.repository;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionTeamType;
import org.example.footballmanager.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
    @Override
    @EntityGraph(attributePaths = {"competition", "country"})
    Optional<Team> findById(Long id);

    @EntityGraph(attributePaths = {"stadium"})
    Optional<Team> findWithStadiumById(Long id);

    Optional<Team> findByName(String name);

    long countByCompetition(Competition league);

    List<Team> findAllByTypeOrderByIdAsc(CompetitionTeamType type);

    List<Team> findByCompetitionId(Long competitionId);

    @Query("select t from Team t where t.type is null or t.type = org.example.footballmanager.model.CompetitionTeamType.CLUB order by t.id asc")
    List<Team> findClubTeamsForOperations();
}
