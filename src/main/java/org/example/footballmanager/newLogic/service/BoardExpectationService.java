package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the board expects, and whether the manager is meeting it (Sprint 2.4).
 *
 * <p>A manager game needs a manager. Without a board there is no consequence for a bad season, no
 * tension in a transfer window, and no reason to care about the wage bill beyond that it is a
 * number on a screen.
 *
 * <h2>Financial health, not just the league table</h2>
 * The headline rule is <b>FFP-lite: weekly wage bill ÷ weekly income</b>. A club spending more than
 * it earns is not a club, and the ratio is shown to the player rather than applied behind their
 * back — a trust penalty the manager cannot see is a gotcha, not a rule.
 *
 * <p>Thresholds are deliberately forgiving. A ratio of exactly 1.0 is sustainable; 1.15 is
 * uncomfortable; 1.35 is a board meeting. Below 0.9 the club is a model of prudence and the board
 * notices that too — a manager who never spends anything is also not winning anything.
 */
@Service
public class BoardExpectationService {

    /** Wage bill above this multiple of weekly income and the board starts to worry. */
    public static final double FFP_COMFORTABLE = 0.90;
    public static final double FFP_STRAINED = 1.15;
    public static final double FFP_CRITICAL = 1.35;

    /** Trust is 0-100. A sacking review starts below this. */
    public static final double TRUST_SACKING_REVIEW = 20.0;

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final CompetitionEntryRepository entries;
    private final FinanceLedgerService ledgerService;

    public BoardExpectationService(TeamRepository teams,
                                   PlayerRepository players,
                                   CompetitionEntryRepository entries,
                                   FinanceLedgerService ledgerService) {
        this.teams = teams;
        this.players = players;
        this.entries = entries;
        this.ledgerService = ledgerService;
    }

    /**
     * Evaluates a club and returns the board's mood, without storing anything.
     *
     * <p>Read-only by design: the board's opinion is derived from facts, so it can be asked as often
     * as the UI likes and can never drift out of sync with the data.
     */
    @Transactional(readOnly = true)
    public BoardMood evaluate(Team team, SeasonCompetition sc) {
        if (team == null) return BoardMood.unknown();

        List<Player> squad = players.findByTeamId(team.getId());
        double wageBill = squad.stream().mapToDouble(p -> Math.max(0, p.getEarnings())).sum();

        Map<String, Object> finances = ledgerService.summarise(team);
        double incomeToDate = asDouble(finances.get("incomeToDate"));
        double costsToDate = asDouble(finances.get("costsToDate"));

        // Average weekly income across the weeks actually settled. With no settled weeks the ratio
        // is unknown rather than infinite — an unplayed season is not an insolvent club.
        int weeksSettled = countWeeks(finances);
        double weeklyIncome = weeksSettled > 0 ? incomeToDate / weeksSettled : 0;
        double weeklyCost = weeksSettled > 0 ? costsToDate / weeksSettled : 0;
        Double ffp = weeklyIncome > 0 ? wageBill / weeklyIncome : null;

        List<String> concerns = new ArrayList<>();
        List<String> plaudits = new ArrayList<>();

        // --- money ---
        FinancialHealth health = financialHealth(ffp, team);
        if (health == FinancialHealth.CRITICAL) {
            concerns.add("The wage bill is " + fmt(ffp == null ? 0 : ffp)
                    + " times weekly income. The board will not fund that.");
        } else if (health == FinancialHealth.STRAINED) {
            concerns.add("Wages are running close to income. Trim the squad or find revenue.");
        } else if (health == FinancialHealth.PRUDENT && weeksSettled >= 4) {
            plaudits.add("The club is comfortably in the black.");
        }

        double budget = team.getBudget() == null ? 0 : team.getBudget();
        if (budget < 0) {
            concerns.add("The club is overdrawn.");
        } else if (health == FinancialHealth.HEALTHY && weeksSettled >= 4) {
            plaudits.add("Finances are under control.");
        }

        // --- players unhappy on their terms ---
        long unhappy = squad.stream().filter(this::underpaid).count();
        if (unhappy > 0) {
            concerns.add(unhappy + " player" + (unhappy == 1 ? "" : "s")
                    + " are paid well below what they are worth and are unhappy about it.");
        }

        // --- sporting ---
        PositionStanding standing = standingOf(team, sc);
        switch (standing) {
            case TITLE_RACE -> plaudits.add("The board expects a title challenge.");
            case UPPER_MID -> plaudits.add("The board is satisfied with progress.");
            case RELEGATION_DANGER ->
                    concerns.add("The club is in the relegation zone. The board is watching closely.");
            case BOTTOM -> concerns.add("The club is bottom of the table. Expect difficult questions.");
            case UNKNOWN -> { }
        }

        double trust = trustScore(health, standing, unhappy, squad.size());
        return new BoardMood(team.getId(), trust, health, standing, ffp, round2(wageBill),
                round2(weeklyIncome), weeksSettled, concerns, plaudits,
                trust < TRUST_SACKING_REVIEW);
    }

    /** Health bands, named so the UI never has to interpret a raw ratio. */
    public enum FinancialHealth {
        /** No income recorded yet — an unplayed season, not a broke one. */
        UNKNOWN,
        /** Never spending anything. Sustainable, and also not winning anything. */
        PRUDENT,
        HEALTHY,
        STRAINED,
        CRITICAL
    }

    public enum PositionStanding {
        UNKNOWN, TITLE_RACE, UPPER_MID, RELEGATION_DANGER, BOTTOM
    }

    private FinancialHealth financialHealth(Double ffp, Team team) {
        if (ffp == null) return FinancialHealth.UNKNOWN;
        if (ffp < FFP_COMFORTABLE * 0.6) return FinancialHealth.PRUDENT;
        if (ffp <= FFP_COMFORTABLE) return FinancialHealth.PRUDENT;
        if (ffp <= FFP_STRAINED) return FinancialHealth.HEALTHY;
        if (ffp <= FFP_CRITICAL) return FinancialHealth.STRAINED;
        return FinancialHealth.CRITICAL;
    }

    /**
     * Trust 0-100.
     *
     * <p>Starts neutral at 60 and is moved by the three things a board actually reacts to: whether
     * the club is solvent, where it is in the table, and whether the players are happy.
     */
    private double trustScore(FinancialHealth health, PositionStanding standing,
                              long unhappy, int squadSize) {
        double trust = 60;
        trust += switch (health) {
            case UNKNOWN -> 0;
            case PRUDENT -> -4;      // safe, but no ambition on show
            case HEALTHY -> 8;
            case STRAINED -> -12;
            case CRITICAL -> -30;
        };
        trust += switch (standing) {
            case UNKNOWN -> 0;
            case TITLE_RACE -> 18;
            case UPPER_MID -> 8;
            case RELEGATION_DANGER -> -16;
            case BOTTOM -> -28;
        };
        if (squadSize > 0 && unhappy > 0) {
            trust -= Math.min(20, unhappy * 4.0 / squadSize * 20);
        }
        return Math.max(0, Math.min(100, trust));
    }

    /**
     * A player is underpaid when their wage is far below their market value.
     *
     * <p>Feeds S2.6's morale: a player on half what he is worth is not going to be a leader in the
     * dressing room, however good he is on the pitch.
     */
    private boolean underpaid(Player p) {
        double value = p.getPlayerValue();
        double wage = Math.max(0, p.getEarnings());
        if (value <= 0) return false;
        // Weekly wage against a total market value is not like-for-like, so compare the annualised
        // wage against a conservative share of value.
        return wage * 52.0 < value * 0.010;
    }

    private PositionStanding standingOf(Team team, SeasonCompetition sc) {
        if (sc == null) return PositionStanding.UNKNOWN;
        CompetitionEntry e = entries.findBySeasonCompetitionAndTeam(sc, team).orElse(null);
        if (e == null || e.getPosition() == null || e.getPosition() <= 0) return PositionStanding.UNKNOWN;

        int pos = e.getPosition();
        int size = Math.max(10, countEntries(sc));
        if (pos == 1) return PositionStanding.TITLE_RACE;
        if (pos <= Math.max(2, size / 3)) return PositionStanding.UPPER_MID;
        if (pos >= size - 2) return PositionStanding.BOTTOM;
        if (pos >= size - 4) return PositionStanding.RELEGATION_DANGER;
        return PositionStanding.UPPER_MID;
    }

    private int countEntries(SeasonCompetition sc) {
        try {
            return entries.findBySeasonCompetition(sc).size();
        } catch (RuntimeException e) {
            return 16;
        }
    }

    private int countWeeks(Map<String, Object> finances) {
        Object lines = finances.get("ledgerLines");
        // Roughly one settled week per 3-6 lines; only used to average, so an estimate is fine.
        int n = lines instanceof Number x ? x.intValue() : 0;
        return n / 4;
    }

    private double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** The board's assessment of a club. */
    public record BoardMood(Long teamId, double trust, FinancialHealth health,
                            PositionStanding standing, Double ffpRatio,
                            double weeklyWageBill, double weeklyIncome, int weeksSettled,
                            List<String> concerns, List<String> plaudits,
                            boolean sackingReview) {

        public static BoardMood unknown() {
            return new BoardMood(null, 60, FinancialHealth.UNKNOWN, PositionStanding.UNKNOWN,
                    null, 0, 0, 0, List.of(), List.of(), false);
        }

        public String headline() {
            if (sackingReview) return "The board is considering a change of manager.";
            if (trust >= 80) return "The board is fully behind you.";
            if (trust >= 65) return "The board is satisfied.";
            if (trust >= 45) return "The board is watching.";
            if (trust >= TRUST_SACKING_REVIEW) return "The board is concerned.";
            return "The board is very concerned.";
        }
    }
}
