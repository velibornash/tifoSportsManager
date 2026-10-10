package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Every match either side of any of these teams, played only.
     *
     * <p>Added with {@code ScheduleInsightService.buildTeamSnapshots}. The snapshot needs each club's five
     * most recent results, which used to be read with
     * {@code findByHomeTeamIdOrAwayTeamId(teamId, teamId)} — once per club, for every club in the world.
     *
     * <p>The home and away teams are fetched with the join because the snapshot filters on both being
     * present, and reading them lazily is what puts an entity graph into the persistence context that the
     * flush then has to dirty-check.
     */
    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam "
            + "WHERE m.played = true AND (m.homeTeam.id IN :teamIds OR m.awayTeam.id IN :teamIds)")
    List<Match> findPlayedInvolvingAnyOf(@Param("teamIds") Collection<Long> teamIds);

    List<Match> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);

    /**
     * Every played international, in the order it was played, as a rating replay reads it.
     *
     * <p>Elo is a replay, so the order is the whole point — feeding the same results in a different
     * order produces different ratings, because each match is scored against what the two countries
     * were rated <i>at the time</i>. The id tiebreak matters for the two matches of one matchday,
     * which share a date and would otherwise come out in whatever order the database returned them.
     *
     * <p><b>A {@link ScoredMatch} projection, not {@code Match} entities</b>, because
     * {@code event_json} is ~840 KB a match and this reads every played international in the world. See
     * {@link ScoredMatch}.
     *
     * <p>The sides are reached by an explicit {@code LEFT JOIN} rather than {@code m.homeTeam.id}, which
     * would be an inner join and would silently drop a match with a missing side instead of carrying a
     * null id through to the replay's "not rated" branch.
     */
    @Query("SELECT new org.example.footballmanager.newLogic.repository.ScoredMatch("
            + "m.id, home.id, home.name, away.id, away.name, m.homeGoals, m.awayGoals, c.scope, c.type, c.nationalStage) "
            + "FROM Match m LEFT JOIN m.homeTeam home LEFT JOIN m.awayTeam away LEFT JOIN m.competition c "
            + "WHERE m.played = true AND m.competition.type = :type "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<ScoredMatch> findPlayedScoredByCompetitionTypeInOrder(
            @Param("type") org.example.footballmanager.newLogic.model.CompetitionType type);

    /**
     * Every played match between national sides, in the order it was played — a rating replay's
     * whole history, in one query.
     *
     * <p><b>One method rather than two calls filtered in Java.</b> The national replay reads
     * {@code INTERNATIONAL} and {@code TOURNAMENT}, and Elo is order-dependent: two calls would have to
     * be merged back into date order by the caller, which is a sort in the service and one more place
     * for the ordering to be wrong. It is also one round trip instead of two, over the same rows.
     *
     * <p>{@code m.competition.type IN (...)} rather than a membership test on the sides, for the same
     * reason {@link #findPlayedClubScoredInOrder()} uses one: a club never plays in either of these
     * types and a national side plays in nothing else, so the type test separates the pools with no
     * extra query.
     */
    /**
     * Every played match between clubs, in the order it was played, with the season and both divisions.
     *
     * <p>Built for the ranking-points replay ({@code P0-RANK-2}), which needs two things
     * {@link ScoredMatch} deliberately does not carry:
     *
     * <ul>
     *   <li><b>the season</b>, because the displayed total is a rolling window over per-season subtotals
     *       and a replay that cannot say which season a match belonged to cannot fill the ledger;</li>
     *   <li><b>each club's division tier</b>, because the points scale by tier, and reading the tier off
     *       a club's rating would be circular while reading it off the club at replay time would give
     *       the season a club was promoted into rather than the season the match was played in.</li>
     * </ul>
     *
     * <p>Clubs only, on {@code teamType = CLUB}: national sides belong to
     * {@code NationalRatingService} and must never be added into a club's ledger.
     *
     * <p>Date order with the id tiebreak, so a replay is deterministic and can safely be run twice.
     */
    @Query("SELECT new org.example.footballmanager.newLogic.repository.RankedMatch("
            + "m.id, home.id, away.id, m.homeGoals, m.awayGoals, m.seasonYear, "
            + "c.scope, c.type, c.teamType, c.nationalStage, homeComp.tier, awayComp.tier) "
            + "FROM Match m LEFT JOIN m.homeTeam home LEFT JOIN m.awayTeam away "
            + "LEFT JOIN m.competition c "
            + "LEFT JOIN home.competition homeComp LEFT JOIN away.competition awayComp "
            + "WHERE m.played = true AND c.teamType = "
            + "org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<RankedMatch> findPlayedClubRankedInOrder();

    /**
     * Every played match between national sides, with the season and the level.
     *
     * <p>For the ranking-points replay ({@code P0-RANK-3}). Carries the season, because the ledger is
     * per season, and the <b>level</b>, because senior and U-21 are two independent totals that must
     * never be added together.
     *
     * <p>The level comes from the competition rather than from either side: a competition is what makes
     * a match a World Cup match, and inferring the level from a team would be circular.
     *
     * <p>{@code teamType = NATIONAL_TEAM}, so a national side can never be scored into a club's ledger.
     */
    @Query("SELECT new org.example.footballmanager.newLogic.repository.RankedNationalMatch("
            + "m.id, home.id, away.id, m.homeGoals, m.awayGoals, m.seasonYear, "
            + "c.scope, c.type, c.nationalStage, c.nationalLevel) "
            + "FROM Match m LEFT JOIN m.homeTeam home LEFT JOIN m.awayTeam away LEFT JOIN m.competition c "
            + "WHERE m.played = true AND c.teamType = "
            + "org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<RankedNationalMatch> findPlayedNationalRankedInOrder();

    @Query("SELECT new org.example.footballmanager.newLogic.repository.ScoredMatch("
            + "m.id, home.id, home.name, away.id, away.name, m.homeGoals, m.awayGoals, c.scope, c.type, c.nationalStage) "
            + "FROM Match m LEFT JOIN m.homeTeam home LEFT JOIN m.awayTeam away LEFT JOIN m.competition c "
            + "WHERE m.played = true AND m.competition.type IN ("
            + "org.example.footballmanager.newLogic.model.CompetitionType.INTERNATIONAL, "
            + "org.example.footballmanager.newLogic.model.CompetitionType.TOURNAMENT) "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<ScoredMatch> findPlayedNationalScoredInOrder();

    /**
     * Every played match between clubs, in the order it was played, as a rating replay reads it.
     *
     * <p>The Elo replay is order-dependent for the same reason the international one is: each match is
     * scored against what the two clubs were rated at the time. The date order, with the id tiebreak for
     * the two matches of one matchday, is what makes a replay reproducible.
     *
     * <p><b>Leagues and cups only, and that is the whole club-versus-national split.</b> A national
     * side plays in an {@code INTERNATIONAL} or {@code TOURNAMENT} competition and a club never plays
     * in one, so the type test separates the two pools without a single membership check. An
     * international <em>club</em> cup — the Champions, Masters and Challenge cups — is
     * {@code type = CUP} with {@code scope = INTERNATIONAL}, which is why it belongs on this side and
     * why {@link #findPlayedScoredByCompetitionTypeInOrder} must not be widened to reach it.
     *
     * <p>A {@link ScoredMatch} projection, not {@code Match} entities: {@code event_json} is ~840 KB a
     * match and this is every played club match in the world. See {@link ScoredMatch}.
     */
    @Query("SELECT new org.example.footballmanager.newLogic.repository.ScoredMatch("
            + "m.id, home.id, home.name, away.id, away.name, m.homeGoals, m.awayGoals, c.scope, c.type, c.nationalStage) "
            + "FROM Match m LEFT JOIN m.homeTeam home LEFT JOIN m.awayTeam away LEFT JOIN m.competition c "
            + "WHERE m.played = true AND m.competition.type IN ("
            + "org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE, "
            + "org.example.footballmanager.newLogic.model.CompetitionType.CUP) "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<ScoredMatch> findPlayedClubScoredInOrder();

    /**
     * One page of the window's matches, as the cursor for the next page.
     *
     * <p><b>The keyset is {@code (match_date, id)}, not {@code id}.</b> Paging on the id alone cannot use
     * an index on the date, so the query reads the primary-key index and heap-filters everything before
     * the page: measured <b>206 ms a page</b> on an 89,280-match season. With the composite key and an
     * index on {@code (match_date, id)} the same page is an index-only range scan at <b>0.35 ms</b>.
     *
     * <p><b>The row-value comparison is written out rather than as {@code (a, b) > (c, d)}</b> because
     * JPQL cannot express a row constructor. It is exactly equivalent here: {@code matchDate} is filtered
     * non-null on the line above, so {@code (match_date, id) > (lastDate, lastId)} is the disjunction
     * below and nothing else.
     *
     * <p><b>First page: pass {@code lastDate = after} and {@code lastId = Long.MIN_VALUE}.</b> The window
     * filter is strictly {@code matchDate > after}, so the disjunction collapses to it and no match on
     * the boundary is admitted — the window means exactly what it meant before this was paged.
     *
     * <p><b>Why not offset paging.</b> Neither form of it has a stable total order here, and a skipped or
     * repeated row between pages would silently under-count a player's recovery: no exception, a
     * plausible number in the log, less work credited than the player did.
     *
     * @param after    the window: matches played strictly after this instant
     * @param lastDate the date of the last row of the previous page, or {@code after} on the first
     * @param lastId   the id of the last row of the previous page, or {@link Long#MIN_VALUE} on the first
     */
    @Query("SELECT new org.example.footballmanager.newLogic.repository.MatchPageEntry(m.id, m.matchDate) "
            + "FROM Match m "
            + "WHERE m.matchDate IS NOT NULL AND m.matchDate > :after "
            + "AND (m.matchDate > :lastDate OR (m.matchDate = :lastDate AND m.id > :lastId)) "
            + "ORDER BY m.matchDate ASC, m.id ASC")
    List<org.example.footballmanager.newLogic.repository.MatchPageEntry> findMatchPagePlayedSince(
            @Param("after") java.time.LocalDateTime after,
            @Param("lastDate") java.time.LocalDateTime lastDate,
            @Param("lastId") Long lastId,
            org.springframework.data.domain.Pageable page);


    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE (m.homeTeam.id = :homeId OR m.awayTeam.id = :awayId) AND m.played = true ORDER BY m.matchDate DESC")
    List<Match> findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(@Param("homeId") Long homeId, @Param("awayId") Long awayId);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam LEFT JOIN FETCH m.homeLineup LEFT JOIN FETCH m.awayLineup LEFT JOIN FETCH m.competition LEFT JOIN FETCH m.stadium WHERE m.id = :id")
    Optional<Match> findWithTeamsAndLineupsById(@Param("id") Long id);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE m.competition.id = :competitionId AND m.seasonYear = :seasonYear AND m.roundNumber = :roundNumber AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId) AND m.started = true AND m.played = false")
    List<Match> findPreparedMatchesForTeamInRound(@Param("competitionId") Long competitionId, @Param("seasonYear") Integer seasonYear, @Param("roundNumber") Integer roundNumber, @Param("teamId") Long teamId);
}