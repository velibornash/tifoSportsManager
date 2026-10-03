package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.BoardExpectationService;
import org.example.footballmanager.newLogic.service.FinanceLedgerService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The club's real accounts.
 *
 * <p>Everything here is read from the ledger. The Finances page used to compute its own numbers in
 * the browser — inventing three sponsors, a monthly income of {@code budget * 0.055}, and six
 * months of history that had never happened. None of it was in the database, so a club's finances
 * looked identical whether it was solvent or bankrupt.
 *
 * <p>Looked up by team id throughout: two clubs may share a name.
 */
@RestController
@RequestMapping("/api/teams/{teamId}/finances")
@RequiredArgsConstructor
public class FinanceController {

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final FinanceLedgerEntryRepository ledger;
    private final FinanceLedgerService ledgerService;
    private final BoardExpectationService board;
    private final SeasonService seasonService;

    /** The full picture: balance, wage bill, budget health, and the ledger itself. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> summary(
            @PathVariable Long teamId,
            @RequestParam(required = false) Integer seasonYear) {
        Team team = teams.findById(teamId).orElse(null);
        if (team == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(ledgerService.summarise(team, seasonYear));
    }

    /** Ledger lines for one season, newest week first. */
    @GetMapping("/ledger")
    public ResponseEntity<List<Map<String, Object>>> entries(
            @PathVariable Long teamId,
            @RequestParam(required = false) Integer seasonYear) {

        Team team = teams.findById(teamId).orElse(null);
        if (team == null) return ResponseEntity.notFound().build();
        if (seasonYear == null) seasonYear = currentSeasonYear();

        List<Map<String, Object>> out = new ArrayList<>();
        List<FinanceLedgerEntry> entries =
                ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(teamId, seasonYear);
        for (int i = entries.size() - 1; i >= 0; i--) {
            FinanceLedgerEntry e = entries.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("week", e.getWeekNumber());
            row.put("seasonYear", e.getSeasonYear());
            row.put("category", e.getCategory() == null ? null : e.getCategory().name());
            row.put("label", e.getCategory() == null ? "" : e.getCategory().label());
            row.put("income", e.getCategory() != null && e.getCategory().isIncome());
            row.put("amount", e.getAmount());
            row.put("note", e.getNote());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** Week-by-week totals for the chart, from real ledger lines. */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> history(
            @PathVariable Long teamId,
            @RequestParam(required = false) Integer seasonYear) {

        Team team = teams.findById(teamId).orElse(null);
        if (team == null) return ResponseEntity.notFound().build();
        if (seasonYear == null) seasonYear = currentSeasonYear();
        return ResponseEntity.ok(ledgerService.weeklyTotals(team, seasonYear));
    }

    /**
     * The board's view of the club (Sprint 2.4).
     *
     * <p>Part of the finances payload because the FFP ratio and the trust it feeds are the same
     * decision: a manager needs to see why the board is unhappy at the same time as the wage bill
     * that made them unhappy.
     */
    @GetMapping("/board")
    public ResponseEntity<Map<String, Object>> board(@PathVariable Long teamId) {
        Team team = teams.findById(teamId).orElse(null);
        if (team == null) return ResponseEntity.notFound().build();

        org.example.footballmanager.newLogic.service.BoardExpectationService.BoardMood mood =
                board.evaluate(team, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("trust", mood.trust());
        out.put("headline", mood.headline());
        out.put("financialHealth", mood.health().name());
        out.put("standing", mood.standing().name());
        out.put("ffpRatio", mood.ffpRatio());
        out.put("weeklyWageBill", mood.weeklyWageBill());
        out.put("weeklyIncome", mood.weeklyIncome());
        out.put("concerns", mood.concerns());
        out.put("plaudits", mood.plaudits());
        out.put("sackingReview", mood.sackingReview());
        // Supporter mood beside board trust, deliberately. The two read from different things — the
        // board looks at the wage bill, the stand looks at how the club treats its players — and a
        // manager who can only see one of them is managing half of what is happening to his club
        // (P2-5).
        out.put("supporterMood", team.getSupporterMood());
        out.put("supporterExpectation", team.supporterExpectation().name());
        out.put("supporterExpectationLabel", team.supporterExpectation().label());
        return ResponseEntity.ok(out);
    }

    /**
     * The season a ledger entry belongs to.
     *
     * <p>This returned {@code java.time.Year.now().getValue()} - the real wall-clock year - while
     * every other season reader in the codebase asked the game clock. So a ledger row was filed
     * under whatever year the manager happened to be playing in, and reading it back asked the
     * world for its season. A season is twelve weeks and four of them run in a year, so the two
     * answers could not agree.
     */
    private Integer currentSeasonYear() {
        return seasonService.getActiveSeasonYear();
    }
}
