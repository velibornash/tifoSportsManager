package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.util.LeagueTableOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
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

    private static final Logger log = LoggerFactory.getLogger(WeeklyFinanceService.class);

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
    private final StaffSponsorService staffSponsors;
    private final TrainingFacilityService facilities;

    public WeeklyFinanceService(TeamRepository teamRepository,
                                PlayerRepository playerRepository,
                                CompetitionEntryRepository entryRepository,
                                FinanceLedgerEntryRepository ledger,
                                AdmissionService admission,
                                PitchMaintenanceService pitch,
                                StaffSponsorService staffSponsors,
                                TrainingFacilityService facilities) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.entryRepository = entryRepository;
        this.ledger = ledger;
        this.admission = admission;
        this.pitch = pitch;
        this.staffSponsors = staffSponsors;
        this.facilities = facilities;
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

        // **A national side is not a club and has no club's economy.**
        //
        // The settlement ran over `teamRepository.findAll()`, so it reached the 96 national sides along
        // with the 14,880 clubs, and nothing below here objected: `broadcast` reads the competition and
        // defaults a null one to weight 20, so every national side in the world was paid a league
        // broadcast share; `gate` projected a home fixture it never plays; `merchandising` sized income
        // off its 25-player squad; and a budget was written onto the team.
        //
        // **A club is a team with a competition**, which is the definition the rest of the codebase
        // already uses. Deliberately NOT `type == CLUB`: PyramidBuilder creates every club in the world
        // and never sets `type`, so that flag would match nothing.
        if (team.getCompetition() == null) {
            return WeekResult.skipped();
        }

        boolean alreadySettled = ledger
                .findByTeamIdAndSeasonYearAndWeekNumber(team.getId(), seasonYear, week).stream()
                .findAny().isPresent();
        if (alreadySettled) {
            return WeekResult.skipped();
        }

        List<FinanceLedgerEntry> lines = new ArrayList<>();
        double opening = budget(team);

        // Every line carries the season and week it belongs to. Half of these used to pass null,
        // which meant gate receipts, wages, upkeep and merchandising were written with no season
        // and could never be read back - so a club's income looked like zero and it was granted no
        // transfer budget at all.
        lines.add(gate(team, seasonYear, week));
        lines.add(broadcast(team, seasonYear, week));
        lines.add(merchandising(team, seasonYear, week));
        lines.add(prizeMoney(team, seasonYear));
        lines.add(wages(team, seasonYear, week));
        lines.add(staffWages(team, seasonYear, week));
        lines.add(sponsorship(team, seasonYear, week));
        lines.add(facilityUpkeep(team, seasonYear, week));
        lines.add(juniorSchoolUpkeep(team, seasonYear, week));

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
    private FinanceLedgerEntry gate(Team team, Integer seasonYear, Integer week) {
        Stadium s = team.getStadium();
        AdmissionService.Projection p = admission.projectHomeFixture(team);
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.GATE_REVENUE,
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

    private FinanceLedgerEntry merchandising(Team team, Integer seasonYear, Integer week) {
        double reputation = team.getReputation() == null ? 50 : team.getReputation();
        long squad = playerRepository.findByTeamId(team.getId()).size();
        double value = MERCHANDISING_BASE * (reputation / 50.0) * Math.max(0.5, squad / 18.0);
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.MERCHANDISING, value,
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
    private FinanceLedgerEntry wages(Team team, Integer seasonYear, Integer week) {
        List<Player> squad = playerRepository.findByTeamId(team.getId());
        double bill = squad.stream().mapToDouble(p -> Math.max(0, p.getEarnings())).sum();
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.WAGES, bill,
                squad.size() + " players under contract");
    }

    /**
     * Staff wages. The head coach and the rest of the backroom are a real weekly cost, which is what
     * makes a coaching appointment a budget decision.
     */
    /**
     * The week number matters: a weekly ledger line with a null week is invisible to the weekly
     * view and to the idempotency check, so it would be paid for but never displayed.
     */
    /**
     * The junior school's weekly upkeep, while it is running (Sprint 5.3a).
     *
     * <p>Not charged in the opening week: that week is already covered by the one-off activation fee,
     * and charging both in the same week turns a fee into a subscription with a misleading label.
     *
     * <p>Deliberately silent for the 300-odd AI clubs, none of which have a school. A ledger line that
     * is always zero for most of the database is noise.
     */
    private FinanceLedgerEntry juniorSchoolUpkeep(Team team, Integer seasonYear, Integer week) {
        if (!Boolean.TRUE.equals(team.getJuniorSchoolActive())) return null;
        if (week != null && week == JuniorSchoolService.OPEN_WEEK) return null;
        double upkeep = JuniorSchoolService.weeklyUpkeep(team);
        if (upkeep <= 0) return null;
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.JUNIOR_SCHOOL, upkeep,
                "Junior school upkeep");
    }

    private FinanceLedgerEntry staffWages(Team team, Integer seasonYear, Integer week) {
        double bill = staffSponsors.weeklyStaffWage(team.getId());
        int n = staffSponsors.staffCount(team.getId());
        if (bill <= 0) return null;
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.STAFF_WAGES, bill,
                n + " staff on the books");
    }

    /**
     * Sponsorship income, from contracts that are actually live this season.
     *
     * <p>Previously the Finances page displayed three sponsors whose annual values were derived
     * from the club's own budget. The money existed only in the browser and was never paid to
     * anyone, so the wage bill was measured against income that was not real.
     */
    private FinanceLedgerEntry sponsorship(Team team, Integer seasonYear, Integer week) {
        int season = seasonYear == null ? java.time.Year.now().getValue() : seasonYear;
        double income = staffSponsors.weeklySponsorshipIncome(team.getId(), season);
        if (income <= 0) return null;
        return FinanceLedgerEntry.of(team, season, week, FinanceCategory.SPONSORSHIP, income,
                "Sponsorship contracts, season " + season);
    }

    /**
     * Facility upkeep: the stadium, plus the pitch maintenance the club has actually funded.
     *
     * <p>Maintenance is charged from the maintenance programme rather than a flat rate, so a club
     * that stops paying for its pitch is spending less here — and is getting a worse pitch, which
     * is the trade the manager is making.
     */
    private FinanceLedgerEntry facilityUpkeep(Team team, Integer seasonYear, Integer week) {
        Stadium s = team.getStadium();
        if (s == null) return null;
        int capacity = s.getCapacity() == null ? 0 : s.getCapacity();
        // What the club has built for training is billed on top of the ground it owns rather than
        // folded silently into the same number - a manager reading this line should be able to see
        // what his facilities are costing him.
        double trainingUpkeep = facilities.weeklyUpkeep(team);
        double upkeep = capacity * UPKEEP_PER_SEAT + trainingUpkeep;

        double maintenanceSpent = pitch.remainingOf(s);
        if (maintenanceSpent > 0) {
            PitchMaintenanceService.MaintenanceResult r =
                    pitch.applyWeeklyMaintenance(s, maintenanceSpent);
            if (r.restored() > 0) {
                return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.PITCH_MAINTENANCE,
                        r.spent(), "Pitch work: condition " + r.before() + " -> " + r.after());
            }
            return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.FACILITY_UPKEEP,
                    upkeep, capacity + " seats + " + Math.round(trainingUpkeep)
                            + " training facilities");
        }
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.FACILITY_UPKEEP,
                upkeep, capacity + " seats + " + Math.round(trainingUpkeep)
                        + " training facilities, no pitch programme funded");
    }

    // --- helpers ---

    /**
     * Pays a season's prize money by finishing position, once the table is final.
     *
     * <p>The weekly {@link #prizeMoney} line deliberately pays nothing — "only paid once the season is
     * finished" — and this is the other half. It had <b>no caller at all</b>, so no club has ever been
     * paid prize money in this game.
     *
     * <p><b>Ranked by {@link LeagueTableOrder}, not by the stored {@code position}.</b> The first
     * version of this method sorted on {@code CompetitionEntry.position}, which nothing sets during a
     * season: {@code PyramidBuilder} writes it once when the world is built and it is never updated. So
     * wiring it as written would have paid the champion's money to whichever club happened to be seeded
     * first, every season, silently. {@code LeagueTableOrder} is already the one definition of a league
     * table (owner decision S8.4) and is what promotion and relegation read, so the money follows the
     * same table the manager is shown.
     *
     * <p>A competition nobody played in is skipped. Without that, a world that is seeded and then rolled
     * over before a single match — exactly what a fresh database does — would pay out a full prize pool
     * for a season that did not happen.
     */
    @Transactional
    public int awardPrizeMoney(SeasonCompetition sc, Integer seasonYear) {
        if (sc == null || sc.getCompetition() == null) return 0;
        List<CompetitionEntry> played = entryRepository.findBySeasonCompetition(sc);
        if (played == null || played.isEmpty() || !wasPlayed(played)) {
            return 0;
        }

        double pool = PRIZE_POOL_BASE * Math.max(1, sc.getCompetition().getTier() == null
                ? 1 : sc.getCompetition().getTier());
        List<CompetitionEntry> table = LeagueTableOrder.sort(played);
        int paid = 0;
        for (int i = 0; i < table.size(); i++) {
            CompetitionEntry e = table.get(i);
            Team t = e.getTeam();
            if (t == null) continue;
            double share = i < PRIZE_SHARE.length ? PRIZE_SHARE[i] : 0.03;
            double amount = pool * share;
            ledger.save(FinanceLedgerEntry.of(t, seasonYear, 0, FinanceCategory.PRIZE_MONEY, amount,
                    "Finished P" + (i + 1) + " of " + table.size()));
            t.setBudget(round2(budget(t) + amount));
            teamRepository.save(t);
            paid++;
        }
        return paid;
    }

    /** Whether anything at all was played in this competition that season. */
    private boolean wasPlayed(List<CompetitionEntry> entries) {
        for (CompetitionEntry e : entries) {
            if (e == null) continue;
            if (LeagueTableOrder.points(e) > 0
                    || LeagueTableOrder.goalsScored(e) > 0
                    || LeagueTableOrder.goalsConceded(e) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pays prize money across every competition of a finished season.
     *
     * <p>The season's competitions come from one query rather than one per competition: the rollover
     * already does far more per-league work than this, and this is the one place where it can be
     * counted in a single round trip.
     *
     * @return how many clubs were paid
     */
    @Transactional
    public int awardPrizeMoneyForSeason(Integer seasonYear, SeasonCompetitionRepository scRepository) {
        if (seasonYear == null) return 0;
        List<SeasonCompetition> competitions = scRepository.findBySeasonYear(seasonYear);
        if (competitions == null || competitions.isEmpty()) return 0;
        int paid = 0;
        for (SeasonCompetition sc : competitions) {
            try {
                paid += awardPrizeMoney(sc, seasonYear);
            } catch (RuntimeException e) {
                // One competition's payout must not cost every other league its prize money.
                log.warn("Prize money failed for competition {}: {}", sc.getId(), e.getMessage());
            }
        }
        return paid;
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
