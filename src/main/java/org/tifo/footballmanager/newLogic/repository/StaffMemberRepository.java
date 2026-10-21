package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.footballmanager.newLogic.model.StaffRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StaffMemberRepository extends JpaRepository<StaffMember, Long> {
    List<StaffMember> findByTeamId(Long teamId);
    Optional<StaffMember> findByTeamIdAndRole(Long teamId, org.tifo.footballmanager.newLogic.model.StaffRole role);
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

    /**
     * The market: everybody not yet attached to a club.
     *
     * <p>A candidate <b>is</b> a staff row with no team, which is why the recruiting market needs no
     * second table and no second entity. Hiring sets the club, refusing deletes the row, and every
     * existing reader — the directory, the wage bill, the training model — already asks by team and so
     * never sees a candidate unless it is looking for one.
     */
    List<StaffMember> findByTeamIsNull();

    long countByTeamIdAndRole(Long teamId, StaffRole role);

    /**
     * How many staff every club holds, by role, in one query.
     *
     * <p><b>The same shape of fix as {@link #findStaffedTeamIds()}, for the same reason.</b> The AI
     * recruiting pass used to ask every club in the world how many staff it had — seven counts for
     * each of fourteen thousand clubs, a hundred thousand round trips inside one transaction — and the
     * job never finished (owner, 2026-10-21: "nije stigao staff, nesto ga zeza"). The set of counts
     * is one query, and the rest is arithmetic.
     *
     * <p>Each row is the club's id, the role, and how many of that role it holds.
     */
    @Query("select t.team.id, t.role, count(t) from StaffMember t "
            + "where t.team.id is not null and t.role is not null "
            + "group by t.team.id, t.role")
    List<Object[]> countsByTeamAndRole();
}
