package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains the squad's social state: mentoring, familiarity and cohesion (Sprint 4.6).
 *
 * <p>The <b>pairings are cached per club per week</b> rather than recomputed, because the weekly
 * training run asks the same question once per player per skill and the answer cannot change while
 * the run is in progress. Caching the answer is only safe because nothing in the loop can alter the
 * squad, which is checked by the cache being cleared from {@link #advanceWeek} — the one place a
 * week turns over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SquadEnvironmentService {

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final TrainingPercentService minutes;
    private final EntityManager entityManager;

    /** clubId -> "season:week" -> (mentorId to juniorId). */
    private final Map<Long, Map<String, Map<Long, Long>>> pairingCache = new ConcurrentHashMap<>();
    /** clubId -> "season:week" -> (juniorId to mentorId). */
    private final Map<Long, Map<String, Map<Long, Long>>> reverseCache = new ConcurrentHashMap<>();

    /** True if this player currently has a senior at his own position showing him how. */
    @Transactional(readOnly = true)
    public boolean isMentored(Long playerId, Integer season, Integer week) {
        if (playerId == null) return false;
        return reverseFor(playerId, season, week).containsKey(playerId);
    }

    /** The senior mentoring this player, or null. */
    @Transactional(readOnly = true)
    public Long mentorOf(Long playerId, Integer season, Integer week) {
        return reverseFor(playerId, season, week).get(playerId);
    }

    /** The juniors this senior is mentoring this week. */
    @Transactional(readOnly = true)
    public List<Long> mentoredBy(Long mentorId, Integer season, Integer week) {
        if (mentorId == null) return List.of();
        return new ArrayList<>(pairingFor(mentorId, season, week).values());
    }

    private Map<Long, Long> reverseFor(Long playerId, Integer season, Integer week) {
        Long teamId = teamOf(playerId);
        if (teamId == null) return Map.of();
        return reverseCache.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key(season, week), k -> reverseOf(SquadEnvironment.mentorPairs(squadOf(teamId))));
    }

    private Map<Long, Long> pairingFor(Long mentorId, Integer season, Integer week) {
        Long teamId = teamOf(mentorId);
        if (teamId == null) return Map.of();
        return pairingCache.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key(season, week), k -> SquadEnvironment.mentorPairs(squadOf(teamId)));
    }

    private static Map<Long, Long> reverseOf(Map<Long, Long> mentorToJunior) {
        Map<Long, Long> juniorToMentor = new LinkedHashMap<>();
        mentorToJunior.forEach((mentor, junior) -> juniorToMentor.put(junior, mentor));
        return juniorToMentor;
    }

    private String key(Integer season, Integer week) {
        return season + ":" + week;
    }

    private Long teamOf(Long playerId) {
        return players.findById(playerId).map(Player::getTeam)
                .map(Team::getId)
                .orElse(null);
    }

    private List<Player> squadOf(Long teamId) {
        return players.findByTeamId(teamId);
    }

    /**
     * Moves every club's squad state on one week.
     *
     * <p>Three things happen: familiarity follows minutes played, cohesion follows how much the
     * squad turned over, and the pairing cache is thrown away so next week re-pairs from scratch.
     * A senior who was sold, a junior who graduated, a player who arrived — all of it is picked up by
     * deriving the pairs again rather than by trying to patch stored ones.
     */
    /**
     * How many clubs are advanced between flushes.
     *
     * <p><b>Measured, 2026-10-11.</b> One pass of this method over the owner's world - 14,880 clubs,
     * ~300,000 players - took <b>over six minutes with the database completely idle</b>: 388 seconds of
     * CPU in 398 seconds of wall, a thread dump putting all of it in
     * {@code org.hibernate.engine.internal.Cascade.cascade}. No query was running. The job was not slow;
     * the ORM was.
     *
     * <p>The cause is the size of the persistence context, not the number of queries. Loading every
     * squad in one go put 300,000 managed entities in the session, and every query after that
     * auto-flushed - cascading over every entity dirtied <em>so far</em>. Three hundred thousand
     * players, one cascade each, over a set that only grows. That is O(clubs x players), and it is why
     * the database was idle while the CPU was not.
     *
     * <p><b>An earlier attempt failed and was reverted.</b> Separating the read phase from the write
     * phase and clearing every 50 clubs measured at 393 s CPU in 408 s - no better at all - because the
     * players were already loaded and dirty when the clearing would have happened. Flushing and
     * clearing <em>before</em> the work, in bounded chunks, is the version that actually shrinks the
     * cascade set, and that is what this is.
     */
    private static final int CLUB_CHUNK = 150;

    @Transactional
    public int advanceWeek(Integer season, Integer week) {
        List<Long> allClubIds = teams.findAll().stream()
                .map(Team::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (allClubIds.isEmpty()) {
            return 0;
        }

        int touched = 0;
        for (int from = 0; from < allClubIds.size(); from += CLUB_CHUNK) {
            List<Long> chunk = allClubIds.subList(from, Math.min(from + CLUB_CHUNK, allClubIds.size()));
            touched += advanceChunk(chunk, season, week);
        }

        pairingCache.clear();
        reverseCache.clear();
        log.info("Squad environment advanced to week {} for {} clubs", week, touched);
        return touched;
    }

    /**
     * One chunk of clubs: read, change, write, and let go.
     *
     * <p>The flush and the clear are the whole point. {@code clear()} detaches everything, so the next
     * chunk starts with an empty persistence context instead of inheriting the last one's dirt; without
     * it the cascade set is still every player in the world by the time the loop finishes.
     */
    private int advanceChunk(List<Long> clubIds, Integer season, Integer week) {
        // One query for the clubs in this chunk, not one per club. Reading them one at a time would
        // trade an ORM problem for a query problem, and `SquadEnvironmentWeeklyQueryCountTest` exists
        // precisely to keep that trade from being made quietly.
        Map<Long, Team> clubsById = new HashMap<>();
        for (Team club : teams.findAllById(clubIds)) {
            if (club != null && club.getId() != null) clubsById.put(club.getId(), club);
        }

        Map<Long, List<Player>> squadsByTeam = new HashMap<>();
        for (Player player : players.findByTeamIdIn(clubIds)) {
            if (player == null || player.getTeam() == null || player.getTeam().getId() == null) continue;
            squadsByTeam.computeIfAbsent(player.getTeam().getId(), k -> new ArrayList<>()).add(player);
        }

        // **Read everything before changing anything.**
        //
        // The mentor pairing and the minutes are both cached per club and both resolve on first use.
        // If that first use happens inside the write loop, it runs while this chunk's players are
        // already dirty - and a query auto-flushes, which cascades over every dirty entity. Nine
        // thousand players times four hundred queries is still quadratic, just at a smaller scale,
        // which is exactly what the first attempt at this fix measured: no improvement at all.
        //
        // Warming both caches while the session is clean costs nothing and moves every one of those
        // queries into a phase with an empty cascade set.
        for (List<Player> squad : squadsByTeam.values()) {
            for (Player p : squad) {
                if (p == null || p.getId() == null) continue;
                minutes.minutesPlayed(p.getId(), p.getTeam().getId(), season, week);
                mentoredBy(p.getId(), season, week);
            }
        }

        int touched = 0;
        for (Long clubId : clubIds) {
            Team club = clubsById.get(clubId);
            if (club == null) continue;
            List<Player> squad = squadsByTeam.getOrDefault(clubId, List.of());

            for (Player p : squad) {
                if (p == null) continue;
                int current = (int) Math.round(SquadEnvironment.familiarityOf(p));
                p.setFamiliarity((double) SquadEnvironment.familiarityAfterWeek(
                        current, minutes.minutesPlayed(p.getId(), clubId, season, week)));
                // A mentor gets something out of it too, and a club that spends a senior's season
                // teaching rather than playing would be exploiting him.
                if (!mentoredBy(p.getId(), season, week).isEmpty()) {
                    p.setMorale(Math.min(100.0, p.getMorale() + SquadEnvironment.MENTOR_MORALE_BONUS));
                }
            }
            // How many faces are still new to the system, measured from familiarity rather than from
            // a separate arrivals log. The contract records when a player signed but not in which
            // week, and inventing a second record of the same fact is how two records drift apart.
            int unsettled = 0;
            for (Player p : squad) {
                if (p != null && SquadEnvironment.isUnsettled(p)) unsettled++;
            }
            club.setCohesion((double) SquadEnvironment.cohesionAfterWeek(
                    (int) Math.round(SquadEnvironment.cohesionOf(club)), unsettled));
            touched++;
        }

        if (entityManager.isOpen()) {
            entityManager.flush();
            entityManager.clear();
        }
        return touched;
    }
}
