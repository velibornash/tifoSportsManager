package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a club's ledger and turns it into something a manager can act on (Sprint 2.2).
 *
 * <p>This is the read side of {@link WeeklyFinanceService}. It exists because the Finances page was
 * inventing its own numbers — three fictional sponsors, a monthly income of {@code budget * 0.055},
 * and six months of history that had never been played. The only honest version of that page reads
 * the ledger, which means it also has to be honest about the periods where the ledger is empty.
 */
@Service
public class FinanceLedgerService {

    private final FinanceLedgerEntryRepository ledger;
    private final PlayerRepository players;

    private final GameClockRepository clocks;

    public FinanceLedgerService(FinanceLedgerEntryRepository ledger, PlayerRepository players,
                                GameClockRepository clocks) {
        this.clocks = clocks;
        this.ledger = ledger;
        this.players = players;
    }

    /** Every season this club has a ledger for, newest first. */
    @Transactional(readOnly = true)
    public List<Integer> seasonsWithLedger(Long teamId) {
        return ledger.findDistinctSeasonsByTeamIdOrderBySeasonYearDesc(teamId);
    }

    /** Everything the Finances page needs, in one call. */
    @Transactional(readOnly = true)
    public Map<String, Object> summarise(Team team) {
        return summarise(team, activeSeason(team));
    }

    /** Summary for a specific season, so the page can show a whole season rather than only this one. */
    @Transactional(readOnly = true)
    public Map<String, Object> summarise(Team team, Integer season) {
        if (season == null) season = activeSeason(team);
        // No clock means no season, and a season that does not exist has nothing settled in it. This
        // used to fall back to Year.now().getValue() -- season 2026 -- so a clockless world read its
        // ledger for a year that was never played, income that had really been earned came back zero,
        // and TransferBudgetService refused the club for having no income. There is no calendar year
        // anywhere in this game: seasons are counted from 1. No clock is not season 2026; it is no
        // season at all, and the honest answer is a refusal.
        List<FinanceLedgerEntry> entries = season == null ? List.of()
                : ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(team.getId(), season);

        double income = 0;
        double wages = 0;
        Map<String, Double> byCategory = new LinkedHashMap<>();
        for (FinanceCategory c : FinanceCategory.values()) byCategory.put(c.name(), 0.0);

        for (FinanceLedgerEntry e : entries) {
            if (e.getAmount() == null || e.getCategory() == null) continue;
            double amt = e.getAmount();
            if (e.getCategory().isIncome()) {
                income += amt;
                byCategory.merge(e.getCategory().name(), amt, Double::sum);
            } else {
                wages += -amt;
                byCategory.merge(e.getCategory().name(), amt, Double::sum);
            }
        }

        List<Player> squad = players.findByTeamId(team.getId());
        double squadWageBill = squad.stream().mapToDouble(p -> Math.max(0, p.getEarnings())).sum();
        double squadValue = squad.stream().mapToDouble(p -> p.getPlayerValue()).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", team.getId());
        out.put("teamName", team.getName());
        out.put("seasonYear", season);
        out.put("budget", team.getBudget() == null ? 0 : team.getBudget());
        out.put("squadSize", squad.size());
        out.put("squadValue", round2(squadValue));
        out.put("squadWageBill", round2(squadWageBill));
        out.put("incomeToDate", round2(income));
        out.put("costsToDate", round2(-wages));
        out.put("netToDate", round2(income - wages));
        out.put("ledgerLines", entries.size());
        out.put("byCategory", byCategory);
        out.put("settled", !entries.isEmpty());
        out.put("seasons", seasonsWithLedger(team.getId()));
        out.put("categories", categoryLabels());

        // A club with no ledger yet must say so rather than render zeroes that look like a
        // genuinely broke club. This is the difference the fiction could not express.
        if (entries.isEmpty()) {
            out.put("notice", "No settled weeks yet for this season. The ledger fills in as weeks "
                    + "are played — nothing here is estimated.");
        }
        return out;
    }

    /** Week-by-week income and cost totals, oldest first, for the chart. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> weeklyTotals(Team team, Integer seasonYear) {
        if (seasonYear == null) seasonYear = activeSeason(team);
        List<FinanceLedgerEntry> entries = ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(
                team.getId(), seasonYear);

        Map<Integer, double[]> byWeek = new TreeMap<>();
        for (FinanceLedgerEntry e : entries) {
            if (e.getWeekNumber() == null || e.getAmount() == null || e.getCategory() == null) continue;
            double[] slot = byWeek.computeIfAbsent(e.getWeekNumber(), k -> new double[2]);
            if (e.getCategory().isIncome()) slot[0] += e.getAmount();
            else slot[1] += -e.getAmount();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        double running = 0;
        for (Map.Entry<Integer, double[]> w : byWeek.entrySet()) {
            double inc = w.getValue()[0];
            double cost = w.getValue()[1];
            running += inc - cost;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", w.getKey());
            row.put("income", round2(inc));
            row.put("expenses", round2(cost));
            row.put("net", round2(inc - cost));
            row.put("balance", round2(running));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, String>> categoryLabels() {
        List<Map<String, String>> out = new ArrayList<>();
        for (FinanceCategory c : FinanceCategory.values()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("key", c.name());
            m.put("label", c.label());
            m.put("income", String.valueOf(c.isIncome()));
            out.add(m);
        }
        return out;
    }

    /**
     * Which season the ledger is being read for.
     *
     * <p>This used to be {@code Year.now().getValue()} - the wall-clock year. The settlement writes
     * a <i>game</i> season, so the two never matched: income came back as zero, every club was told
     * it had no settled income, and therefore no transfer budget, and the board had no ledger to form
     * an opinion from. A manager's season is twelve real weeks, so the calendar year is not a season
     * at all.
     *
     * <p>Read from the clock repository rather than through SeasonService, which depends on the
     * weekly settlement that writes these lines - the same cycle the window and contract services hit.
     */
    private Integer activeSeason(Team team) {
        return clocks.findAll().stream()
                .filter(c -> c.getCurrentSeason() != null)
                // The furthest-advanced clock, and the lowest id between equals. findFirst() over an
                // unordered table returned an arbitrary season, so two runs against the same data could
                // disagree about which season the game was in -- and every figure below is a read of
                // that season.
                .max(Comparator.<org.example.footballmanager.newLogic.model.GameClock, Integer>comparing(
                        org.example.footballmanager.newLogic.model.GameClock::getCurrentSeason)
                        .thenComparing(org.example.footballmanager.newLogic.model.GameClock::getId,
                                Comparator.reverseOrder()))
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentSeason)
                .orElse(null);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
