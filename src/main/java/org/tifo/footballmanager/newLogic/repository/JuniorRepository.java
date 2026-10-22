package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Junior;
import org.tifo.footballmanager.newLogic.model.JuniorStatus;
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
                                      org.tifo.footballmanager.newLogic.model.JuniorStatus status);

    /**
     * Juniors created before the position column existed (Sprint 5.3).
     *
     * <p>Repaired rather than read-as-unknown, because a null position at promotion re-rolls one —
     * which is the single behaviour this field was added to remove.
     */
    List<Junior> findByPositionIsNull();

    long countByTeamIdAndStatus(Long teamId,
                                org.tifo.footballmanager.newLogic.model.JuniorStatus status);

    @Query("SELECT j FROM Junior j WHERE j.team.id = :teamId AND (j.archived = false OR j.archived IS NULL) ORDER BY j.academySkillExact DESC")
    List<Junior> findVisibleByTeamId(@Param("teamId") Long teamId);

    /**
     * The clubs that already rolled an intake for this season and week.
     *
     * <p>The season intake asked this per club, and the world has fourteen thousand of them. It is
     * asked once now, for the whole world, and the answer is a set to test membership against.
     */
    @Query("""
            SELECT DISTINCT j.team.id FROM Junior j
            WHERE j.arrivalSeasonNumber = :seasonNumber
              AND j.arrivalWeekNumber = :arrivalWeekNumber
            """)
    List<Long> findTeamIdsWithIntakeIn(@Param("seasonNumber") int seasonNumber,
                                       @Param("arrivalWeekNumber") int arrivalWeekNumber);

    /**
     * Visible juniors of one status, counted per club, for a list of clubs in one query.
     *
     * <p>This is the academy's free-slot question ("how many active juniors does this club already
     * hold?"), grouped instead of asked once per club. A club with none is absent from the result
     * rather than a zero, which is what the caller's {@code getOrDefault} is for.
     */
    @Query("""
            SELECT j.team.id, count(j) FROM Junior j
            WHERE j.team.id IN :teamIds
              AND j.status = :status
              AND (j.archived = false OR j.archived IS NULL)
            GROUP BY j.team.id
            """)
    List<Object[]> countVisibleByTeamIdsAndStatus(@Param("teamIds") java.util.Collection<Long> teamIds,
                                                  @Param("status") JuniorStatus status);

    List<Junior> findByTeamIdAndArchivedTrueOrderByArrivalSeasonNumberDescAcademySkillExactDesc(Long teamId);
    List<Junior> findByStatus(JuniorStatus status);


    @Modifying
    @Query("update Junior j set j.age = j.age + 1 where j.status = :status")
    int incrementAgeByStatus(@Param("status") JuniorStatus status);

    /**
     * Every active junior who arrived before {@code arrivalSeasonNumber} — his tenure is over
     * (owner, 2026-10-08).
     *
     * <p>Replaces {@code findByStatusAndAgeGreaterThanEqual}. The deadline used to be an age, which
     * made it depend on the roll at intake: the same season produced prospects who debuted the
     * following spring and prospects who sat in the academy for five more years. Tenure is now
     * measured in seasons, so the cohort that arrived in season N is resolved when season N+1 ends,
     * whoever was fifteen and whoever was nineteen.
     */
    @Query("SELECT j FROM Junior j WHERE j.status = :status AND j.arrivalSeasonNumber < :arrivalSeasonNumber")
    List<Junior> findByStatusAndArrivalSeasonNumberLessThan(@Param("status") JuniorStatus status,
                                                            @Param("arrivalSeasonNumber") int arrivalSeasonNumber);
}
