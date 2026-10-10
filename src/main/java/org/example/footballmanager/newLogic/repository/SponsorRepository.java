package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Sponsor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SponsorRepository extends JpaRepository<Sponsor, Long> {
    List<Sponsor> findByTeamId(Long teamId);
    long countByTeamId(Long teamId);

    /**
     * Every club that already has a row, in one query.
     *
     * <p>Added with the seeding path that needed it. `seedAllClubs` walked every club in the world and
     * asked whether it was staffed — one count per club, which is 1 + 14,880 queries to answer a
     * question that is a single set. Reading the set once and filtering in Java is the same shape
     * of fix as `MatchdayJob`, for the same reason: the per-row answer cannot narrow the query.
     */
    @Query("select distinct t.team.id from Sponsor t where t.team.id is not null")
    List<Long> findSponsoredTeamIds();
}
