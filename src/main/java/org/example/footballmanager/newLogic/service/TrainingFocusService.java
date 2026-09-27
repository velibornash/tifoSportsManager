package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.PlayerTrainingFocus;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerTrainingFocusRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Individual training focus: one or two specific skills, per player, per week (Sprint 4.1).
 *
 * <p>The headline training feature, and the thing the role buckets could never do. A manager chooses
 * a skill per position group; this lets him work on one player specifically, on anything, for this
 * week only.
 *
 * <h2>Why it bypasses the role's allow-list</h2>
 * The allow-list exists to pick a <b>sensible default</b> for a striker or a keeper. An individual
 * focus is an explicit decision, and a system that quietly rewrites it back to "shooting" because the
 * player is a forward would be refusing to do the job it was asked to do. So a focus is taken at face
 * value, and the role default still applies to everything the focus does not name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingFocusService {

    /** The owner said one or two skills. Three is a programme, not a focus. */
    public static final int MAX_SKILLS_PER_PLAYER = 2;

    /**
     * Skills a focus may name.
     *
     * <p>All eight, which is the point. The backlog's "allow stamina and fitness" is satisfied
     * because the restriction is gone rather than widened.
     */
    private static final Set<SkillName> FOCUSABLE =
            EnumSet.of(SkillName.STAMINA, SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE,
                    SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER);

    private final PlayerTrainingFocusRepository focuses;
    private final PlayerRepository players;
    private final TeamRepository teams;

    /**
     * Sets a player's focus for a week, replacing whatever was there.
     *
     * <p>Replace rather than append: a manager changing his mind on Thursday should not end up with
     * four skills focused because he clicked save three times.
     *
     * @return the skills actually set, in order; empty if the request was refused
     */
    @Transactional
    public List<SkillName> setFocus(Long teamId, Long playerId, Integer season, Integer week,
                                   List<SkillName> requested) {
        if (teamId == null || playerId == null) {
            return List.of();
        }
        Player player = players.findById(playerId).orElse(null);
        if (player == null || player.getTeam() == null
                || !Objects.equals(player.getTeam().getId(), teamId)) {
            // Only the owning club decides. A manager cannot set a focus on a rival's player, which
            // would otherwise be a way to write training data for another club.
            return List.of();
        }

        List<SkillName> skills = sanitise(requested);
        if (skills.isEmpty()) {
            clearFocus(playerId, season, week);
            return List.of();
        }

        for (PlayerTrainingFocus existing : focuses.findByPlayerIdAndSeasonAndWeek(playerId, season, week)) {
            if (!skills.contains(existing.getSkill())) {
                focuses.delete(existing);
            }
        }
        for (SkillName skill : skills) {
            if (focuses.findByPlayerIdAndSeasonAndWeekAndSkill(playerId, season, week, skill).isEmpty()) {
                focuses.save(PlayerTrainingFocus.of(teamId, playerId, season, week, skill));
            }
        }
        return skills;
    }

    /** Removes a player's focus for a week, so the role default applies again. */
    @Transactional
    public int clearFocus(Long playerId, Integer season, Integer week) {
        List<PlayerTrainingFocus> existing = focuses.findByPlayerIdAndSeasonAndWeek(playerId, season, week);
        focuses.deleteAll(existing);
        return existing.size();
    }

    /** Every focus set for a squad this week, keyed by player. */
    @Transactional(readOnly = true)
    public Map<Long, List<SkillName>> focusForSquad(Long teamId, Integer season, Integer week) {
        Map<Long, List<SkillName>> byPlayer = new LinkedHashMap<>();
        for (PlayerTrainingFocus focus : focuses.findByTeamIdAndSeasonAndWeek(teamId, season, week)) {
            if (focus == null || focus.getPlayerId() == null) continue;
            byPlayer.computeIfAbsent(focus.getPlayerId(), k -> new ArrayList<>()).add(focus.getSkill());
        }
        return byPlayer;
    }

    /**
     * Parses a skill name from the training screen, case- and space-insensitive.
     *
     * <p>Returns empty for an unknown name rather than throwing, so one bad value in a two-skill
     * request does not lose the rest of a manager's decision.
     */
    public static Optional<SkillName> parseSkill(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String clean = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return java.util.Arrays.stream(SkillName.values())
                .filter(skill -> skill.name().equals(clean))
                .findFirst();
    }

    /**
     * The skill a player is actually worked on this week.
     *
     * <p>A focus wins over the role default. When there are two, the first is the main one and the
     * second is the supporting one — order is the manager's, because the list he sent is the list he
     * meant.
     */
    @Transactional(readOnly = true)
    public SkillName primarySkillFor(Player player, SkillName roleDefault, Integer season, Integer week) {
        if (player == null || player.getId() == null) {
            return roleDefault != null ? roleDefault : defaultFor(player);
        }
        List<PlayerTrainingFocus> rows =
                focuses.findByPlayerIdAndSeasonAndWeek(player.getId(), season, week);
        for (PlayerTrainingFocus row : rows) {
            if (row != null && row.getSkill() != null) {
                return row.getSkill();
            }
        }
        return roleDefault != null ? roleDefault : defaultFor(player);
    }

    /** The second focused skill, or null. */
    @Transactional(readOnly = true)
    public SkillName secondarySkillFor(Player player, Integer season, Integer week) {
        if (player == null || player.getId() == null) {
            return null;
        }
        List<PlayerTrainingFocus> rows =
                focuses.findByPlayerIdAndSeasonAndWeek(player.getId(), season, week);
        if (rows.size() < 2) {
            return null;
        }
        PlayerTrainingFocus second = rows.get(1);
        return second == null ? null : second.getSkill();
    }

    /** Whether this player has been given an individual focus this week. */
    @Transactional(readOnly = true)
    public boolean hasFocus(Player player, Integer season, Integer week) {
        if (player == null || player.getId() == null) {
            return false;
        }
        return !focuses.findByPlayerIdAndSeasonAndWeek(player.getId(), season, week).isEmpty();
    }

    /** Drops unknown skills, duplicates, and anything past the limit, keeping the manager's order. */
    private List<SkillName> sanitise(List<SkillName> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<SkillName> out = new ArrayList<>();
        for (SkillName skill : requested) {
            if (skill == null || skill == SkillName.FATIGUE) {
                continue;
            }
            if (!FOCUSABLE.contains(skill) || out.contains(skill)) {
                continue;
            }
            if (out.size() >= MAX_SKILLS_PER_PLAYER) {
                // Trimming rather than failing is deliberate: a manager who asks for five gets the
                // first two, which is the rule, rather than an error he has to decode. The order
                // he sent is the order he meant, so the first two are his actual first two.
                break;
            }
            out.add(skill);
        }
        return out;
    }

    private SkillName defaultFor(Player player) {
        return TrainingPercent.primarySkillFor(player == null ? null : player.effectiveRole());
    }
}
