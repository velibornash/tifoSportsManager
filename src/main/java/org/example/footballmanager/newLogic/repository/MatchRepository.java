package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {
    List<Match> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);
    List<Match> findByCompetitionIdAndSeasonYear(Long competitionId, Integer seasonYear);
    List<Match> findBySeasonYearAndWeekNumber(Integer seasonYear, Integer weekNumber);
    Optional<Match> findByHomeTeamIdAndAwayTeamIdAndSeasonYearAndRoundNumber(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer roundNumber);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE m.id = :id")
    Optional<Match> findWithTeamsById(@Param("id") Long id);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam LEFT JOIN FETCH m.homeLineup LEFT JOIN FETCH m.awayLineup WHERE m.id = :id")
    Optional<Match> findDetailedById(@Param("id") Long id);

    List<Match> findByHomeTeamIdInAndAwayTeamIdIn(List<Long> homeTeamIds, List<Long> awayTeamIds);

    List<Match> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);

    /**
     * Every played international, in the order it was played.
     *
     * <p>Elo is a replay, so the order is the whole point — feeding the same results in a different
     * order produces different ratings, because each match is scored against what the two countries
     * were rated <i>at the time</i>. The id tiebreak matters for the two matches of one matchday,
     * which share a date and would otherwise come out in whatever order the database returned them.
     */
    @Query("SELECT m FROM Match m WHERE m.played = true AND m.competition.type = :type "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<Match> findPlayedByCompetitionTypeOrderByMatchDateAscIdAsc(
            @Param("type") org.example.footballmanager.newLogic.model.CompetitionType type);

    /**
     * Every played match between clubs, in the order it was played.
     *
     * <p>The Elo replay is order-dependent for the same reason the international one is: each match is
     * scored against what the two clubs were rated <i>at the time</i>. The date order, with the id
     * tiebreak for the two matches of one matchday, is what makes a replay reproducible.
     *
     * <p><b>Leagues and cups only, and that is the whole club-versus-national split.</b> A national
     * side plays in an {@code INTERNATIONAL} or {@code TOURNAMENT} competition and a club never plays
     * in one, so the type test separates the two pools without a single membership check. An
     * international <em>club</em> cup — the Champions, Masters and Challenge cups — is
     * {@code type = CUP} with {@code scope = INTERNATIONAL}, which is why it belongs on this side and
     * why {@link #findPlayedByCompetitionTypeOrderByMatchDateAscIdAsc} must not be widened to reach it.
     */
    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam "
            + "LEFT JOIN FETCH m.competition "
            + "WHERE m.played = true AND m.competition.type IN ("
            + "org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE, "
            + "org.example.footballmanager.newLogic.model.CompetitionType.CUP) "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<Match> findPlayedClubMatchesInOrder();

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE (m.homeTeam.id = :homeId OR m.awayTeam.id = :awayId) AND m.played = true ORDER BY m.matchDate DESC")
    List<Match> findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(@Param("homeId") Long homeId, @Param("awayId") Long awayId);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam LEFT JOIN FETCH m.homeLineup LEFT JOIN FETCH m.awayLineup LEFT JOIN FETCH m.competition LEFT JOIN FETCH m.stadium WHERE m.id = :id")
    Optional<Match> findWithTeamsAndLineupsById(@Param("id") Long id);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE m.competition.id = :competitionId AND m.seasonYear = :seasonYear AND m.roundNumber = :roundNumber AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId) AND m.started = true AND m.played = false")
    List<Match> findPreparedMatchesForTeamInRound(@Param("competitionId") Long competitionId, @Param("seasonYear") Integer seasonYear, @Param("roundNumber") Integer roundNumber, @Param("teamId") Long teamId);
}