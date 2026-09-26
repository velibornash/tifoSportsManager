package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a club's week: what comes in, what goes out, and the resulting balance (Sprint 2.2).
 *
 * <p>The point of this sprint is that <b>every</b> club has an economy, not only the one the player
 * manages. Before this, gate income, broadcast money and prize money existed as formulas that
 * nothing called, and {@code Player.earnings} was seeded and read by nothing — so the wage bill was
 * free, gate receipts were fiction, and the transfer budget had nothing to spend from or be
 * limited by.
 *
 * <p>Design notes worth keeping:
 * <ul>
 *   <li><b>Append-only.</b> The budget moves by the sum of the week's lines, and the lines are
 *       kept, so a manager can see which week hurt.</li>
 *   <li><b>Per-team transaction.</b> One club failing must not roll back the other 309. This is
 *       {@code REQUIRES_NEW} for exactly that reason — a 310-club loop inside a single transaction
 *       is a single point of failure for the whole week.</li>
 *   <li><b>Idempotent per week.</b> Settlement refuses to run twice for the same club and week,
 *       because {@code advanceWeek} is reachable from more than one path and a double settlement
 *       is a silent double wage bill.</li>
 * </ul>
 */
@Service
public class WeeklyFinanceService {

    /** Broadcast income per club for a league of the given reputation weight. */
    private static final double BROADCAST_PER_WEIGHT_POINT = 145.0;

    /** Weekly facility upkeep per seat of stadium capacity. */
    private static final double UPKEEP_PER_SEAT = 0.42;

    /** Weekly merchandising base, scaled by reputation and squad size. */
    private static final double MERCHANDISING_BASE = 1_400.0;

    /** Prize money table, indexed by finishing position (0 = champion). */
    private static final double[] PRIZE_SHARE = {
            0.45, 0.28, 0.20, 0.16, 0.135, 0.115, 0.10, 0.085,
            0.075, 0.065, 0.058, 0.052, 0.046, 0.042, 0.038, 0.035
    };
    private static final double PRIZE_POOL_BASE = 2_400_000.0;

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final CompetitionEntryRepository entryRepository;
    private final FinanceLedgerEntryRepository ledger;
    private final AdmissionService admission;
    private final PitchMaintenanceService pitch;

    public WeeklyFinanceService(TeamRepository teamRepository,
                                PlayerRepository playerRepository,
                                CompetitionEntryRepository entryRepository,
                                FinanceLedgerEntryRepository ledger,
                                AdmissionService admission,
                                PitchMaintenanceService pitch) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.entryRepository = entryRepository;
        this.ledger = ledger;
        this.admission = admission;
        this.pitch = pitch;
    }

    /**
     * Settles one club for one week.
     *
     * <p>REQUIRES_NEW so a failure here cannot roll back the caller's week. Run twice for the same
     * club and week it is a no-op.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WeekResult applyWeeklyFinances(Team team, Integer seasonYear, Integer week) {
        if (team == null || team.getId() == null) return WeekResult.skipped();

        boolean alreadySettled = ledger
                .findByTeamIdAndSeasonYearAndWeekNumber(team.getId(), seasonYear, week).stream()
                .findAny().isPresent();
        if (alreadySettled) {
            return WeekResult.skipped();
        }

        List<FinanceLedgerEntry> lines = new ArrayList<>();
        double opening = budget(team);

        lines.add(gate(team));
        lines.add(broadcast(team, seasonYear, week));
        lines.add(merchandising(team));
        lines.add(prizeMoney(team, seasonYear));
        lines.add(wages(team));
        lines.add(facilityUpkeep(team));

        // Only real lines are written, so the ledger does not fill with 0.00 rows.
        List<FinanceLedgerEntry> written = lines.stream()
                .filter(e -> e != null && e.getAmount() != null && Math.abs(e.getAmount()) > 0.005)
                .toList();
        if (!written.isEmpty()) {
            ledger.saveAll(written);
        }

        double net = written.stream().mapToDouble(e -> e.getAmount()).sum();
        double closing = opening + net;
        team.setBudget(round2(closing));

        return new WeekResult(team.getId(), opening, net, closing, written.size(), false);
    }

    // --- income ---

    /**
     * Gate receipts. Uses the realised per-tier price, so a ground full of cheap seats is worth
     * less than a full ground with premium sold — the same rule the projection shows the manager.
     */
    private FinanceLedgerEntry gate(Team team) {
        Stadium s = team.getStadium();
        AdmissionService.Projection p = admission.projectHomeFixture(team);
        return FinanceLedgerEntry.of(team, null, null, FinanceCategory.GATE_REVENUE,
                p.gateRevenue,
                "Home fixture, " + p.totalAttendance + " of " + p.capacity
                        + " at an average EUR " + p.averageRealisedPrice);
    }

    /**
     * Broadcast income, driven by {@code Competition.reputationWeight}.
     *
     * <p>That field has been seeded as {@code tier × 20} since the beginning and read by nothing,
     * so a second-tier club and a first-tier club were on the same money.
     */
    private FinanceLedgerEntry broadcast(Team team, Integer seasonYear, Integer week) {
        Competition competition = team.getCompetition();
        int weight = competition != null && competition.getReputationWeight() != null
                ? competition.getReputationWeight() : 20;
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.BROADCAST,
                weight * BROADCAST_PER_WEIGHT_POINT,
                "League broadcast share, reputation weight " + weight);
    }

    private FinanceLedgerEntry merchandising(Team team) {
        double reputation = team.getReputation() == null ? 50 : team.getReputation();
        long squad = playerRepository.findByTeamId(team.getId()).size();
        double value = MERCHANDISING_BASE * (reputation / 50.0) * Math.max(0.5, squad / 18.0);
        return FinanceLedgerEntry.of(team, null, null, FinanceCategory.MERCHANDISING, value,
                squad + " players, reputation " + Math.round(reputation));
    }

    /** Prize money is only paid once the season is finished, so this is a no-op mid-season. */
    private FinanceLedgerEntry prizeMoney(Team team, Integer seasonYear) {
        return null;
    }

    // --- costs ---

    /**
     * The wage bill: the sum of every player's weekly earnings.
     *
     * <p>{@code Player.earnings} was seeded with realistic values and read by nothing, which made
     * the most important cost in football free.
     */
    private FinanceLedgerEntry wages(Team team) {
        List<Player> squad = playerRepository.findByTeamId(team.getId());
        double bill = squad.stream().mapToDouble(p -> Math.max(0, p.getEarnings())).sum();
        return FinanceLedgerEntry.of(team, null, null, FinanceCategory.WAGES, bill,
                squad.size() + " players under contract");
    }

    /**
     * Facility upkeep: the stadium, plus the pitch maintenance the club has actually funded.
     *
     * <p>Maintenance is charged from the maintenance programme rather than a flat rate, so a club
     * that stops paying for its pitch is spending less here — and is getting a worse pitch, which
     * is the trade the manager is making.
     */
    private FinanceLedgerEntry facilityUpkeep(Team team) {
        Stadium s = team.getStadium();
        if (s == null) return null;
        int capacity = s.getCapacity() == null ? 0 : s.getCapacity();
        double upkeep = capacity * UPKEEP_PER_SEAT;

        double maintenanceSpent = pitch.remainingOf(s);
        if (maintenanceSpent > 0) {
            PitchMaintenanceService.MaintenanceResult r =
                    pitch.applyWeeklyMaintenance(s, maintenanceSpent);
            if (r.restored() > 0) {
                return FinanceLedgerEntry.of(team, null, null, FinanceCategory.PITCH_MAINTENANCE,
                        r.spent(), "Pitch work: condition " + r.before() + " -> " + r.after());
            }
            return FinanceLedgerEntry.of(team, null, null, FinanceCategory.FACILITY_UPKEEP,
                    upkeep, capacity + " seats");
        }
        return FinanceLedgerEntry.of(team, null, null, FinanceCategory.FACILITY_UPKEEP,
                upkeep, capacity + " seats, no pitch programme funded");
    }

    // --- helpers ---

    /** Pays a season's prize money once the league table is final. */
    @Transactional
    public void awardPrizeMoney(SeasonCompetition sc, Integer seasonYear) {
        if (sc == null || sc.getCompetition() == null) return;
        double pool = PRIZE_POOL_BASE * Math.max(1, sc.getCompetition().getTier() == null
                ? 1 : sc.getCompetition().getTier());
        List<CompetitionEntry> entries = new ArrayList<>(entryRepository.findBySeasonCompetition(sc));
        entries.sort((a, b) -> Integer.compare(position(b), position(a)));
        for (int i = 0; i < entries.size(); i++) {
            CompetitionEntry e = entries.get(i);
            double share = i < PRIZE_SHARE.length ? PRIZE_SHARE[i] : 0.03;
            double amount = pool * share;
            Team t = e.getTeam();
            if (t == null) continue;
            ledger.save(FinanceLedgerEntry.of(t, seasonYear, 0, FinanceCategory.PRIZE_MONEY, amount,
                    "Finished P" + (i + 1) + " of " + entries.size()));
            t.setBudget(round2(budget(t) + amount));
            teamRepository.save(t);
        }
    }

    private int position(CompetitionEntry e) {
        return e.getPosition() == null ? Integer.MAX_VALUE : e.getPosition();
    }

    private double budget(Team team) {
        return team.getBudget() == null ? 0 : team.getBudget();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** What one club's week did. */
    public record WeekResult(Long teamId, double opening, double net, double closing,
                             int lines, boolean notApplied) {
        public static WeekResult skipped() {
            return new WeekResult(null, 0, 0, 0, 0, true);
        }
    }
}
