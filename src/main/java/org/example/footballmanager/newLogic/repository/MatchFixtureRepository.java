package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchFixtureRepository extends JpaRepository<MatchFixture, Long> {
    List<MatchFixture> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);

    /**
     * The fixture a played match came from.
     *
     * <p>The link runs fixture → match ({@code MatchFixture.playedMatch}), and the match view needs it the
     * other way round: after the whistle a manager is on the match, and the substitution plan is keyed by
     * fixture. Without this there is no route from a played match to the report of how its conditions
     * went, which is why {@code MatchDTO} has no fixture id to offer.
     *
     * <p>{@code played_match_id} carries a unique constraint — one played match belongs to at most one
     * fixture — so {@code Optional} is the honest return rather than a list that should never hold two.
     * An exhibition has no fixture and returns empty, which is a real state rather than a broken one.
     */
    Optional<MatchFixture> findByPlayedMatchId(Long playedMatchId);

    /**
     * Every fixture a team appears in, home or away.
     *
     * <p>Spring Data cannot derive "home OR away" from a single parameter, so this is an explicit
     * query. The national-team screen needs exactly this, and pairing it with the existing
     * home-and-away-id method would force callers to invent a sentinel id for the side they do not
     * know.
     */
    /**
     * Unplayed fixtures for one day of one week (owner, 2026-09-28).
     *
     * <p>Day-precise, not week-precise. A week holds two league rounds, so asking for "week 3" would
     * hand a day-3 job the day-7 round as well and it would play football that is two days early.
     */
    @Query("select f from MatchFixture f where f.seasonYear = :seasonYear and f.weekNumber = :weekNumber "
            + "and f.dayNumber = :dayNumber and f.played = false")
    List<MatchFixture> findUnplayedOnDay(@Param("seasonYear") Integer seasonYear,
                                         @Param("weekNumber") Integer weekNumber,
                                         @Param("dayNumber") Integer dayNumber);

    /**
     * Unplayed <b>friendlies</b> for one day, whatever competition they belong to.
     *
     * <p><b>The query that makes a friendly playable at all.</b> Every other matchday selects by
     * {@code competition.type}, and a friendly belongs to no competition — deliberately, since it decides
     * nothing. So a friendly fixture was invisible to every matchday in the framework: it could be
     * agreed, written, and shown, and never played.
     *
     * <p>Selected by {@code matchType} instead, which is the field that says what the fixture *is* rather
     * than what competition it belongs to. {@code matchType = FRIENDLY} and not a null check, because a
     * null type is a row written before the column existed and those rows also carry no day.
     */
    @Query("select f from MatchFixture f where f.seasonYear = :seasonYear and f.weekNumber = :weekNumber "
            + "and f.dayNumber = :dayNumber and f.played = false "
            + "and f.matchType = org.example.footballmanager.newLogic.model.MatchType.FRIENDLY")
    List<MatchFixture> findUnplayedFriendliesOnDay(@Param("seasonYear") Integer seasonYear,
                                                   @Param("weekNumber") Integer weekNumber,
                                                   @Param("dayNumber") Integer dayNumber);

    List<MatchFixture> findBySeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(
            Integer seasonYear, Integer weekNumber, Integer dayNumber);

    long countBySeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(
            Integer seasonYear, Integer weekNumber, Integer dayNumber);

    /**
     * How many unplayed fixtures one competition already has in a week.
     *
     * <p>This exists because {@code countBySeasonYearAndWeekNumberAndDayNumberAndPlayedFalse} counts
     * across <b>every</b> competition, so the cup seeder's idempotency guard saw another country's
     * round-1 ties and refused to draw its own. One cup drew and every other cup after it was left
     * empty, silently, in a world of forty-eight countries. The guard has to be scoped to the cup it is
     * protecting.
     */
    long countByCompetitionIdAndSeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(
            Long competitionId, Integer seasonYear, Integer weekNumber, Integer dayNumber);

    /**
     * Every fixture on a day, played or not.
     *
     * <p>For the watch screen. Filtering to unplayed made a manager whose match the matchday job had
     * already run see "no fixture on day 3" - the opposite of what they want, since the whole point of
     * clicking Watch is to see the result of a match that has been generated.
     */
    List<MatchFixture> findBySeasonYearAndWeekNumberAndDayNumber(
            Integer seasonYear, Integer weekNumber, Integer dayNumber);

    @Query("select f from MatchFixture f where f.homeTeam.id = :teamId or f.awayTeam.id = :teamId")
    List<MatchFixture> findAllForTeam(@Param("teamId") Long teamId);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndPlayedFalse(Long competitionId, Integer seasonYear);
    List<MatchFixture> findBySeasonYearAndWeekNumber(Integer seasonYear, Integer weekNumber);
    Optional<MatchFixture> findByHomeTeamIdAndAwayTeamIdAndSeasonYearAndRoundNumber(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer roundNumber);

    List<MatchFixture> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);

    /**
     * Every fixture of a competition, in any season.
     *
     * <p>Added for the World page's national-competition tiles, which answer "is there a competition to
     * click into" rather than "is it drawn this season". Counting one season only made a competition
     * drawn in season 1 read as undrawn while season 2 was active (owner, 2026-10-10).
     */
    @Query("select f from MatchFixture f where f.competition.id = :competitionId "
            + "order by f.seasonYear, f.roundNumber, f.matchDate")
    List<MatchFixture> findByCompetitionIdOrdered(@Param("competitionId") Long competitionId);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    long countByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalse(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndHomeTeamIdAndAwayTeamIdAndPlayedFalse(
            Long competitionId, Integer seasonYear, Integer roundNumber, Long homeTeamId, Long awayTeamId
    );

    @Query("""
            SELECT f FROM MatchFixture f
            WHERE f.competition.id = :competitionId
              AND f.seasonYear = :seasonYear
              AND (f.homeTeam.id = :teamId OR f.awayTeam.id = :teamId)
            ORDER BY f.roundNumber ASC, f.matchDate ASC, f.id ASC
            """)
    List<MatchFixture> findTeamScheduleByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
            @Param("competitionId") Long competitionId,
            @Param("seasonYear") Integer seasonYear,
            @Param("teamId") Long teamId
    );

    @Query("""
            SELECT f FROM MatchFixture f
            WHERE f.seasonYear = :seasonYear
              AND (f.homeTeam.id = :teamId OR f.awayTeam.id = :teamId)
            ORDER BY f.roundNumber ASC, f.matchDate ASC, f.id ASC
            """)
    List<MatchFixture> findTeamScheduleBySeasonYearOrderByRoundNumberAscMatchDateAsc(
            @Param("seasonYear") Integer seasonYear,
            @Param("teamId") Long teamId
    );
}
