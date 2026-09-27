package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    @Transactional
    public int advanceWeek(Integer season, Integer week) {
        List<Team> clubs = teams.findAll();
        if (clubs == null) return 0;
        int touched = 0;
        for (Team club : clubs) {
            if (club == null || club.getId() == null) continue;
            List<Player> squad = players.findByTeamId(club.getId());
            if (squad == null) continue;
            for (Player p : squad) {
                if (p == null) continue;
                int current = (int) Math.round(SquadEnvironment.familiarityOf(p));
                p.setFamiliarity((double) SquadEnvironment.familiarityAfterWeek(
                        current, minutesThisWeek(p, season, week)));
                // A mentor gets something out of it too, and a club that spends a senior's season
                // teaching rather than playing would be exploiting him.
                if (mentoredBy(p.getId(), season, week).size() > 0) {
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
        pairingCache.clear();
        reverseCache.clear();
        log.info("Squad environment advanced to week {} for {} clubs", week, touched);
        return touched;
    }

    /**
     * Minutes this player actually played this week.
     *
     * <p>Delegated to the same source the training percentage uses, so a substitute who played twenty
     * minutes is treated identically here and there. A player with no recorded minutes counts as
     * none, never as a full match.
     */
    private int minutesThisWeek(Player player, Integer season, Integer week) {
        if (player == null || player.getId() == null) return 0;
        return minutes.minutesPlayed(player.getId(),
                player.getTeam() == null ? null : player.getTeam().getId(), season, week);
    }
}
