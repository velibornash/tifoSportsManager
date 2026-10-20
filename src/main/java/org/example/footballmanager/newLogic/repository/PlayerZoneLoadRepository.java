package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlayerZoneLoadRepository extends JpaRepository<PlayerZoneLoad, Long> {

    List<PlayerZoneLoad> findByPlayerIdOrderByIdDesc(Long playerId);

    List<PlayerZoneLoad> findByMatchId(Long matchId);

    /**
     * Every zone load from matches played since a moment — one query for the whole world's recovery.
     *
     * <p>Added because {@code applyDailyRecovery} asked {@code recoveryFor} per player, and that asked
     * this repository per player: 16,354 players is 16,354 round trips, and the job logged
     * "7408 player(s) recovered" after <b>42 minutes</b>. The maths is unchanged, only the number of
     * queries.
     *
     * <p><b>Superseded by {@link #findLoadMinutesPlayedSince} and kept only because deleting it is a
     * separate concern.</b> This one returns entities and {@code JOIN FETCH}es the match, so it
     * materialises a managed {@code PlayerZoneLoad} per row plus the joined match columns — for an
     * aggregate that reads four numbers. Measured on a real matchday (155 matches, 18,853 rows):
     * <b>21.0 ms</b> against the projection's <b>10.8 ms</b>. No production caller is left.
     */
    @Query("SELECT load FROM PlayerZoneLoad load JOIN FETCH load.match m "
            + "WHERE m.matchDate IS NOT NULL AND m.matchDate > :after")
    List<PlayerZoneLoad> findLoadsPlayedSince(@Param("after") java.time.LocalDateTime after);

    /**
     * The same window as {@link #findLoadsPlayedSince}, as the four numbers the aggregate reads.
     *
     * <p><b>A projection, not an entity, and the join does not fetch the match.</b> The old query
     * returned every column of the load <em>and every column of the match</em> for each of ~121 rows per
     * match, and then the caller called one method on each. This returns {@code (player_id, zone,
     * minutes, intensity)} and nothing else, so the daily recovery job holds four scalars per row
     * instead of a managed entity with a persistence-context entry behind it.
     *
     * <p>Measured on a real matchday — 155 matches, 18,853 zone-load rows, 3,410 distinct players:
     * <b>21.0 ms → 10.8 ms</b>, and the row that crosses the wire is a third of the width. Both plans
     * are a hash of the window's matches joined to a scan of the loads, because {@code match.match_date}
     * carries no index — see the board on D2.
     *
     * <p><b>Not paged, deliberately.</b> The obvious fix is {@code Pageable}, and offset paging over a
     * join with no total order can skip and duplicate rows between pages — which would silently
     * under-count a player's recovery. Keyset paging on {@code id} is the correct version and is not
     * written yet; the projection is the part that is safe to land on its own.
     */
    @Query("SELECT new org.example.footballmanager.newLogic.repository.ZoneLoadMinutes("
            + "load.player.id, load.zone, load.minutes, load.intensity) "
            + "FROM PlayerZoneLoad load JOIN load.match m "
            + "WHERE m.matchDate IS NOT NULL AND m.matchDate > :after")
    List<ZoneLoadMinutes> findLoadMinutesPlayedSince(@Param("after") java.time.LocalDateTime after);

    /**
     * The same window as {@link #findLoadMinutesPlayedSince}, for one page of matches.
     *
     * <p><b>The window's match ids, keyset-paged — which is what makes the read cheap.</b>
     * {@code findLoadMinutesPlayedSince} joins the window's matches to a scan of the whole
     * {@code player_zone_load} table: at full scale, one matchday's window is 7,440 matches and
     * 1,473,120 rows returned out of <b>17,677,440 read</b>, because the only index that can answer
     * {@code match_id} leads with {@code player_id}. Measured 4,441 ms.
     *
     * <p>Handed a page of ids, this becomes {@code match_id IN (:ids)} — a set of index lookups
     * against {@code ix_zone_load_match}, reading the 1,473,120 rows that are wanted instead of the
     * 17,677,440 that exist.
     *
     * <p><b>Why the caller pages at all</b> and does not pass 7,440 ids in one go: a single
     * {@code IN} list that long is its own problem, and the point of the page is that no single
     * statement holds more than a page's worth of the table.
     */
    @Query("SELECT new org.example.footballmanager.newLogic.repository.ZoneLoadMinutes("
            + "load.player.id, load.zone, load.minutes, load.intensity) "
            + "FROM PlayerZoneLoad load "
            + "WHERE load.match.id IN :matchIds")
    List<ZoneLoadMinutes> findLoadMinutesForMatches(@Param("matchIds") java.util.Collection<Long> matchIds);
}

