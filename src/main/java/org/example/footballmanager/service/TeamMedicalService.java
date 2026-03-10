package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.dto.TeamMedicalOverviewDTO;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.SkillName;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TeamMedicalService {
    private static final int FATIGUE_ALERT_THRESHOLD = 18;
    private static final int RECOVERY_FATIGUE_REDUCTION = 12;
    private static final int RECOVERY_DAY_REDUCTION = 3;

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;

    @Transactional(readOnly = true)
    public TeamMedicalOverviewDTO buildOverview(Long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return null;
        }
        return buildOverview(team);
    }

    @Transactional
    public TeamMedicalOverviewDTO applyRecovery(Long teamId, Long playerId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return null;
        }

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getTeam() != null && Objects.equals(p.getTeam().getId(), teamId))
                .orElse(null);
        if (player == null) {
            return null;
        }

        Skills skills = player.getSkills();
        if (skills != null) {
            int reducedFatigue = Math.max(0, skills.getFatigue() - RECOVERY_FATIGUE_REDUCTION);
            skills.setSkill(SkillName.FATIGUE, reducedFatigue);
        }

        int currentInjuryDays = Math.max(0, player.getInjuryDaysRemaining());
        if (currentInjuryDays > 0) {
            int next = Math.max(0, currentInjuryDays - RECOVERY_DAY_REDUCTION);
            player.setInjuryDaysRemaining(next);
            if (next == 0) {
                player.setInjurySeasonNumber(null);
                player.setInjuryWeekNumber(null);
                player.setInjured(false);
            }
        }

        player.setForm(Math.min(10.0, Math.max(1.0, player.getForm() + 0.2)));
        playerRepository.save(player);
        return buildOverview(team);
    }

    private TeamMedicalOverviewDTO buildOverview(Team team) {
        List<Player> players = playerRepository.findByTeamId(team.getId());
        int injuredCount = (int) players.stream().filter(Player::isInjured).count();
        int criticalInjuryCount = (int) players.stream()
                .filter(Player::isInjured)
                .filter(player -> player.getInjuryDaysRemaining() >= 14)
                .count();
        int rehabCount = (int) players.stream().filter(this::needsRecovery).count();
        int averageConditionPercent = players.isEmpty()
                ? 100
                : (int) Math.round(players.stream().mapToInt(this::conditionPercent).average().orElse(100.0));

        List<PlayerDTO> queue = players.stream()
                .filter(this::needsRecovery)
                .sorted(Comparator
                        .comparing(Player::isInjured).reversed()
                        .thenComparingInt(Player::getInjuryDaysRemaining).reversed()
                        .thenComparingInt(this::fatigueValue).reversed()
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(PlayerDTO::from)
                .toList();

        return new TeamMedicalOverviewDTO(
                team.getId(),
                team.getName(),
                players.size(),
                Math.max(0, players.size() - injuredCount),
                injuredCount,
                criticalInjuryCount,
                rehabCount,
                averageConditionPercent,
                queue
        );
    }

    private boolean needsRecovery(Player player) {
        return player != null && (player.isInjured() || fatigueValue(player) >= FATIGUE_ALERT_THRESHOLD);
    }

    private int conditionPercent(Player player) {
        return Math.max(0, Math.min(100, 100 - fatigueValue(player)));
    }

    private int fatigueValue(Player player) {
        if (player == null || player.getSkills() == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, player.getSkills().getFatigue()));
    }
}