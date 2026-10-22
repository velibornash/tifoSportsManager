package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.util.Rounding;
import lombok.RequiredArgsConstructor;
import org.tifo.footballmanager.newLogic.dto.PlayerDTO;
import org.tifo.footballmanager.newLogic.dto.TeamMedicalOverviewDTO;
import org.tifo.footballmanager.newLogic.exception.ApiException;
import org.tifo.footballmanager.newLogic.model.FinanceCategory;
import org.tifo.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.footballmanager.newLogic.model.StaffRole;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.StaffMemberRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TeamMedicalService {
    private static final int FATIGUE_ALERT_THRESHOLD = 18;
    private static final int RECOVERY_FATIGUE_REDUCTION = 12;

    /**
     * What a club recovers at when it employs no physio. Not zero: a club without one is slower, not
     * injured, and a floor of zero would make the medical centre useless to everybody who has not hired.
     */
    private static final double NO_PHYSIO_FACTOR = 0.6;

    private static final double SURGERY_BASE_FEE = 40_000.0;
    private static final double SURGERY_PER_DOCTOR_POINT = 2_500.0;
    private static final double SURGERY_MIN_SHARE = 0.30;
    private static final double SURGERY_MAX_SHARE = 0.50;
    private static final int RECOVERY_DAY_REDUCTION = 3;

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final FinanceLedgerEntryRepository ledger;
    private final SeasonService seasons;

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
            // **Physio handles fatigue, doctor handles injuries - two roles, two jobs** (owner,
            // 2026-10-18). That sentence was in the comment below and the physio did nothing at all: the
            // fatigue reduction was a flat twelve for every club in the world, whether it employed a
            // physio or not. He is now what the comment always claimed.
            int reduction = (int) Math.round(RECOVERY_FATIGUE_REDUCTION * physioFactor(teamId));
            int reducedFatigue = Math.max(0, skills.getFatigue() - reduction);
            skills.setSkill(SkillName.FATIGUE, reducedFatigue);
        }

        int currentInjuryDays = Math.max(0, player.getInjuryDaysRemaining());
        if (currentInjuryDays > 0) {
            // **Doctor shortens injury recovery.** The base reduction is 3 days; if the club has a
            // doctor, his fitness attribute adds up to 4 more (20 fitness = 7 days total). Physio
            // handles fatigue, doctor handles injuries - two roles, two jobs.
            int reduction = RECOVERY_DAY_REDUCTION;
            StaffMember doctor = staffMemberRepository.findByTeamId(teamId).stream()
                    .filter(sm -> sm.getRole() == StaffRole.DOCTOR).findFirst().orElse(null);
            if (doctor != null && doctor.getFitness() != null) {
                reduction += (int) Math.round(doctor.getFitness() / 5.0);
            }
            int next = Math.max(0, currentInjuryDays - reduction);
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

    @Transactional
    public TeamMedicalOverviewDTO applySurgery(Long teamId, Long playerId) {
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
        if (!player.isInjured() || player.getInjuryDaysRemaining() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_INJURED",
                    "There is nothing to operate on - he is not injured.");
        }
        if (player.isInjurySurgeryDone()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALREADY_OPERATED",
                    "He has already had this operation. One injury, one operation.");
        }
        StaffMember doctor = staffMemberRepository.findByTeamId(teamId).stream()
                .filter(sm -> sm.getRole() == StaffRole.DOCTOR).findFirst().orElse(null);
        if (doctor == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_DOCTOR",
                    "The club has no doctor, so nobody can operate. Hire one and he can.");
        }

        int fitness = Math.max(0, Math.min(20, doctor.getFitness() == null ? 0 : doctor.getFitness()));
        double cost = SURGERY_BASE_FEE + fitness * SURGERY_PER_DOCTOR_POINT;
        double budget = team.getBudget() == null ? 0.0 : team.getBudget();
        if (cost > budget) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_MONEY",
                    "The operation costs " + money(cost) + " and the club has " + money(budget) + ".");
        }

        double share = SURGERY_MIN_SHARE
                + (fitness / 20.0) * (SURGERY_MAX_SHARE - SURGERY_MIN_SHARE);
        int remaining = player.getInjuryDaysRemaining();
        int next = Math.max(0, (int) Math.round(remaining - remaining * share));
        player.setInjuryDaysRemaining(next);
        player.setInjurySurgeryDone(true);
        if (next == 0) {
            player.setInjurySeasonNumber(null);
            player.setInjuryWeekNumber(null);
            player.setInjured(false);
        }
        playerRepository.save(player);

        team.setBudget(round2(budget - cost));
        teamRepository.save(team);
        ledger.save(FinanceLedgerEntry.of(team, seasonYear(), weekNumber(),
                FinanceCategory.MEDICAL, -cost,
                "Operation on " + player.getName() + " - " + remaining + " days of rehab cut to "
                        + next + " by " + (doctor.getName() == null ? "the club doctor" : doctor.getName())
                        + " - " + money(cost)));

        return buildOverview(team);
    }

    private SurgeryOffer surgeryOffer(Team team, Player player) {
        int remaining = player.getInjuryDaysRemaining();
        if (!player.isInjured() || remaining <= 0) {
            return null;
        }
        if (player.isInjurySurgeryDone()) {
            return new SurgeryOffer(false, 0.0, "He has already had this operation.");
        }
        StaffMember doctor = staffMemberRepository.findByTeamId(team.getId()).stream()
                .filter(sm -> sm.getRole() == StaffRole.DOCTOR).findFirst().orElse(null);
        if (doctor == null) {
            return new SurgeryOffer(false, 0.0, "The club has no doctor.");
        }
        int fitness = Math.max(0, Math.min(20, doctor.getFitness() == null ? 0 : doctor.getFitness()));
        double cost = SURGERY_BASE_FEE + fitness * SURGERY_PER_DOCTOR_POINT;
        double share = SURGERY_MIN_SHARE + (fitness / 20.0) * (SURGERY_MAX_SHARE - SURGERY_MIN_SHARE);
        int next = Math.max(0, (int) Math.round(remaining - remaining * share));
        return new SurgeryOffer(true, cost, remaining + " days of rehab would become " + next + ".");
    }

    private record SurgeryOffer(boolean available, double cost, String note) {
    }

    private int seasonYear() {
        return seasons.getActiveSeasonYear();
    }

    private int weekNumber() {
        return seasons.getCurrentWeek();
    }

    private String money(double amount) {
        return String.format("%,.0f EUR", amount);
    }

    private static double round2(double value) {
        return Rounding.to2(value);
    }

    /**
     * What the club's physio is worth to a recovery session (owner, 2026-10-18).
     *
     * <p>The owner: *"bez fizija se sporije oporavljaju"* - without a physio they recover more slowly. So
     * the floor is not zero, it is slow: a club with nobody recovers at 60% of the rate, and the best
     * physio in the game restores the full twelve points. Between those, it scales with what he knows
     * about fitness, which is the skill a physio is hired for.
     *
     * <p>Judged on {@code coachRating(STAMINA)} rather than his overall, for the same reason the doctor is
     * judged on {@code fitness}: a medical role is worth what he knows about bodies, not how good a coach
     * he is in general.
     */
    private double physioFactor(Long teamId) {
        StaffMember best = null;
        int bestRating = 0;
        for (StaffMember member : staffMemberRepository.findByTeamId(teamId)) {
            if (member == null || member.getRole() != StaffRole.PHYSIO) continue;
            int rating = Math.max(1, Math.min(20, member.coachRating(SkillName.STAMINA)));
            if (best == null || rating > bestRating) {
                best = member;
                bestRating = rating;
            }
        }
        if (best == null) {
            return NO_PHYSIO_FACTOR;
        }
        return NO_PHYSIO_FACTOR + (1.0 - NO_PHYSIO_FACTOR) * (bestRating / 20.0);
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

        List<PlayerDTO> rows = new ArrayList<>();
        for (PlayerDTO row : queue) {
            Player player = players.stream()
                    .filter(p -> Objects.equals(p.getId(), row.getId()))
                    .findFirst().orElse(null);
            if (player != null && player.isInjured()) {
                SurgeryOffer offer = surgeryOffer(team, player);
                if (offer != null) {
                    row.setSurgeryAvailable(offer.available());
                    row.setSurgeryCost(offer.cost());
                    row.setSurgeryNote(offer.note());
                }
            }
            rows.add(row);
        }

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