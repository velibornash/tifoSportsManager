package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.ScoutAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScoutAssignmentRepository extends JpaRepository<ScoutAssignment, Long> {

    List<ScoutAssignment> findByTeamIdOrderByIdAsc(Long teamId);

    List<ScoutAssignment> findByTeamIdAndActiveTrueOrderByIdAsc(Long teamId);

    Optional<ScoutAssignment> findByTeamIdAndCountryIdAndActiveTrue(Long teamId, Long countryId);

    /**
     * The duplicate guard.
     *
     * <p>Exists as a query rather than a loop over {@link #findByTeamIdOrderByIdAsc} so the check and
     * the write cannot race: two concurrent assignments to the same country would both pass a
     * read-then-write check, and one club scouting Brazil twice is not a state worth supporting.
     */
    @Query("select count(a) > 0 from ScoutAssignment a where a.team.id = :teamId and a.country.id = :countryId and a.active = true")
    boolean existsActiveForCountry(@Param("teamId") Long teamId, @Param("countryId") Long countryId);

    /**
     * How many active assignments a club has, used to decide whether the network is worth anything at
     * all. A count query rather than a list size because the caller wants a number, not the rows.
     */
    @Query("select count(a) from ScoutAssignment a where a.team.id = :teamId and a.active = true")
    long countActiveByTeam(@Param("teamId") Long teamId);
}
