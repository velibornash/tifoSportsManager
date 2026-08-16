package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Football-analyst style statistics harness.
 * Simulates N full matches and reports possession, shot quality (xG, distance buckets),
 * passing volume/accuracy by type, duels, set pieces, discipline and possession chains.
 * Ends with heuristic "unrealism flags" so tuning decisions are data-driven.
 */
public class FootballAnalystStatsTest {

    private record MatchStats(
        String home, String away, int homeGoals, int awayGoals,
        double homePoss, double awayPoss,
        int shots, int shotsOnTarget, int shotsBlocked, double xG, int goals,
        int passesAttempted, int passesCompleted, int interceptions,
        int shortPasses, int longPasses, int throughBalls, int crosses,
        int tacklesWon, int tacklesLost, int headersWon,
        int corners, int throwIns, int goalKicks, int freeKicks,
        int fouls, int yellows, int reds,
        int duels,
        int chains, double avgChainPasses, int longestChain,
        int events) {

        double shotAccuracy() { return shots == 0 ? 0 : 100.0 * shotsOnTarget / shots; }
    }

    @Test
    void analystReportAcrossMultipleMatches() {
        int N = 5;
        List<MatchStats> matches = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Home " + i, "Away " + i);
            MatchResult r = orchestrator.simulate(matchId);
            matches.add(analyze(r));
        }

        printReport(matches);
        printUnrealismFlags(matches);

        // Basic sanity: every match must have gone to completion
        for (MatchStats ms : matches) {
            assertTrue(ms.events() > 0);
            assertTrue(ms.passesAttempted() >= 10, "Too few pass attempts in " + ms.home());
        }
    }

    private MatchStats analyze(MatchResult r) {
        int passes = 0, completed = 0, interceptions = 0;
        int shortP = 0, longP = 0, through = 0, crosses = 0;
        int tacklesWon = 0, tacklesLost = 0, headersWon = 0;
        int corners = 0, throwIns = 0, goalKicks = 0, freeKicks = 0;
        int fouls = 0, yellows = 0, reds = 0, duels = 0;
        int blocks = 0, shots = 0, sot = 0;
        double xG = 0.0;

        for (MatchEvent e : r.events()) {
            switch (e) {
                case PassEvent p -> { passes++; if (p.completed()) completed++; }
                case PassInterceptedEvent ignored -> { passes++; interceptions++; }
                case PassIncompleteEvent ignored -> passes++;
                case LongBallEvent p -> longP++;
                case ThroughBallEvent p -> through++;
                case CrossEvent p -> crosses++;
                case CrossHeaderEvent p -> crosses++;
                case TackleEvent t -> { duels++; if (t.success()) tacklesWon++; else tacklesLost++; }
                case DuelEvent d -> duels++;
                case DribbleEvent d -> { duels++; tacklesWon++; }
                case DribbleLostEvent d -> { duels++; tacklesLost++; }
                case SetPieceEvent sp -> {
                    switch (sp.setPieceType()) {
                        case CORNER -> corners++;
                        case THROW_IN -> throwIns++;
                        case GOAL_KICK -> goalKicks++;
                        case FREE_KICK -> freeKicks++;
                    }
                }
                case FoulEvent f -> fouls++;
                case CardEvent c -> { if (c.cardType() == CardEvent.CardType.YELLOW) yellows++; else reds++; }
                case GoalEvent g -> { shots++; sot++; xG += g.xG(); }
                case ShotSavedEvent s -> { shots++; sot++; xG += s.xG(); }
                case ShotMissedEvent s -> { shots++; xG += s.xG(); }
                default -> { }
            }
        }

        // Possession chains from PossessionEndEvent
        List<PossessionEndEvent> chains = r.events().stream()
            .filter(e -> e instanceof PossessionEndEvent)
            .map(e -> (PossessionEndEvent) e)
            .toList();
        int longest = chains.stream().mapToInt(PossessionEndEvent::passCount).max().orElse(0);
        double avgChain = chains.isEmpty() ? 0 : chains.stream()
            .mapToInt(PossessionEndEvent::passCount).average().orElse(0);

        int goals = r.homeGoals() + r.awayGoals();
        return new MatchStats(
            "", "", r.homeGoals(), r.awayGoals(),
            r.homePossession(), r.awayPossession(),
            shots, sot, blocks, xG, goals,
            passes, completed, interceptions,
            shortP, longP, through, crosses,
            tacklesWon, tacklesLost, headersWon,
            corners, throwIns, goalKicks, freeKicks,
            fouls, yellows, reds,
            duels, chains.size(), avgChain, longest,
            r.events().size());
    }

    private void printReport(List<MatchStats> matches) {
        AtomicInteger i = new AtomicInteger(1);
        System.out.println("\n=== FOOTBALL ANALYST REPORT (" + matches.size() + " matches) ===");
        matches.forEach(ms -> {
            int idx = i.getAndIncrement();
            System.out.printf("Match %d:  %d-%d  Poss H=%.0f%% | Shots %d (%.0f%% OT) | xG %.2f | "
                    + "Passes %d/%d (%.0f%%) | Corners %d | Fouls %d | Chains %d (avg %.1f, long %d)%n",
                idx, ms.homeGoals(), ms.awayGoals(), ms.homePoss(),
                ms.shots(), ms.shotAccuracy(), ms.xG(),
                ms.passesCompleted(), ms.passesAttempted(),
                100.0 * ms.passesCompleted() / Math.max(1, ms.passesAttempted()),
                ms.corners(), ms.fouls(), ms.chains(), ms.avgChainPasses(), ms.longestChain());
        });

        int n = Math.max(1, matches.size());
        int shots = matches.stream().mapToInt(MatchStats::shots).sum();
        int sot = matches.stream().mapToInt(MatchStats::shotsOnTarget).sum();
        int goals = matches.stream().mapToInt(MatchStats::goals).sum();
        double xG = matches.stream().mapToDouble(MatchStats::xG).sum();
        int att = matches.stream().mapToInt(MatchStats::passesAttempted).sum();
        int comp = matches.stream().mapToInt(MatchStats::passesCompleted).sum();
        int fouls = matches.stream().mapToInt(MatchStats::fouls).sum();
        int corners = matches.stream().mapToInt(MatchStats::corners).sum();

        System.out.printf("%n--- AVERAGES ---%n");
        System.out.printf("Goals:        %.2f/match%n", goals / (double) n);
        System.out.printf("Shots:        %.1f/match (on-target %.1f%%, blocked %.1f%%)%n",
            shots / (double) n, 100.0 * sot / Math.max(1, shots),
            100.0 * matches.stream().mapToInt(MatchStats::shotsBlocked).sum() / Math.max(1, shots));
        System.out.printf("xG:           %.2f/match, conversion %.0f%%%n",
            xG / n, 100.0 * goals / Math.max(1.0, xG));
        System.out.printf("Passes:       %.0f attempted, %.0f%% completed%n",
            att / (double) n, 100.0 * comp / Math.max(1, att));
        System.out.printf("Corners:      %.1f | Fouls: %.1f | Cards: %.1f%n",
            corners / (double) n, fouls / (double) n,
            matches.stream().mapToInt(m -> m.yellows() + m.reds()).sum() / (double) n);
        System.out.printf("Duels:        %.1f | Chains: %.1f (avg %.1f passes, longest %d)%n",
            matches.stream().mapToInt(MatchStats::duels).sum() / (double) n,
            matches.stream().mapToInt(MatchStats::chains).sum() / (double) n,
            matches.stream().mapToDouble(MatchStats::avgChainPasses).sum() / n,
            matches.stream().mapToInt(MatchStats::longestChain).max().orElse(0));
    }

    private void printUnrealismFlags(List<MatchStats> matches) {
        System.out.println("\n--- UNREALISM FLAGS (football-analyst heuristics) ---");
        int flags = 0;

        double ot = matches.stream()
            .mapToInt(MatchStats::shotsOnTarget).sum() * 100.0
            / Math.max(1, matches.stream().mapToInt(MatchStats::shots).sum());
        if (ot > 60) { System.out.printf("  [!] Shots on target %.0f%% (realistic ~30-45%%)%n", ot); flags++; }

        double shotsPer = matches.stream().mapToInt(MatchStats::shots).sum() / (double) matches.size();
        if (shotsPer < 10) { System.out.printf("  [!] Only %.1f shots/match (realistic ~12-20)%n", shotsPer); flags++; }

        double passPer = matches.stream().mapToInt(MatchStats::passesAttempted).sum() / (double) matches.size();
        if (passPer < 400) { System.out.printf("  [!] Only %.0f passes/match (realistic 600-1000)%n", passPer); flags++; }

        double compRate = matches.stream().mapToInt(MatchStats::passesCompleted).sum() * 100.0
            / Math.max(1, matches.stream().mapToInt(MatchStats::passesAttempted).sum());
        if (compRate > 90) { System.out.printf("  [!] Pass completion %.0f%% too high (realistic ~80-87%%)%n", compRate); flags++; }
        if (compRate < 65) { System.out.printf("  [!] Pass completion %.0f%% too low%n", compRate); flags++; }

        double foulsPer = matches.stream().mapToInt(MatchStats::fouls).sum() / (double) matches.size();
        if (foulsPer < 10) { System.out.printf("  [!] Only %.1f fouls/match (realistic ~15-25)%n", foulsPer); flags++; }

        double goalsPer = matches.stream().mapToInt(MatchStats::goals).sum() / (double) matches.size();
        if (goalsPer > 4.0) { System.out.printf("  [!] %.1f goals/match too high%n", goalsPer); flags++; }

        double avgPoss = matches.stream().mapToDouble(MatchStats::homePoss).average().orElse(50);
        if (avgPoss > 65) { System.out.printf("  [!] Average home possession %.0f%% — home-side bias%n", avgPoss); flags++; }

        if (flags == 0) {
            System.out.println("  No realism flags raised — values within football-analyst ranges.");
        } else {
            System.out.printf("  %d flag(s) raised.%n", flags);
        }
    }
}
