package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JuniorRepository extends JpaRepository<Junior, Long> {
    List<Junior> findByTeamId(Long teamId);
    List<Junior> findByTeamIdOrderByAcademySkillExactDesc(Long teamId);

    /**
     * Juniors created before the arrival-age column existed (Sprint 5.2).
     *
     * <p>Exists so their talent reports can narrow at all. Null here means "the roll never happened",
     * not "arrived at graduation age" — see the backfill in {@code DatabaseInitializer} for why the
     * repair value is chosen the way it is.
     */
    List<Junior> findByArrivalAgeIsNull();

    /** The active intake, used when a junior school closes and every prospect graduates at once. */
    List<Junior> findByTeamIdAndStatus(Long teamId,
                                      org.example.footballmanager.newLogic.model.JuniorStatus status);

    /**
     * Juniors created before the position column existed (Sprint 5.3).
     *
     * <p>Repaired rather than read-as-unknown, because a null position at promotion re-rolls one —
     * which is the single behaviour this field was added to remove.
     */
    List<Junior> findByPositionIsNull();

    long countByTeamIdAndStatus(Long teamId,
                                org.example.footballmanager.newLogic.model.JuniorStatus status);

    @Query("SELECT j FROM Junior j WHERE j.team.id = :teamId AND (j.archived = false OR j.archived IS NULL) ORDER BY j.academySkillExact DESC")
    List<Junior> findVisibleByTeamId(@Param("teamId") Long teamId);

    @Query("SELECT count(j) FROM Junior j WHERE j.team.id = :teamId AND j.status = :status AND (j.archived = false OR j.archived IS NULL)")
    long countVisibleByTeamIdAndStatus(@Param("teamId") Long teamId, @Param("status") JuniorStatus status);

    List<Junior> findByTeamIdAndArchivedTrueOrderByArrivalSeasonNumberDescAcademySkillExactDesc(Long teamId);
    List<Junior> findByStatus(JuniorStatus status);
    long countByTeamIdAndArrivalSeasonNumberAndArrivalWeekNumber(Long teamId, int arrivalSeasonNumber, int arrivalWeekNumber);

    @Modifying
    @Query("update Junior j set j.age = j.age + 1 where j.status = :status")
    int incrementAgeByStatus(@Param("status") JuniorStatus status);

    /** Every active junior at or past the graduation age — the window closing on them. */
    List<Junior> findByStatusAndAgeGreaterThanEqual(@Param("status") JuniorStatus status,
                                                    @Param("age") int age);
}
