package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendlyRequestRepository extends JpaRepository<FriendlyRequest, Long> {

    List<FriendlyRequest> findByRequesterTeamIdAndSeasonAndWeek(Long requesterTeamId, Integer season,
                                                               Integer week);

    List<FriendlyRequest> findByOpponentTeamIdAndSeasonAndWeek(Long opponentTeamId, Integer season,
                                                              Integer week);

    List<FriendlyRequest> findBySeasonAndWeek(Integer season, Integer week);

    Optional<FriendlyRequest> findByIdAndOpponentTeamId(Long id, Long opponentTeamId);

    /**
     * Every friendly agreed for one team in one week. The current training contract charges zero,
     * but the count remains useful for the friendly summary and future rules.
     *
     * <p>Both roles are checked because a club can be the one that asked or the one that said yes.
     */
    @Query("""
            select r from FriendlyRequest r
            where r.status = org.example.footballmanager.newLogic.model.FriendlyRequest$FriendlyStatus.ACCEPTED
              and r.season = :season and r.week = :week
              and (r.requesterTeamId = :teamId or r.opponentTeamId = :teamId)
            """)
    List<FriendlyRequest> findAcceptedForTeamAndWeek(@Param("teamId") Long teamId,
                                                    @Param("season") Integer season,
                                                    @Param("week") Integer week);

    /** Requests still waiting for an answer, for expiring them once the week has gone. */
    @Query("""
            select r from FriendlyRequest r
            where r.status = org.example.footballmanager.newLogic.model.FriendlyRequest$FriendlyStatus.PENDING
              and r.season = :season and r.week < :week
            """)
    List<FriendlyRequest> findStalePending(@Param("season") Integer season, @Param("week") Integer week);
}
