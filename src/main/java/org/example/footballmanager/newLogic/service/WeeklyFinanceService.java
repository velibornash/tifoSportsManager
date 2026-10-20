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

    /** Weekly facility upkeep per seat of stadium capacity. */
    private static final double UPKEEP_PER_SEAT = 0.42;

    /**
     * Weekly merchandising base, scaled by reputation and squad size.
     *
     * <p>Raised from 1,400. At that figure merchandising was 1% of the wage bill and 3% of income —
     * a line that existed, was written to the ledger with its own note, and moved nothing. A side's
     * shirt is worth having in proportion to how many people want it, and 1,400 a week is not a
     * number that describes any football club.
     */
    private static final double MERCHANDISING_BASE = 26_000.0;

    /** Prize money table, indexed by finishing position (0 = champion). */
    private static final double[] PRIZE_SHARE = {
            0.45, 0.28, 0.20, 0.16, 0.135, 0.115, 0.10, 0.085,
            0.075, 0.065, 0.058, 0.052, 0.046, 0.042, 0.038, 0.035
    };
    private static final double PRIZE_POOL_BASE = 2_400_000.0;

    /** The coin that separates two teams level on everything the table can measure. */
    private final RankingTieBreakService tieBreaks;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final CompetitionEntryRepository entryRepository;
    private final FinanceLedgerEntryRepository ledger;
    private final AdmissionService admission;
    private final PitchMaintenanceService pitch;
    private final StaffSponsorService staffSponsors;
    private final TrainingFacilityService facilities;

    public WeeklyFinanceService(RankingTieBreakService tieBreaks,
                                TeamRepository teamRepository,
                                PlayerRepository playerRepository,
                                CompetitionEntryRepository entryRepository,
                                FinanceLedgerEntryRepository ledger,
                                AdmissionService admission,
                                PitchMaintenanceService pitch,
                                StaffSponsorService staffSponsors,
                                TrainingFacilityService facilities) {
        this.tieBreaks = tieBreaks;
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

        // **Only human teams and active countries have an economy** (owner, 2026-10-11).
        //
        // "Simulirane lige i AI klubovi nemaju ekonomiju i to, tu su samo za internacionalne mečeve,
        // bot klubovi i za NT bot reprezentacije. Samo human timovi (ili aktivne lige, šta god od to
        // dvoje) imaju ekonomiju."
        //
        // This check is here and not only in the weekly loop on purpose. In the loop alone, any other
        // caller that reached for a club directly would settle it — and the failure is invisible,
        // because settling an AI club looks exactly like settling a real one until you add up what
        // 13,630 of them created in a week.
        //
        // Their players still get the default pace training and their national sides still draw.
        // This is about money and nothing else.
        if (team.getCountry() != null
                && team.getCountry().getState() != org.example.footballmanager.newLogic.model.CountryState.ACTIVE
                && !team.isHumanControlled()) {
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
        // Read the squad once. Three of the lines below are a function of it, and the wage bill is
        // what the broadcast money is a share of, so they all have to see the same squad.
        List<Player> squad = playerRepository.findByTeamId(team.getId());

        // **A club with no squad has no economy.**
        //
        // Measured on the owner's world, 2026-10-11: 14,880 clubs, of which **13,630 have no players at
        // all** — 47 `SIMULATED` countries whose 31 divisions were built as names and tables and never
        // populated, plus the rest of their lower tiers. Every one of them was being settled anyway,
        // and a club with no players has no wages, no stadium upkeep and no gate, so what it received
        // was merchandising and nothing else:
        //
        //     13,630 clubs with no players   +199,430,400 this week, from nothing
        //     1,250 clubs with players       -56,281,117 this week
        //
        // That is money created every week by the game's own bookkeeping, and it accumulates: a
        // second-division shell in Austria is already carrying a larger budget than a real club in
        // Serbia. Settling nothing for a club with no squad is both the honest answer and ten times
        // less work.
        if (squad.isEmpty()) {
            return WeekResult.skipped();
        }

        double wageBill = ClubEconomics.wageBill(team, squad);

        lines.add(gate(team, seasonYear, week));
        lines.add(broadcast(team, seasonYear, week, wageBill));
        lines.add(merchandising(team, seasonYear, week, squad));
        lines.add(prizeMoney(team, seasonYear));
        lines.add(wages(team, seasonYear, week, squad, wageBill));
        lines.add(staffWages(team, seasonYear, week));
        lines.add(sponsorship(team, seasonYear, week));
        lines.addAll(facilityUpkeep(team, seasonYear, week));
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
     * Broadcast income, driven by {@code Competition.reputationWeight}.
     *
     * <p>That field has been seeded as {@code tier × 20} since the beginning and read by nothing,
     * so a second-tier club and a first-tier club were on the same money.
     */
    /**
     * Broadcast income, as a share of the club's own wage bill.
     *
     * <p>Was {@code competition.reputationWeight × 145} — a pure function of the division, so every
     * club in a league earned the same and none of it moved when the club did. Measured on the owner's
     * database that put a top-flight club paying 18.5 million a week against 2,900 of television money.
     *
     * <p>See {@link ClubEconomics#broadcastShareOfWageBill}. The note carries the arithmetic, because a
     * manager reading a finance line is owed the working.
     */
    private FinanceLedgerEntry broadcast(Team team, Integer seasonYear, Integer week, double wageBill) {
        Competition competition = team.getCompetition();
        double share = ClubEconomics.broadcastShareOfWageBill(competition);
        double income = wageBill * share;
        String division = competition == null || competition.getTier() == null
                ? "this division" : "tier " + competition.getTier();
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.BROADCAST, income,
                String.format("%s of a %s weekly wage bill of %s", percent(share), division,
                        money(wageBill)));
    }

    private FinanceLedgerEntry gate(Team team, Integer seasonYear, Integer week) {
        AdmissionService.Projection p = admission.projectHomeFixture(team);
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.GATE_REVENUE,
                p.gateRevenue,
                // `money()` carries the currency itself, so the format must not add another "EUR"
                // (owner, 2026-10-20: "ovo EUR EUR 5 deluje kao greska").
                String.format("Home fixture, %d of %d at an average %s (%.0f%% of the ground)",
                        p.totalAttendance, p.capacity, money(p.averageRealisedPrice),
                        p.capacity <= 0 ? 0 : 100.0 * p.totalAttendance / p.capacity));
    }

    private FinanceLedgerEntry merchandising(Team team, Integer seasonYear, Integer week, List<Player> squad) {
        double reputation = team.getReputation() == null ? 50 : team.getReputation();
        double value = MERCHANDISING_BASE * (reputation / 50.0)
                * Math.max(0.5, squad.size() / 18.0);
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.MERCHANDISING, value,
                squad.size() + " players, reputation " + Math.round(reputation));
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
    private FinanceLedgerEntry wages(Team team, Integer seasonYear, Integer week,
                                     List<Player> squad, double wageBill) {
        return FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.WAGES, -wageBill,
                squad.size() + " players under contract, most expensive "
                        + squad.stream().mapToDouble(p -> Math.max(0, p.getEarnings())).max().orElse(0));
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
    /**
     * What a named sponsor on the boards pays a club with no signed contract, in a fifth-tier club.
     *
     * <p>It used to be 70 a week, which is a fifth of {@code StaffSponsorService}'s smallest seeded
     * contract (18,000 a year) - chosen so the line would read on the Finance page "without moving
     * the economy". It did move the economy, in the wrong direction: a fifth-tier club was told its
     * name on the boards was worth **44.59 a week** (owner, 2026-10-20: "ovo je premalo nema veze,
     * sto je tier 5... bar pomnozi sa 500").
     *
     * <p>So: the old figure times **500** - a fifth-tier club's boards are worth 35,000 a week, not
     * 70 - and proportionally more the higher the tier, because the boards of a first-tier club are
     * worth more than the boards of a fifth-tier one: the tier is the market the sponsor is buying
     * into. (Owner, 2026-10-20: "ne treba petsto nego prethodnu vrednost POMNOZITI sa 500, gde
     * moze sponzor nedeljno 500 evra to je nista" - five hundred a week is nothing.)
     */
    private static final double BOARD_WEEKLY = 35_000.0;

    /**
     * How much the club's tier multiplies the boards money.
     *
     * <p>Tier 5 is the reference (the fifth tier pays the base), tier 1 pays five times as much, and
     * a club whose tier is not known - or below the fifth - keeps the base rather than being paid
     * less than the reference.
     *
     * @param tier the club's tier, or null when it has no competition
     * @return the multiplier
     */
    static double boardTierFactorForTier(Integer tier) {
        if (tier == null || tier >= 5) return 1.0;
        if (tier <= 1) return 5.0;
        return 6.0 - tier;
    }

    /**
     * The boards money a club with no signed contract is paid in a week.
     *
     * <p>Public arithmetic on purpose: this is the number the owner complained about, and it is worth
     * a test that can fail. The figure is the old one times 500, the tier scales it, and the
     * reputation still scales it - a fifth-tier club with a low reputation sees about 22,000 a week
     * where it used to see 44.59.
     *
     * @param tier the club's tier, or null
     * @param scale the reputation scale (0.45 to 1.80)
     * @return the weekly income
     */
    static double boardSponsorshipWeekly(Integer tier, double scale) {
        return BOARD_WEEKLY * boardTierFactorForTier(tier) * scale;
    }

    /** The club's tier, or null when it has no competition to read it from. */
    private static Integer tierOf(Team team) {
        try {
            return team.getCompetition() == null ? null : team.getCompetition().getTier();
        } catch (Exception ignored) {
            // A club without a competition (or a lazy proxy that cannot be read) simply gets the base.
            return null;
        }
    }

    private FinanceLedgerEntry sponsorship(Team team, Integer seasonYear, Integer week) {
        int season = seasonYear == null ? 1 : seasonYear;
        double contracted = staffSponsors.weeklySponsorshipIncome(team.getId(), season);
        String sponsor = sponsorName(team);
        if (contracted <= 0 && sponsor == null) return null;

        // Sponsors are seeded by division, so on their own they pay every club in a league the same.
        // What separates a club's shirt from another's is the club, so that is what the deal is
        // scaled by - and the contract value is still shown, because the manager signed it.
        double reputation = team.getReputation() == null ? 50 : team.getReputation();
        double scale = 0.45 + reputation / 100.0 * 1.35;
        String note;
        double income;
        if (contracted > 0) {
            income = contracted * scale;
            note = String.format("%s of contracted value, reputation %d", money(contracted),
                    Math.round(reputation));
        } else {
            // No signed contract, but a sponsor's name is painted on the boards and that is a deal too.
            //
            // <p>This line is new and it changes the economy: the `sponsor` table has no production
            // seeder, so it was empty, this method returned null, and <b>no club in the world had any
            // sponsorship income at all</b>. The Finance page had no sponsor line to name, which is what
            // the owner reported. Kept deliberately small - BOARD_WEEKLY is a fifth of the smallest
            // seeded contract - so it is a visible line rather than a rebalance, and it is named so the
            // manager can see who is paying it.
            income = boardSponsorshipWeekly(tierOf(team), scale);
            note = String.format("%s, the name on the boards, reputation %d", sponsor,
                    Math.round(reputation));
        }
        // The contract path did not name the sponsor either; the owner asked for the name on this line.
        return FinanceLedgerEntry.of(team, season, week, FinanceCategory.SPONSORSHIP, income,
                sponsor == null ? note : note + " - " + sponsor);
    }

    /**
     * The club's sponsor: the ground's named boards, or nothing.
     *
     * <p>One name, stored once. The {@code sponsor} table models contracts and the stadium holds the
     * name, and this prefers the stadium because that is where a manager sets it and where he sees it.
     */
    private String sponsorName(Team team) {
        Stadium ground = team == null ? null : team.getStadium();
        // A club with no ground has no boards, so it has no sponsor income. A club with a ground it has
        // not named a sponsor for carries the default one - the same rule the stadium page uses.
        return ground == null ? null : ground.effectiveSponsorName();
    }

    /**
     * Facility upkeep: the stadium, plus the pitch maintenance the club has actually funded.
     *
     * <p>Maintenance is charged from the maintenance programme rather than a flat rate, so a club
     * that stops paying for its pitch is spending less here — and is getting a worse pitch, which
     * is the trade the manager is making.
     */
    private List<FinanceLedgerEntry> facilityUpkeep(Team team, Integer seasonYear, Integer week) {
        Stadium s = team.getStadium();
        if (s == null) return List.of();
        int capacity = s.getCapacity() == null ? 0 : s.getCapacity();
        // What the club has built for training is billed on top of the ground it owns rather than
        // folded silently into the same number - a manager reading this line should be able to see
        // what his facilities are costing him.
        double trainingUpkeep = facilities.weeklyUpkeep(team);
        double upkeep = capacity * UPKEEP_PER_SEAT + trainingUpkeep;
        double maintenanceSpent = pitch.remainingOf(s);

        // **Both costs, or the one that applies.**
        //
        // This returned a single line, and the branch taken when the pitch came back better than it
        // went charged the pitch work and returned - so the club paid for its grass and not for its
        // stadium. The javadoc above claims the opposite ("a club that stops paying for its pitch is
        // spending less here"), which is what a club that never improves its pitch gets. Improving it
        // was the cheaper option.
        List<FinanceLedgerEntry> upkeepLines = new ArrayList<>();
        if (maintenanceSpent > 0) {
            PitchMaintenanceService.MaintenanceResult r =
                    pitch.applyWeeklyMaintenance(s, maintenanceSpent);
            if (r.restored() > 0) {
                upkeepLines.add(FinanceLedgerEntry.of(team, seasonYear, week,
                        FinanceCategory.PITCH_MAINTENANCE, -r.spent(),
                        "Pitch work: condition " + r.before() + " -> " + r.after()));
            }
        }
        String note = capacity + " seats + " + Math.round(trainingUpkeep) + " training facilities";
        upkeepLines.add(FinanceLedgerEntry.of(team, seasonYear, week, FinanceCategory.FACILITY_UPKEEP,
                -upkeep, note));
        return upkeepLines;
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
        List<CompetitionEntry> table = LeagueTableOrder.sort(played,
                tieBreaks.worldDraw(seasonYear == null ? 1 : seasonYear));
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

    /** How a line reads: "62% of a tier 1 weekly wage bill of EUR 18,478,000". */
    private String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    private String money(double amount) {
        return "EUR " + String.format("%,.0f", amount);
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
