package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {
    @Override
    @EntityGraph(attributePaths = {"competition", "country"})
    Optional<Team> findById(Long id);

    @EntityGraph(attributePaths = {"stadium"})
    Optional<Team> findWithStadiumById(Long id);

    Optional<Team> findByName(String name);

    /**
     * Every club with this name, for code that must survive a duplicate.
     *
     * <p>{@link #findByName} returns {@code Optional<Team>} and therefore <b>throws</b> when two
     * clubs share a name — and two clubs sharing a name is explicitly allowed. Anything that
     * resolves a club from a user-supplied or stored string must use this instead and decide
     * explicitly what to do about the ambiguity, rather than crashing or silently picking one.
     */
    List<Team> findAllByNameIgnoreCase(String name);

    long countByCompetition(Competition league);

    List<Team> findAllByTypeOrderByIdAsc(CompetitionTeamType type);

    List<Team> findByCompetitionId(Long competitionId);
    List<Team> findByCountryId(Long countryId);
    List<Team> findByHumanControlledTrue();

    @Query("SELECT t FROM org.example.footballmanager.newLogic.model.Team t WHERE t.competition.id = :competitionId AND t.type = org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB")
    List<Team> findClubsByCompetitionId(@Param("competitionId") Long competitionId);

    @Query("select t from Team t where t.type is null or t.type = org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB order by t.id asc")
    List<Team> findClubTeamsForOperations();
}
