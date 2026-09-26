package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchPlayerStatsRepository extends JpaRepository<MatchPlayerStats, Long> {
    List<MatchPlayerStats> findByMatchId(Long matchId);
    List<MatchPlayerStats> findByPlayerId(Long playerId);
    List<MatchPlayerStats> findByPlayerIdIn(List<Long> playerIds);

    /**
     * Every stat line a player produced in one week of one season.
     *
     * <p>Goes through {@code Match} rather than carrying a week on the stat row, because the stat
     * row does not have one and adding it would mean a second thing to keep in step. Going through
     * the match also means the owner's rule is satisfied for free: <b>every</b> competition counts -
     * league, cup, European, national team and friendly alike - because they are all rows in the
     * same table distinguished only by {@code Match.competition}, and nothing here filters on it.
     */
    @Query("""
            select s from MatchPlayerStats s
            where s.player.id = :playerId
              and s.match.seasonYear = :season
              and s.match.weekNumber = :week
            """)
    List<MatchPlayerStats> findByPlayerAndSeasonAndWeek(@Param("playerId") Long playerId,
                                                        @Param("season") Integer season,
                                                        @Param("week") Integer week);

    /** The same, for a whole squad at once - one query per week rather than one per player. */
    @Query("""
            select s from MatchPlayerStats s
            where s.player.team.id = :teamId
              and s.match.seasonYear = :season
              and s.match.weekNumber = :week
            """)
    List<MatchPlayerStats> findByTeamAndSeasonAndWeek(@Param("teamId") Long teamId,
                                                      @Param("season") Integer season,
                                                      @Param("week") Integer week);
}