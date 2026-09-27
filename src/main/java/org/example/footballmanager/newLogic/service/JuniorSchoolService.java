package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.junior.JuniorSchoolStateDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.JuniorRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The junior school as a thing a club buys and switches off (Sprint 5.3a).
 *
 * <p><b>Owner specification, 2026-09-27.</b> It costs a one-off activation fee plus a weekly upkeep.
 * It can be switched on in <b>week 1 only</b> and switched off in <b>week 12 only</b>. Switching it off
 * <b>automatically promotes every junior and lists each one for transfer</b>. No AI club has one at
 * all.
 *
 * <h2>Why the week windows are narrow on purpose</h2>
 * The whole point is that the academy is a commitment rather than a menu. A toggle available in any
 * week would let a manager wait for a good scouting report before paying, and switch the school off
 * the week before graduation — which is a free option on the best prospects in the game, obtained by
 * watching them first. Restricting the windows removes the arbitrage.
 *
 * <p>The deactivation behaviour is the sharp edge, and it is deliberate. A club that closes its school
 * in week 12 loses its intake: everyone graduates and goes on the market. That is a real and expensive
 * mistake available to make, and the feature is worthless without it — a school that could be idled
 * at no cost would make the weekly fee meaningless.
 *
 * <h2>Where the money goes</h2>
 * The activation fee is charged through the weekly ledger, and the upkeep is a standing line. Scout
 * wages are <b>not</b> charged here: a scout is a {@code StaffMember} and his wage already appears on
 * {@code STAFF_WAGES} from Sprint 2.2, whether or not he is abroad. A second charge would bill the
 * same man twice in the same week.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JuniorSchoolService {

    /** The only week a school may be opened. */
    public static final int OPEN_WEEK = 1;

    /** The only week a school may be closed. */
    public static final int CLOSE_WEEK = SeasonCalendar.WEEKS_PER_SEASON;

    private final TeamRepository teamRepository;
    private final JuniorRepository juniorRepository;
    private final FinanceLedgerEntryRepository ledger;
    private final YouthAcademyService youthAcademyService;

    /**
     * Opens the school, charging the one-off fee.
     *
     * @throws ApiException {@code JUNIOR_SCHOOL_WINDOW_CLOSED} outside week 1, and
     *                      {@code JUNIOR_SCHOOL_ALREADY_ACTIVE} if it is already running
     */
    @Transactional
    public JuniorSchoolStateDTO open(Long teamId, int seasonNumber, int weekNumber) {
        Team team = requireTeam(teamId);
        requireWindow(weekNumber, OPEN_WEEK, "opened");
        if (isActive(team)) {
            throw new ApiException(HttpStatus.CONFLICT, "JUNIOR_SCHOOL_ALREADY_ACTIVE",
                    "The junior school is already running.");
        }

        double fee = activationFee(team);
        double budget = team.getBudget() == null ? 0.0 : team.getBudget();
        if (fee > budget) {
            // Refused rather than allowed to go negative. An academy the club cannot pay for would
            // roll an intake it cannot staff, which is precisely the free academy this replaced.
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "JUNIOR_SCHOOL_UNAFFORDABLE",
                    "Opening the junior school costs " + Math.round(fee)
                            + " and the club has " + Math.round(budget) + ".");
        }

        team.setJuniorSchoolActive(true);
        team.setJuniorSchoolSinceSeason(seasonNumber);
        team.setBudget(budget - fee);
        teamRepository.save(team);
        ledger.save(FinanceLedgerEntry.of(team, seasonNumber, weekNumber,
                FinanceCategory.JUNIOR_SCHOOL, fee, "Junior school opened"));

        log.info("Junior school opened by {} (season {}, week {}): {} charged.",
                team.getName(), seasonNumber, weekNumber, Math.round(fee));
        return stateOf(team, seasonNumber, weekNumber);
    }

    /**
     * Closes the school: every junior graduates and goes on the transfer list.
     *
     * @return how many prospects were released to the market
     */
    @Transactional
    public int close(Long teamId, int seasonNumber, int weekNumber) {
        Team team = requireTeam(teamId);
        requireWindow(weekNumber, CLOSE_WEEK, "closed");
        if (!isActive(team)) {
            throw new ApiException(HttpStatus.CONFLICT, "JUNIOR_SCHOOL_NOT_ACTIVE",
                    "The junior school is not running.");
        }

        List<Junior> intake = juniorRepository.findByTeamIdAndStatus(teamId, JuniorStatus.ACTIVE);
        int released = 0;
        for (Junior junior : intake) {
            // No catch. A swallowed exception inside a transaction marks it rollback-only, and the
            // caller then fails on commit with an UnexpectedRollbackException that names neither the
            // junior nor the reason. One bad prospect must fail loudly and visibly instead.
            youthAcademyService.graduateForSchoolClosure(junior.getId(), seasonNumber, weekNumber);
            released++;
        }

        team.setJuniorSchoolActive(false);
        teamRepository.save(team);
        log.info("Junior school closed by {} (season {}, week {}): {} prospects graduated onto the transfer list.",
                team.getName(), seasonNumber, weekNumber, released);
        return released;
    }

    /** Whether this club is currently running a junior school. Null and false behave the same. */
    public static boolean isActive(Team team) {
        return team != null && Boolean.TRUE.equals(team.getJuniorSchoolActive());
    }

    /** Whether the school may be opened right now — the week-1 window, ignoring money and state. */
    public static boolean canOpenNow(int weekNumber) {
        return weekNumber == OPEN_WEEK;
    }

    /** Whether the school may be closed right now — the week-12 window. */
    public static boolean canCloseNow(int weekNumber) {
        return weekNumber == CLOSE_WEEK;
    }

    /**
     * The weekly upkeep, charged for as long as the school is open.
     *
     * <p>Charged from the week <b>after</b> it opens. The opening week is already covered by the
     * one-off fee, and charging both in the same week is how a "fee" quietly becomes a subscription
     * with a misleading label.
     */
    public static double weeklyUpkeep(Team team) {
        if (team == null || !Boolean.TRUE.equals(team.getJuniorSchoolActive())) return 0.0;
        return upkeep(team);
    }

    /**
     * The club's academy state, priced, with both windows made explicit.
     *
     * <p>The window information is on the DTO rather than left to the UI to derive from the week,
     * because "you can only do this in week 1" is a rule the manager has to be told, not inferred.
     */
    @Transactional(readOnly = true)
    public JuniorSchoolStateDTO stateFor(Long teamId, int seasonNumber, int weekNumber) {
        return stateOf(requireTeam(teamId), seasonNumber, weekNumber);
    }

    private JuniorSchoolStateDTO stateOf(Team team, int seasonNumber, int weekNumber) {
        boolean active = isActive(team);
        JuniorSchoolStateDTO dto = new JuniorSchoolStateDTO();
        dto.setTeamId(team.getId());
        dto.setTeamName(team.getName());
        dto.setActive(active);
        dto.setSinceSeason(team.getJuniorSchoolSinceSeason());
        dto.setSeasonNumber(seasonNumber);
        dto.setWeekNumber(weekNumber);
        dto.setCanOpen(canOpenNow(weekNumber) && !active);
        dto.setCanClose(canCloseNow(weekNumber) && active);
        dto.setActivationFee(activationFee(team));
        dto.setWeeklyUpkeep(upkeep(team));

        if (active) {
            dto.setActiveJuniors((int) juniorRepository.countByTeamIdAndStatus(team.getId(), JuniorStatus.ACTIVE));
            dto.setNote(weekNumber == OPEN_WEEK
                    ? "The school is open. This is the only week it could have been opened."
                    : "Running. It cannot be closed until week " + CLOSE_WEEK + ".");
        } else {
            dto.setActiveJuniors(0);
            dto.setNote(weekNumber == OPEN_WEEK
                    ? "Not running. A school can only be opened in week " + OPEN_WEEK + "."
                    : "Not running, and it cannot be opened now — the window is week " + OPEN_WEEK + ".");
        }
        return dto;
    }

    /**
     * The one-off activation fee, scaled by what the club can afford.
     *
     * <p>Deliberately not a flat number. A flat fee either punishes a small club out of the academy
     * entirely or is free to a large one, and the academy is the feature that makes a small club's
     * season mean something.
     *
     * <p><b>Static on purpose.</b> This is a pure function of the club's reputation, and the weekly
     * finance tick needs it. Injecting the whole service there would couple the ledger to a feature
     * it does not own and risk a circular dependency through the academy's own dependencies, for the
     * sake of one multiplication.
     */
    public static double activationFee(Team team) {
        return round2(5_000.0 + reputation(team) * 900.0);
    }

    /** The weekly upkeep, scaled the same way so the two figures stay related. */
    public static double upkeep(Team team) {
        return round2(1_200.0 + reputation(team) * 120.0);
    }

    /**
     * Reputation, defaulted when unrecorded.
     *
     * <p>It is a raw {@code Double} off a seeded club, so 90.8776... is a perfectly ordinary value and
     * it must not reach a price.
     */
    private static double reputation(Team team) {
        if (team == null || team.getReputation() == null) return 50.0;
        return team.getReputation();
    }

    /**
     * Money is rounded to two decimals on the way out, not on the way to the screen.
     *
     * <p>{@code 5000 + 90.87761 * 900} is 82191.20164797436, and an activation fee carrying fourteen
     * decimal places is the same defect as a pitch at 67.26952925761245 — it only looks right because
     * the UI happens to format it. Rounding at the source means every consumer gets a clean number.
     */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void requireWindow(int weekNumber, int required, String action) {
        if (weekNumber == required) return;
        throw new ApiException(HttpStatus.CONFLICT, "JUNIOR_SCHOOL_WINDOW_CLOSED",
                "A junior school can only be " + action + " in week " + required
                        + "; it is week " + weekNumber + ".");
    }

    private Team requireTeam(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found."));
    }
}
