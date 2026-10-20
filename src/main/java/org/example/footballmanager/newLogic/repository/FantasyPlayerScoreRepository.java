package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.fantasy.FantasyPlayerTotal;
import org.example.footballmanager.newLogic.model.FantasyPlayerScore;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FantasyPlayerScoreRepository extends JpaRepository<FantasyPlayerScore, Long> {

    /** The guard on the scoring pass: a week that has rows has been scored and is never scored again. */
    boolean existsBySeasonYearAndWeekNumber(Integer seasonYear, Integer weekNumber);

    /** One gameweek's points for the whole pool, dearest first, for the player-points view. */
    List<FantasyPlayerScore> findBySeasonYearAndWeekNumberOrderByPointsDesc(Integer seasonYear,
                                                                           Integer weekNumber);

    List<FantasyPlayerScore> findByPlayerIdAndSeasonYear(Long playerId, Integer seasonYear);

    /**
     * Every player's season total, for the picker's Points column (owner, 2026-10-18).
     *
     * <p>One row per player who has scored anything. A player who has not appears nowhere, and the picker
     * reads that as zero — which is right, because a gameweek that has not been played is not a blank.
     */
    @Query("""
            select new org.example.footballmanager.newLogic.fantasy.FantasyPlayerPoints(s.player.id, sum(s.points))
            from FantasyPlayerScore s
            where s.seasonYear = :seasonYear
            group by s.player.id
            """)
    List<org.example.footballmanager.newLogic.fantasy.FantasyPlayerPoints> seasonTotals(
            @Param("seasonYear") Integer seasonYear);

    /**
     * The season's best players in one country, for the player-points table (owner, 2026-10-17).
     *
     * <p>Summed in the database rather than in the client: a season is twelve gameweeks and the pool is
     * 7,730 players, and shipping ninety thousand rows to add them up in a browser is not a read model.
     *
     * <p>Grouped by the player's id and carrying his name, club, position and tier, so the table needs no
     * second lookup per row. Ordered by the sum and limited by the caller's page.
     */
    @Query("""
            select new org.example.footballmanager.newLogic.fantasy.FantasyPlayerTotal(
                s.player.name, s.player.team.name, s.player.position, s.player.team.competition.tier,
                sum(s.points))
            from FantasyPlayerScore s
            where s.seasonYear = :seasonYear
              and s.player.team.competition.country.id = :countryId
            group by s.player.id, s.player.name, s.player.team.name, s.player.position,
                     s.player.team.competition.tier
            order by sum(s.points) desc, s.player.name asc
            """)
    List<FantasyPlayerTotal> seasonTopScorers(@Param("countryId") Long countryId,
                                              @Param("seasonYear") Integer seasonYear,
                                              Pageable page);
}
