package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.TrainingAssignment;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.TrainingAssignmentRepository;
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
@Slf4j
public class TrainingPercentService {

    private final StaffMemberRepository staff;
    private final MatchPlayerStatsRepository stats;
    /**
     * The slot each player trains in. Nullable: a world built before T-MATCH-8 has no rows at all,
     * and every player then trains on all his minutes, which is what happened before.
     */
    private final TrainingAssignmentRepository trainingAssignments;
    /**
     * The club's ground, which trains faster the higher it is built (T-FAC-1, owner ruling).
     *
     * <p>Nullable, and null means no effect: a world whose facilities were never seeded trains exactly as
     * it did before, which is the safe reading of a missing input.
     */
    private final TrainingFacilityService facilities;

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
        // **The club's coaching strength for this skill, not one coach's single number** (owner,
        // 2026-10-18): the head coach's skill in it, his overall, and every assistant's overall, averaged
        // back onto the 1-20 scale. See TrainingPercent.coachingStrength for why it is a mean.
        int strength = TrainingPercent.coachingStrength(staffFor(player), skill, player.getAge());
        Long teamId = player.getTeam() == null ? -1L : player.getTeam().getId();
        int minutes = minutesPlayed(player.getId(), teamId, season, week);
        // **The ground, finally doing something (T-FAC-1).** A manager who spends on the training ground
        // has bought a better week, and before this his four facility levels changed the price, the
        // upkeep and the number on the stadium page - and nothing else.
        //
        // The percentage is capped at 100 rather than scaled past it: a better ground makes the same week
        // worth more, and a week cannot be worth more than a week. A club at level 20 with a full week of
        // minutes tops out, which is the honest ceiling and keeps one facility from being unbounded.
        double percent = TrainingPercent.percent(player.getTalent(), strength, minutes);
        double multiplier = trainingGroundMultiplier(player);
        return multiplier <= 1.0 ? percent : Math.min(100.0, percent * multiplier);
    }

    /**
     * The club's training multiplier, or 1.0 when there is no ground or no facilities service.
     *
     * <p>Fails to neutral rather than to an exception: this runs for every player of every club in the
     * world, and a missing facility must not be the reason a training week does not run.
     */
    private double trainingGroundMultiplier(Player player) {
        if (facilities == null || player == null || player.getTeam() == null) {
            return 1.0;
        }
        try {
            return facilities.trainingMultiplier(player.getTeam());
        } catch (RuntimeException unreadableGround) {
            log.debug("No ground for {}, training at 1.0", player.getTeam().getName());
            return 1.0;
        }
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
            // The same composite as the single-player path, from the staff list already in hand: this runs
            // for every player of every club in the league, so the list is fetched once and the strength is
            // a walk over a handful of staff.
            int strength = TrainingPercent.coachingStrength(staffAtClub, skill, player.getAge());
            out.put(player.getId(), TrainingPercent.percent(
                    player.getTalent(), strength, minutes.getOrDefault(player.getId(), 0)));
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
            List<MatchPlayerStats> lines = stats.findByTeamAndSeasonAndWeek(teamId, season, week);
            Map<Long, Position> trainingSlot = trainingSlotsFor(lines);

            for (MatchPlayerStats line : lines) {
                // Defensive: a stat row without a player or minutes is not worth failing a week's
                // training over.
                if (line == null || line.getPlayer() == null || line.getPlayer().getId() == null) {
                    continue;
                }
                long playerId = line.getPlayer().getId();

                // **T-MATCH-8: only the minutes spent in the slot the player trains in count.**
                //
                // Before this, every stat row was summed and a player set to train MID was credited with
                // 90 minutes at full-back. The slot was on the simulation's own output and was not written
                // down, so there was nothing to compare against — which is why this reads
                // `positionPlayed`, a column added with this change.
                //
                // Two deliberate escapes, both of which keep a player on their old, unchanged behaviour:
                // no training position set, and a match that recorded no slot (every result written before
                // this column existed). Counting nothing for all of them would silently change how every
                // existing player develops.
                Position trainsIn = trainingSlot.get(playerId);
                if (trainsIn != null && line.getPositionPlayed() != null
                        && line.getPositionPlayed() != trainsIn) {
                    continue;
                }
                byPlayer.merge(playerId, Math.max(0, line.getMinutesPlayed()), Integer::sum);
            }
            return byPlayer;
        });
    }

    /**
     * The slot each player in these stat rows trains in.
     *
     * <p>One query for the whole squad rather than one per player: this runs for every club in the world
     * once a week, and a query per player would be 33,000 of them on a fresh world.
     */
    private Map<Long, Position> trainingSlotsFor(List<MatchPlayerStats> lines) {
        if (trainingAssignments == null || lines == null || lines.isEmpty()) {
            return Map.of();
        }
        List<Long> playerIds = lines.stream()
                .filter(l -> l != null && l.getPlayer() != null && l.getPlayer().getId() != null)
                .map(l -> l.getPlayer().getId())
                .distinct()
                .toList();
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Position> slots = new HashMap<>();
        for (TrainingAssignment assignment : trainingAssignments.findByPlayerIds(playerIds)) {
            if (assignment != null && assignment.getPlayerId() != null
                    && assignment.getTrainingPosition() != null) {
                slots.put(assignment.getPlayerId(), assignment.getTrainingPosition());
            }
        }
        return slots;
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
