package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.StaffMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StaffMemberRepository extends JpaRepository<StaffMember, Long> {
    List<StaffMember> findByTeamId(Long teamId);
    Optional<StaffMember> findByTeamIdAndRole(Long teamId, org.example.footballmanager.newLogic.model.StaffRole role);
    long countByTeamId(Long teamId);

    /**
     * Every club that already has a row, in one query.
     *
     * <p>Added with the seeding path that needed it. `seedAllClubs` walked every club in the world and
     * asked whether it was staffed — one count per club, which is 1 + 14,880 queries to answer a
     * question that is a single set. Reading the set once and filtering in Java is the same shape
     * of fix as `MatchdayJob`, for the same reason: the per-row answer cannot narrow the query.
     */
    @Query("select distinct t.team.id from StaffMember t where t.team.id is not null")
    List<Long> findStaffedTeamIds();
}
