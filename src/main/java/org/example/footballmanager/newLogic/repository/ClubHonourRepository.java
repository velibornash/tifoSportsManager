package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.ClubHonour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ClubHonourRepository extends JpaRepository<ClubHonour, Long> {

    /** What a club has won, and when. The Club page milestones row. */
    List<ClubHonour> findByTeamIdOrderBySeasonYearDescMedal(Long teamId);

    void deleteByTeamId(Long teamId);

    /**
     * Recomputing one season must not delete the others.
     *
     * <p>An explicit delete that flushes first, not a derived {@code deleteByX}: derived deletes run the
     * DELETE against unflushed pending inserts, so a re-run of the same season in one transaction failed
     * its own unique constraint on the old rows.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ClubHonour h where h.seasonYear = :seasonYear")
    int deleteBySeasonYear(int seasonYear);
}
