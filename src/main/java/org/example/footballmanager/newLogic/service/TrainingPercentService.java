package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the training percentage for real players, from data rather than arguments (owner,
 * 2026-09-27).
 *
 * <p>{@link TrainingPercent} is the rule; this is the part that has to know which coach the club
 * employs, which skill the player is being worked on, and how many minutes he actually played. It
 * also caches minutes per week, because a squad of twenty-five would otherwise be twenty-five
 * identical queries every time the manager trains.
 *
 * <p><b>Minutes come from every competition.</b> League, cup, European, national team and friendly
 * are all rows in the same table distinguished only by {@code Match.competition}, and nothing here
 * filters on it — which is what the owner asked for, and it means a call-up counts toward the 120
 * without a single special case.
 */
@Service
@RequiredArgsConstructor
public class TrainingPercentService {

    private final StaffMemberRepository staff;
    private final MatchPlayerStatsRepository stats;

    /** Minutes per player for the week, so a squad costs one query rather than twenty-five. */
    private final Map<String, Map<Long, Integer>> weekCache = new HashMap<>();

    /**
     * The percentage of a week's training a player gets.
     *
     * @param season the season the week belongs to
     * @param week   the week, 1-12
     * @param skill  the skill being trained this week; the coach is judged on this one
     */
    @Transactional(readOnly = true)
    public double percentForWeek(Player player, Integer season, Integer week, SkillName skill) {
        if (player == null || player.getId() == null) return 0;
        if (skill == null) {
            skill = TrainingPercent.primarySkillFor(player.effectiveRole());
        }
        StaffMember coach = coachForSkill(player, skill);
        Long teamId = player.getTeam() == null ? -1L : player.getTeam().getId();
        int minutes = minutesPlayed(player.getId(), teamId, season, week);
        return TrainingPercent.percentFor(player, coach, skill, minutes);
    }

    /**
     * The same, for a whole squad.
     *
     * <p>The club's staff list is fetched <b>once</b>, because it is a query and this runs for every
     * club in the league. The coach is then resolved per player and per skill from that list, which is
     * cheap — it is a walk over a handful of staff — and it is what makes a specialist worth
     * anything. Resolving one coach for the whole squad and using him for everybody would hand a
     * goalkeeper the striker's coaching.
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> percentForSquad(List<Player> squad, Integer season, Integer week) {
        Map<Long, Double> out = new HashMap<>();
        if (squad == null || squad.isEmpty()) return out;
        Long teamId = squad.get(0).getTeam() == null ? -1L : squad.get(0).getTeam().getId();
        Map<Long, Integer> minutes = minutesForWeekKey(teamId, season, week);
        List<StaffMember> staffAtClub = staffFor(squad.get(0));

        for (Player player : squad) {
            if (player == null || player.getId() == null) continue;
            SkillName skill = TrainingPercent.primarySkillFor(player.effectiveRole());
            StaffMember coach = TrainingPercent.coachForSkill(staffAtClub, skill, player.getAge());
            out.put(player.getId(), TrainingPercent.percentFor(
                    player, coach, skill, minutes.getOrDefault(player.getId(), 0)));
        }
        return out;
    }

    /**
     * The best coach at this club for one skill, or null if the club has nobody who teaches it.
     *
     * <p>Null is a real answer and not a failure: a club with no staff trains on talent and minutes
     * alone, which is a worse but perfectly valid week.
     */
    public StaffMember coachForSkill(Player player, SkillName skill) {
        if (player == null || player.getTeam() == null || player.getTeam().getId() == null) {
            return null;
        }
        return TrainingPercent.coachForSkill(staffFor(player), skill, player.getAge());
    }

    /** The club's staff list, or empty rather than null so callers can walk it. */
    private List<StaffMember> staffFor(Player player) {
        if (player == null || player.getTeam() == null || player.getTeam().getId() == null) {
            return List.of();
        }
        return staff.findByTeamId(player.getTeam().getId());
    }

    /**
     * Minutes a player played that week, summed over every competition.
     *
     * <p>Cached per week. A cache is the right call here because the same week is asked about
     * repeatedly — once for the report, once for the growth pass, and once for the dashboard — and
     * the answer cannot change within a tick.
     */
    @Transactional(readOnly = true)
    public int minutesPlayed(Long playerId, Long teamId, Integer season, Integer week) {
        return minutesForWeekKey(teamId, season, week).getOrDefault(playerId, 0);
    }

    private Map<Long, Integer> minutesForWeekKey(Long teamId, Integer season, Integer week) {
        String key = teamId + ":" + season + ":" + week;
        return weekCache.computeIfAbsent(key, k -> {
            Map<Long, Integer> byPlayer = new HashMap<>();
            if (teamId == null || teamId < 0 || season == null || week == null) {
                return byPlayer;
            }
            for (MatchPlayerStats line : stats.findByTeamAndSeasonAndWeek(teamId, season, week)) {
                // Defensive: a stat row without a player or minutes is not worth failing a week's
                // training over.
                if (line == null || line.getPlayer() == null || line.getPlayer().getId() == null) {
                    continue;
                }
                byPlayer.merge(line.getPlayer().getId(), Math.max(0, line.getMinutesPlayed()),
                        Integer::sum);
            }
            return byPlayer;
        });
    }

    /**
     * The club's coach, or null if the club has no staff at all.
     *
     * <p>Deliberately not cached with the minutes: a club can hire and fire between weeks, and a
     * coach remembered for a week too long would keep developing players on his terms.
     */
    @Transactional(readOnly = true)
    public StaffMember coachFor(Player player) {
        if (player == null || player.getTeam() == null || player.getTeam().getId() == null) {
            return null;
        }
        return TrainingPercent.coachFor(staff.findByTeamId(player.getTeam().getId()));
    }

    /** Drops the cached minutes. Called when the calendar moves or a match is recorded. */
    public void invalidate() {
        weekCache.clear();
    }

}
