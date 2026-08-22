package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Action;
import org.example.footballmanager.demo.service.model.PassHeight;
import org.example.footballmanager.demo.service.result.*;
import org.example.footballmanager.demo.service.result.LogEntry;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs N matches and outputs comprehensive football analytics.
 * Parses action logs for detailed breakdown of crosses, centers, free kicks,
 * VAR, air vs ground passes, shot types, etc.
 */
public class ComprehensiveBatchRunner {

    public static void main(String[] args) {
        int numMatches = 100;
        if (args.length > 0) {
            try { numMatches = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        // ── Basic aggregates ──
        int totalHomeGoals = 0, totalAwayGoals = 0;
        int totalShots = 0, totalShotsOnTarget = 0;
        int totalPassesAttempted = 0, totalPassesCompleted = 0;
        int totalFouls = 0, totalYellowCards = 0, totalRedCards = 0;
        int totalCorners = 0, totalOffsides = 0, totalPenalties = 0;
        int totalThrowIns = 0, totalGoalKicks = 0;
        double totalHomePossession = 0;

        // ── Detailed from logs ──
        int thruAttempts = 0, thruCompleted = 0;
        int crosses = 0, crossesCompleted = 0;
        int centers = 0, centersCompleted = 0;
        int airPasses = 0, airPassCompleted = 0;
        int groundPasses = 0, groundPassCompleted = 0;
        int freeKicksTotal = 0;
        int varReviews = 0, varOffsideConfirmed = 0, varOffsideOverturned = 0;
        int varGoalConfirmed = 0, varGoalOverturned = 0;
        int varRedConfirmed = 0, varRedOverturned = 0;
        int varPenaltyConfirmed = 0, varPenaltyOverturned = 0;
        int interceptions = 0, looseBallCount = 0;
        int goalsFromCross = 0, goalsFromCenter = 0;
        int shotsOnTargetFromCross = 0, shotsOnTargetFromCenter = 0;
        int shotsTotalFromCross = 0, shotsTotalFromCenter = 0;
        int cornersFromCross = 0, cornersFromCenter = 0, cornersFromShot = 0, cornersFromPass = 0;
        int cornersFromSave = 0, cornersFromDeflection = 0;
        int throwInsFromPass = 0, throwInsFromCross = 0;
        int totalActions = 0;
        int duelsTotal = 0;
        int clearances = 0;
        int shotsFromThru = 0;
        int goalsFromThru = 0;
        int goalsFromDirectShot = 0;
        int saves = 0;

        List<String> scorelines = new ArrayList<>();
        int zeroZeroDraws = 0, bttsCount = 0;

        System.out.println("=== COMPREHENSIVE BATCH: " + numMatches + " MATCHES ===\n");

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numMatches; i++) {
            long seed = 1000 + i * 7L;
            MatchSimulator sim = new MatchSimulator(seed);
            long skillSeed = seed;
            var homePlayers = MatchSimulationController.generateTeam("HOME", "Home", skillSeed);
            var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Away", skillSeed);

            MatchResult result = sim.simulate(homePlayers, awayPlayers, "Home", "Away");

            int hg = result.homeGoals(), ag = result.awayGoals();
            totalHomeGoals += hg;
            totalAwayGoals += ag;
            totalShots += result.homeStats().shots() + result.awayStats().shots();
            totalShotsOnTarget += result.homeStats().shotsOnTarget() + result.awayStats().shotsOnTarget();
            totalPassesAttempted += result.homeStats().passesAttempted() + result.awayStats().passesAttempted();
            totalPassesCompleted += result.homeStats().passesCompleted() + result.awayStats().passesCompleted();
            totalFouls += result.homeStats().fouls() + result.awayStats().fouls();
            totalYellowCards += result.homeStats().yellowCards() + result.awayStats().yellowCards();
            totalRedCards += result.homeStats().redCards() + result.awayStats().redCards();
            totalCorners += result.homeStats().corners() + result.awayStats().corners();
            totalOffsides += result.homeStats().offsides() + result.awayStats().offsides();
            totalPenalties += result.homeStats().penalties() + result.awayStats().penalties();
            totalThrowIns += result.homeStats().getThrowInCount() + result.awayStats().getThrowInCount();
            totalGoalKicks += result.homeStats().getGoalKickCount() + result.awayStats().getGoalKickCount();
            totalHomePossession += result.homeStats().possessionPercent();
            thruAttempts += result.homeStats().getThruAttempts() + result.awayStats().getThruAttempts();
            thruCompleted += result.homeStats().getThruCompleted() + result.awayStats().getThruCompleted();
            interceptions += result.homeStats().getInterceptionCount() + result.awayStats().getInterceptionCount();
            looseBallCount += result.homeStats().getLooseBallCount() + result.awayStats().getLooseBallCount();

            // Parse logs for detailed breakdown
            Action lastCrossAction = null;
            Action lastCenterAction = null;
            Action lastThruAction = null;

            for (LogEntry entry : result.logs()) {
                String desc = entry.getDescription();
                String ch = entry.getChannel();
                Object ctx = entry.getContext();

                // ── ACTION entries (what just started) ──
                if (desc.startsWith("ACTION:")) {
                    totalActions++;

                    // Cross
                    if (desc.contains("ACTION: CROSS")) {
                        crosses++;
                        if (ctx instanceof Action a) lastCrossAction = a;
                    }
                    // Center
                    if (desc.contains("ACTION: CENTER")) {
                        centers++;
                        if (ctx instanceof Action a) lastCenterAction = a;
                    }
                    // Pass — air vs ground
                    if (desc.startsWith("ACTION: PASS") || desc.contains("ACTION: CLEAR")) {
                        boolean isAir = false;
                        if (ctx instanceof Action a && a.getPassHeight() == PassHeight.AIR) {
                            isAir = true;
                        }
                        if (desc.startsWith("ACTION: PASS")) {
                            if (isAir) airPasses++; else groundPasses++;
                        }
                    }
                    // THRU
                    if (desc.contains("ACTION: PASS") && desc.contains("THRU")) {
                        if (ctx instanceof Action a) lastThruAction = a;
                    }
                    // Shot
                    if (desc.contains("ACTION: SHOT")) {
                        // track preceding action type
                    }
                    // Clearance
                    if (desc.contains("ACTION: CLEAR")) {
                        clearances++;
                    }
                }

                // ── OUTCOME entries ──
                if (desc.startsWith("OUTCOME:")) {
                    // Cross outcomes
                    if (desc.contains("OUTCOME: CROSS")) {
                        if (desc.contains("RECEIVED") || desc.contains("PASS_RECEIVED")) {
                            crossesCompleted++;
                        }
                    }
                    // Center outcomes
                    if (desc.contains("OUTCOME: CENTER")) {
                        if (desc.contains("RECEIVED") || desc.contains("PASS_RECEIVED")) {
                            centersCompleted++;
                        }
                    }
                    // Pass outcomes (air vs ground)
                    if (desc.contains("OUTCOME: PASS") && !desc.contains("OUTCOME: THRU")) {
                        boolean isAir = false;
                        if (ctx instanceof Action a && a.getPassHeight() == PassHeight.AIR) {
                            isAir = true;
                        }
                        if (desc.contains("PASS_RECEIVED") || desc.contains("RECEIVED")) {
                            if (isAir) airPassCompleted++; else groundPassCompleted++;
                        }
                    }
                    // THRU pass outcomes
                    if (desc.contains("OUTCOME: PASS") && desc.contains("THRU")) {
                        // counted via stats
                    }
                    // Goal
                    if (desc.contains("GOAL") || desc.contains("goal")) {
                        // Goal from cross/center/thru
                        if (lastCrossAction != null) goalsFromCross++;
                        else if (lastCenterAction != null) goalsFromCenter++;
                        else if (lastThruAction != null) goalsFromThru++;
                    }
                    // Save
                    if (desc.contains("SAVE")) {
                        saves++;
                        if (lastCrossAction != null) shotsOnTargetFromCross++;
                        if (lastCenterAction != null) shotsOnTargetFromCenter++;
                    }
                    // Shot on target
                    if (desc.contains("OUTCOME: SHOT")) {
                        if (desc.contains("onTarget=true") || desc.contains("GOAL") || desc.contains("SAVE")) {
                            if (lastCrossAction != null) shotsOnTargetFromCross++;
                            if (lastCenterAction != null) shotsOnTargetFromCenter++;
                        }
                    }
                    // Interception
                    if (desc.contains("INTERCEPTED")) {
                        // counted via stats
                    }
                }

                // ── VAR ──
                if (ch.equals("VAR") || desc.contains("VAR ") || desc.contains("VAR(")) {
                    varReviews++;
                    if (desc.contains("OVERTURNED offside") || desc.contains("OFFSIDE_OVERTURNED")) varOffsideOverturned++;
                    else if (desc.contains("OFFSIDE")) varOffsideConfirmed++;
                    if (desc.contains("OVERTURNED GOAL") || desc.contains("GOAL_OVERTURNED")) varGoalOverturned++;
                    else if (desc.contains("GOAL") && ch.equals("VAR")) varGoalConfirmed++;
                    if (desc.contains("OVERTURNED red") || desc.contains("RED_OVERTURNED")) varRedOverturned++;
                    else if (desc.contains("RED")) varRedConfirmed++;
                    if (desc.contains("OVERTURNED penalty") || desc.contains("PENALTY_OVERTURNED")) varPenaltyOverturned++;
                    else if (desc.contains("PENALTY")) varPenaltyConfirmed++;
                }

                // ── Free kick restarts ──
                if (ch.equals("FREE_KICK")) {
                    freeKicksTotal++;
                }

                // ── DUEL ──
                if (desc.startsWith("DUEL")) {
                    duelsTotal++;
                }

                // Reset preceding action tracker at each new ACTION
                if (desc.startsWith("ACTION:")) {
                    if (desc.contains("ACTION: CROSS")) {
                        lastCenterAction = null;
                        lastThruAction = null;
                    } else if (desc.contains("ACTION: CENTER")) {
                        lastCrossAction = null;
                        lastThruAction = null;
                    } else if (desc.contains("ACTION: PASS") && desc.contains("THRU")) {
                        lastCrossAction = null;
                        lastCenterAction = null;
                    } else if (desc.contains("ACTION: SHOT")) {
                        lastCrossAction = null;
                        lastCenterAction = null;
                        lastThruAction = null;
                    } else {
                        lastCrossAction = null;
                        lastCenterAction = null;
                        lastThruAction = null;
                    }
                }
            }

            String scoreline = hg + "-" + ag;
            scorelines.add(scoreline);
            if (hg == 0 && ag == 0) zeroZeroDraws++;
            if (hg > 0 && ag > 0) bttsCount++;

            if ((i + 1) % 10 == 0 || i == numMatches - 1) {
                System.out.printf("  Completed %d/%d matches (running avg: %.1f goals/match, %.1f shots/match)%n",
                        i + 1, numMatches,
                        (totalHomeGoals + totalAwayGoals) / (double) (i + 1),
                        totalShots / (double) (i + 1));
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("%nCompleted in %d seconds%n%n", elapsed / 1000);

        final int totalMatches = numMatches;
        double N = totalMatches;

        // ══════════════════════════════════════════════════════════════
        // COMPREHENSIVE REPORT
        // ══════════════════════════════════════════════════════════════
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf( "║  COMPREHENSIVE ANALYSIS — %d MATCHES                         ║%n", numMatches);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // ── 1. GOALS ──
        System.out.println("\n═══ 1. GOALS ═══");
        int totalGoals = totalHomeGoals + totalAwayGoals;
        System.out.printf("  Total:        %d (%.1f per match)%n", totalGoals, totalGoals / N);
        System.out.printf("  Home:         %d (%.1f/match)%n", totalHomeGoals, totalHomeGoals / N);
        System.out.printf("  Away:         %d (%.1f/match)%n", totalAwayGoals, totalAwayGoals / N);
        System.out.printf("  0-0 draws:    %d (%.0f%%)%n", zeroZeroDraws, 100.0 * zeroZeroDraws / totalMatches);
        System.out.printf("  BTTS:         %d (%.0f%% — both teams score)%n", bttsCount, 100.0 * bttsCount / totalMatches);

        // ── 2. SHOTS ──
        System.out.println("\n═══ 2. SHOTS ═══");
        System.out.printf("  Total shots:      %d (%.1f per match)%n", totalShots, totalShots / N);
        System.out.printf("  Shots on target:  %d (%.0f%%)%n", totalShotsOnTarget,
                totalShots > 0 ? 100.0 * totalShotsOnTarget / totalShots : 0);
        System.out.printf("  Conversion rate:  %.1f%% (goals/shots)%n",
                totalShots > 0 ? 100.0 * totalGoals / totalShots : 0);
        System.out.printf("  Shots per goal:   %.1f%n",
                totalGoals > 0 ? (double) totalShots / totalGoals : 0);
        System.out.printf("  Saves:            %d (%.0f%% save rate)%n", saves,
                totalShotsOnTarget > 0 ? 100.0 * saves / totalShotsOnTarget : 0);

        // ── 3. PASSING ──
        System.out.println("\n═══ 3. PASSING ═══");
        int totalPassFailures = totalPassesAttempted - totalPassesCompleted;
        System.out.printf("  Total passes:     %d/%d (%.0f%% accuracy)%n",
                totalPassesCompleted, totalPassesAttempted,
                totalPassesAttempted > 0 ? 100.0 * totalPassesCompleted / totalPassesAttempted : 0);
        System.out.printf("  Passes per match: %.0f%n", totalPassesAttempted / N);
        System.out.printf("  Air passes:       %d/%d (%.0f%% accuracy)%n",
                airPassCompleted, airPasses,
                airPasses > 0 ? 100.0 * airPassCompleted / airPasses : 0);
        System.out.printf("  Ground passes:    %d/%d (%.0f%% accuracy)%n",
                groundPassCompleted, groundPasses,
                groundPasses > 0 ? 100.0 * groundPassCompleted / groundPasses : 0);
        System.out.printf("  Air pass ratio:   %.0f%%%n",
                (airPasses + groundPasses) > 0 ? 100.0 * airPasses / (airPasses + groundPasses) : 0);
        System.out.printf("  THRU passes:      %d/%d (%.0f%% success)%n",
                thruCompleted, thruAttempts,
                thruAttempts > 0 ? 100.0 * thruCompleted / thruAttempts : 0);
        System.out.printf("  Clearances:       %d (%.1f per match)%n", clearances, clearances / N);

        // ── 4. CROSSING & CENTERING ──
        System.out.println("\n═══ 4. WIDE PLAY (Cross/Center) ═══");
        System.out.printf("  Crosses:      %d total (%.1f/match) — completed: %d (%.0f%%)%n",
                crosses, crosses / N, crossesCompleted,
                crosses > 0 ? 100.0 * crossesCompleted / crosses : 0);
        System.out.printf("  Centers:      %d total (%.1f/match) — completed: %d (%.0f%%)%n",
                centers, centers / N, centersCompleted,
                centers > 0 ? 100.0 * centersCompleted / centers : 0);
        int widePlayTotal = crosses + centers;
        System.out.printf("  Wide play total: %d (%.1f/match)%n", widePlayTotal, widePlayTotal / N);
        System.out.printf("  Wide completions: %d (%.0f%%)%n",
                crossesCompleted + centersCompleted,
                widePlayTotal > 0 ? 100.0 * (crossesCompleted + centersCompleted) / widePlayTotal : 0);

        // ── 5. SET PIECES ──
        System.out.println("\n═══ 5. SET PIECES ═══");
        System.out.printf("  Corners:      %d (%.1f per match)%n", totalCorners, totalCorners / N);
        System.out.printf("  Throw-ins:    %d (%.1f per match)%n", totalThrowIns, totalThrowIns / N);
        System.out.printf("  Goal kicks:   %d (%.1f per match)%n", totalGoalKicks, totalGoalKicks / N);
        System.out.printf("  Free kicks:   %d (%.1f per match)%n", freeKicksTotal, freeKicksTotal / N);
        System.out.printf("  Penalties:    %d (%.1f per match)%n", totalPenalties, totalPenalties / N);

        // ── 6. DISCIPLINE ──
        System.out.println("\n═══ 6. DISCIPLINE ═══");
        System.out.printf("  Fouls:         %d (%.1f per match)%n", totalFouls, totalFouls / N);
        System.out.printf("  Yellow cards:  %d (%.1f per match)%n", totalYellowCards, totalYellowCards / N);
        System.out.printf("  Red cards:     %d (%.2f per match)%n", totalRedCards, totalRedCards / N);
        System.out.printf("  Fouls per yellow: %.1f%n", totalYellowCards > 0 ? (double) totalFouls / totalYellowCards : 0);

        // ── 7. POSSESSION ──
        System.out.println("\n═══ 7. POSSESSION ═══");
        System.out.printf("  Avg home possession: %.0f%%%n", totalHomePossession / N);

        // ── 8. OFFSIDES ──
        System.out.println("\n═══ 8. OFFSIDES ═══");
        System.out.printf("  Total:        %d (%.1f per match)%n", totalOffsides, totalOffsides / N);

        // ── 9. VAR ──
        System.out.println("\n═══ 9. VAR REVIEW ═══");
        System.out.printf("  Total VAR reviews:       %d (%.1f per match)%n", varReviews, varReviews / N);
        System.out.printf("  Offside confirmed:       %d  | overturned: %d%n", varOffsideConfirmed, varOffsideOverturned);
        System.out.printf("  Goal confirmed:          %d  | overturned: %d%n", varGoalConfirmed, varGoalOverturned);
        System.out.printf("  Red card confirmed:      %d  | overturned: %d%n", varRedConfirmed, varRedOverturned);
        System.out.printf("  Penalty confirmed:       %d  | overturned: %d%n", varPenaltyConfirmed, varPenaltyOverturned);
        int totalConfirmed = varOffsideConfirmed + varGoalConfirmed + varRedConfirmed + varPenaltyConfirmed;
        int totalOverturned = varOffsideOverturned + varGoalOverturned + varRedOverturned + varPenaltyOverturned;
        if (totalConfirmed + totalOverturned > 0) {
            System.out.printf("  Overturn rate:           %.0f%%%n", 100.0 * totalOverturned / (totalConfirmed + totalOverturned));
        }

        // ── 10. DUELS ──
        System.out.println("\n═══ 10. DUELS & TACKLES ═══");
        System.out.printf("  Duels total:     %d (%.1f per match)%n", duelsTotal, duelsTotal / N);
        System.out.printf("  Interceptions:   %d (%.1f per match)%n", interceptions, interceptions / N);
        System.out.printf("  Loose balls:     %d (%.1f per match)%n", looseBallCount, looseBallCount / N);

        // ── 11. RESTART BREAKDOWN ──
        System.out.println("\n═══ 11. RESTART SOURCES ═══");
        int totalRestarts = totalCorners + totalThrowIns + totalGoalKicks;
        System.out.printf("  Total restarts:      %d (%.1f per match)%n", totalRestarts, totalRestarts / N);
        System.out.printf("    Corners:           %.0f%%%n", totalRestarts > 0 ? 100.0 * totalCorners / totalRestarts : 0);
        System.out.printf("    Throw-ins:         %.0f%%%n", totalRestarts > 0 ? 100.0 * totalThrowIns / totalRestarts : 0);
        System.out.printf("    Goal kicks:        %.0f%%%n", totalRestarts > 0 ? 100.0 * totalGoalKicks / totalRestarts : 0);

        // ── 12. FOOTBALL-SPECIFIC ANALYSIS ──
        System.out.println("\n═══ 12. TACTICAL ANALYSIS ═══");
        System.out.printf("  Goals from cross:     %d (%.0f%% of goals)%n", goalsFromCross,
                totalGoals > 0 ? 100.0 * goalsFromCross / totalGoals : 0);
        System.out.printf("  Goals from center:    %d (%.0f%% of goals)%n", goalsFromCenter,
                totalGoals > 0 ? 100.0 * goalsFromCenter / totalGoals : 0);
        System.out.printf("  Goals from thru ball: %d (%.0f%% of goals)%n", goalsFromThru,
                totalGoals > 0 ? 100.0 * goalsFromThru / totalGoals : 0);
        System.out.printf("  Goals from open play: %d (%.0f%% of goals)%n", totalGoals - goalsFromCross - goalsFromCenter - goalsFromThru,
                totalGoals > 0 ? 100.0 * (totalGoals - goalsFromCross - goalsFromCenter - goalsFromThru) / totalGoals : 0);

        // ── SCORE DISTRIBUTION ──
        System.out.println("\n═══ SCORE DISTRIBUTION ═══");
        Map<String, Integer> scoreDist = new TreeMap<>();
        for (String s : scorelines) {
            scoreDist.merge(s, 1, Integer::sum);
        }
        scoreDist.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(12)
                .forEach(e -> System.out.printf("  %-6s %4d times (%.0f%%)%n",
                        e.getKey(), e.getValue(), 100.0 * e.getValue() / totalMatches));

        System.out.println("\n══════════════════════════════════════════════════════════════");
        System.out.println("End of report.");
    }
}
