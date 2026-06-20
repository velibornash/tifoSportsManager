package org.example.footballtextmanager.repository;

import org.example.footballtextmanager.model.CSCompetition;
import org.example.footballtextmanager.model.CSCompetitionTeamType;
import org.example.footballtextmanager.model.CTeam;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CSTeamRepository extends JpaRepository<CTeam, Long> {
    @Override
    @EntityGraph(attributePaths = {"competition", "country"})
    Optional<CTeam> findById(Long id);

    @EntityGraph(attributePaths = {"stadium"})
    Optional<CTeam> findWithStadiumById(Long id);

    Optional<CTeam> findByName(String name);

    long countByCSCompetition(CSCompetition league);

    List<CTeam> findAllByTypeOrderByIdAsc(CSCompetitionTeamType type);

    List<CTeam> findByCSCompetitionId(Long csCompetitionId);

    @Query("select t from CTeam t where t.type is null or t.type = CSCompetitionTeamType.CLUB order by t.id asc")
    List<CTeam> findClubTeamsForOperations();
}
