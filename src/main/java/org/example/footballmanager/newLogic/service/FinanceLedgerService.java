package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.ArrayList;
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

    public FinanceLedgerService(FinanceLedgerEntryRepository ledger, PlayerRepository players) {
        this.ledger = ledger;
        this.players = players;
    }

    /** Everything the Finances page needs, in one call. */
    @Transactional(readOnly = true)
    public Map<String, Object> summarise(Team team) {
        Integer season = activeSeason(team);
        List<FinanceLedgerEntry> entries = ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(
                team.getId(), season);

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

    private Integer activeSeason(Team team) {
        return Year.now().getValue();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
