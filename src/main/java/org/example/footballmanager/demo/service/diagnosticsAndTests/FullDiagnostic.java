package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Action;
import org.example.footballmanager.demo.service.model.PassHeight;
import org.example.footballmanager.demo.service.recording.MatchEvent;
import org.example.footballmanager.demo.service.result.*;

import java.util.*;

public class FullDiagnostic {
    public static void main(String[] args) {
        long seed = System.currentTimeMillis();
        MatchSimulator sim = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Home", 14);
        var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Away", 14);
        MatchResult result = sim.simulate(homePlayers, awayPlayers, "Home", "Away");

        TeamMatchStats hs = result.homeStats();
        TeamMatchStats as = result.awayStats();

        // === From TeamMatchStats (authoritative) ===
        int homeGoals = hs.goals(), awayGoals = as.goals();
        int homeShots = hs.shots(), awayShots = as.shots();
        int homeShotsOt = hs.shotsOnTarget(), awayShotsOt = as.shotsOnTarget();
        int homePasses = hs.passesAttempted(), awayPasses = as.passesAttempted();
        int homePassCompleted = hs.passesCompleted(), awayPassCompleted = as.passesCompleted();
        int homeCorners = hs.corners(), awayCorners = as.corners();
        int homeOffsides = hs.offsides(), awayOffsides = as.offsides();
        int homeYellow = hs.yellowCards(), awayYellow = as.yellowCards();
        int homeRed = hs.redCards(), awayRed = as.redCards();
        int homePenalties = hs.penalties(), awayPenalties = as.penalties();
        int homeThrowIns = hs.getThrowInCount(), awayThrowIns = as.getThrowInCount();
        int homeInterceptions = hs.getInterceptionCount(), awayInterceptions = as.getInterceptionCount();
        int homeFouls = hs.fouls(), awayFouls = as.fouls();
        int homeGoalKicks = hs.getGoalKickCount(), awayGoalKicks = as.getGoalKickCount();
        int homeLoosePasses = hs.getLooseBallCount(), awayLoosePasses = as.getLooseBallCount();
        int homePassOutOfBounds = hs.getPassOutOfBoundsCount(), awayPassOutOfBounds = as.getPassOutOfBoundsCount();
        int homeThruAttempts = hs.getThruAttempts(), awayThruAttempts = as.getThruAttempts();
        int homeThruCompleted = hs.getThruCompleted(), awayThruCompleted = as.getThruCompleted();

        // === From MatchEvent records ===
        int varOffsideConfirmed = 0, varOffsideOverturned = 0;
        int varGoalConfirmed = 0, varGoalOverturned = 0;
        int homeSavesCount = 0, awaySavesCount = 0;
        int homeBlocks = 0, awayBlocks = 0;
        int homeDeflections = 0, awayDeflections = 0;
        int homePenaltiesScored = 0, awayPenaltiesScored = 0;
        int homePenaltiesMissed = 0, awayPenaltiesMissed = 0;
        int homePenaltiesSaved = 0, awayPenaltiesSaved = 0;

        for (MatchEvent ev : result.events()) {
            String t = ev.type() == null ? "" : ev.type();
            String team = ev.team();

            if (t.contains("VAR_OFFSIDE_CONFIRMED")) varOffsideConfirmed++;
            if (t.contains("VAR_OFFSIDE_OVERTURNED")) varOffsideOverturned++;
            if (t.contains("VAR_GOAL_CONFIRMED")) varGoalConfirmed++;
            if (t.contains("VAR_GOAL_OVERTURNED")) varGoalOverturned++;
            if (t.contains("GK_SAVE")) {
                if ("HOME".equals(team)) homeSavesCount++;
                else awaySavesCount++;
            }
            if (t.contains("SHOT_BLOCKED")) {
                if ("HOME".equals(team)) homeBlocks++;
                else awayBlocks++;
            }
            if (t.contains("DEFLECTION")) {
                if ("HOME".equals(team)) homeDeflections++;
                else awayDeflections++;
            }
            if (t.contains("PENALTY_SCORED")) {
                if ("HOME".equals(team)) homePenaltiesScored++;
                else awayPenaltiesScored++;
            }
            if (t.contains("PENALTY_MISSED")) homePenaltiesMissed++;
            if (t.contains("PENALTY_SAVED")) homePenaltiesSaved++;
        }

        // === From logs (only for things not in TeamMatchStats) ===
        int homeCrosses = 0, awayCrosses = 0;
        int homeCenters = 0, awayCenters = 0;
        int homeCarries = 0, awayCarries = 0;
        int homeChases = 0, awayChases = 0;
        int homeDuels = 0, awayDuels = 0;
        int homeAirDuels = 0, awayAirDuels = 0;
        int homeOffsideRetreats = 0, awayOffsideRetreats = 0;
        int homeAirPasses = 0, awayAirPasses = 0;
        int homeGroundPasses = 0, awayGroundPasses = 0;
        int homeCrossesScored = 0, awayCrossesScored = 0;
        int homeCentersScored = 0, awayCentersScored = 0;

        String lastGoalSource = "KICKOFF";

        for (LogEntry e : result.logs()) {
            String desc = e.getDescription();
            String team = e.getTeam();
            String channel = e.getChannel();

            if (team == null) {
                if (desc.contains("Home ") || desc.contains("HOME ")) team = "HOME";
                else if (desc.contains("Away ") || desc.contains("AWAY ")) team = "AWAY";
            }
            boolean isHome = "HOME".equals(team);

            // OFFSIDE RETREAT
            if (desc.contains("OFFSIDE RETREAT")) {
                if (isHome) homeOffsideRetreats++;
                else awayOffsideRetreats++;
            }

            // ACTION entries
            if (desc.startsWith("ACTION:")) {
                if (desc.contains("CROSS")) {
                    if (isHome) homeCrosses++; else awayCrosses++;
                    lastGoalSource = "CROSS";
                } else if (desc.contains("CENTER")) {
                    if (isHome) homeCenters++; else awayCenters++;
                    lastGoalSource = "CENTER";
                } else if (desc.contains("CARRY")) {
                    if (isHome) homeCarries++; else awayCarries++;
                } else if (desc.startsWith("ACTION: PASS") && !desc.contains("THRU")) {
                    Object ctx = e.getContext();
                    boolean isAir = (ctx instanceof Action a && a.getPassHeight() == PassHeight.AIR);
                    if (isHome) {
                        if (isAir) homeAirPasses++; else homeGroundPasses++;
                    } else {
                        if (isAir) awayAirPasses++; else awayGroundPasses++;
                    }
                }
                if (desc.contains("THRU") || desc.startsWith("THRU")) {
                    lastGoalSource = "THRU";
                }
            }

            // GOAL
            if ((desc.contains("GOAL!") || desc.startsWith("GOAL:")) && !desc.contains("PENALTY")) {
                if ("CROSS".equals(lastGoalSource)) {
                    if (isHome) homeCrossesScored++; else awayCrossesScored++;
                } else if ("CENTER".equals(lastGoalSource)) {
                    if (isHome) homeCentersScored++; else awayCentersScored++;
                }
            }

            if (channel != null && channel.contains("KICKOFF")) {
                lastGoalSource = "KICKOFF";
            }
        }

        int homeCleanSheets = (awayGoals == 0) ? 1 : 0;
        int awayCleanSheets = (homeGoals == 0) ? 1 : 0;

        int totalGoals = homeGoals + awayGoals;
        int totalShots = homeShots + awayShots;
        int totalShotsOt = homeShotsOt + awayShotsOt;
        int totalPasses = homePasses + awayPasses;
        int totalPassCompleted = homePassCompleted + awayPassCompleted;
        int totalCorners = homeCorners + awayCorners;
        int totalThrowIns = homeThrowIns + awayThrowIns;
        int totalGoalKicks = homeGoalKicks + awayGoalKicks;
        int totalOffsides = homeOffsides + awayOffsides;
        int totalYellow = homeYellow + awayYellow;
        int totalRed = homeRed + awayRed;
        int totalPenalties = homePenalties + awayPenalties;
        int totalFouls = homeFouls + awayFouls;
        int totalInterceptions = homeInterceptions + awayInterceptions;
        int totalChases = homeChases + awayChases;
        int totalDuels = homeDuels + awayDuels;
        int totalCarries = homeCarries + awayCarries;
        int totalCrosses = homeCrosses + awayCrosses;
        int totalCenters = homeCenters + awayCenters;
        int totalAirDuels = homeAirDuels + awayAirDuels;
        int totalOffsideRetreats = homeOffsideRetreats + awayOffsideRetreats;
        int totalVar = varOffsideConfirmed + varOffsideOverturned;
        int totalSaves = homeSavesCount + awaySavesCount;

        double passAccTotal = totalPasses > 0 ? 100.0 * totalPassCompleted / totalPasses : 0;
        double homePassAcc = homePasses > 0 ? 100.0 * homePassCompleted / homePasses : 0;
        double awayPassAcc = awayPasses > 0 ? 100.0 * awayPassCompleted / awayPasses : 0;
        double conversion = totalShots > 0 ? 100.0 * totalGoals / totalShots : 0;
        double sotPct = totalShots > 0 ? 100.0 * totalShotsOt / totalShots : 0;
        double savePct = totalShotsOt > 0 ? 100.0 * totalSaves / totalShotsOt : 0;

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 FULL MATCH STATISTICS (1 match, skill=14)                  ║");
        System.out.println("╠════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ %-38s  %6s  %6s  %8s  %6s%n", "Metric", "Home", "Away", "Total", "Real");
        System.out.println("╠════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "GOALS", homeGoals, awayGoals, totalGoals, "2.5-3.2");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Shots (total)", homeShots, awayShots, totalShots, "25-35");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6.1f%%%n", "Shots on target", homeShotsOt, awayShotsOt, totalShotsOt, sotPct);
        System.out.printf("║ %-38s  %6.1f%%  %6.1f%%  %8.1f%%  %6s%n", "Shot conversion", homeShots>0?100.0*homeGoals/homeShots:0, awayShots>0?100.0*awayGoals/awayShots:0, conversion, "10-12%");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Passes (total)", homePasses, awayPasses, totalPasses, "500-700");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Passes completed", homePassCompleted, awayPassCompleted, totalPassCompleted, "");
        System.out.printf("║ %-38s  %6.1f%%  %6.1f%%  %8.1f%%  %6s%n", "Pass accuracy", homePassAcc, awayPassAcc, passAccTotal, "75-85%");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "VAR offsides confirmed", homeOffsides, awayOffsides, totalOffsides, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "VAR offsides overturned", varOffsideOverturned, varOffsideOverturned, varOffsideOverturned, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "VAR goal events", varGoalConfirmed, varGoalConfirmed, varGoalConfirmed, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "GK saves", homeSavesCount, awaySavesCount, totalSaves, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Blocks (shots)", homeBlocks, awayBlocks, homeBlocks+awayBlocks, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Deflections", homeDeflections, awayDeflections, homeDeflections+awayDeflections, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Interceptions", homeInterceptions, awayInterceptions, totalInterceptions, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Goal kicks", homeGoalKicks, awayGoalKicks, totalGoalKicks, "3-5");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Throw-ins (out of bounds)", homeThrowIns, awayThrowIns, totalThrowIns, "4-6");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Corners", homeCorners, awayCorners, totalCorners, "9-11");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Goals from corners", homeCrossesScored+homeCentersScored, awayCrossesScored+awayCentersScored, homeCrossesScored+homeCentersScored+awayCrossesScored+awayCentersScored, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Offsides (confirmed)", homeOffsides, awayOffsides, totalOffsides, "2-5");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Offside retreats", homeOffsideRetreats, awayOffsideRetreats, totalOffsideRetreats, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Yellow cards", homeYellow, awayYellow, totalYellow, "2-4");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Red cards", homeRed, awayRed, totalRed, "0.1-0.3");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Penalties awarded", homePenalties, awayPenalties, totalPenalties, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Penalties scored", homePenaltiesScored, awayPenaltiesScored, homePenaltiesScored+awayPenaltiesScored, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Penalties missed/saved", homePenaltiesMissed+homePenaltiesSaved, awayPenaltiesMissed+awayPenaltiesSaved, homePenaltiesMissed+homePenaltiesSaved+awayPenaltiesMissed+awayPenaltiesSaved, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Free kicks (fouls awarded)", homeFouls, awayFouls, totalFouls, "10-15");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Crosses", homeCrosses, awayCrosses, totalCrosses, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Goals from crosses", homeCrossesScored, awayCrossesScored, homeCrossesScored+awayCrossesScored, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Centers", homeCenters, awayCenters, totalCenters, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Goals from centers", homeCentersScored, awayCentersScored, homeCentersScored+awayCentersScored, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Carries / dribbles", homeCarries, awayCarries, totalCarries, "80-150");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Duels", homeDuels, awayDuels, totalDuels, "30-50");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Chases", homeChases, awayChases, totalChases, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Air duels", homeAirDuels, awayAirDuels, totalAirDuels, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Clean sheets", homeCleanSheets, awayCleanSheets, homeCleanSheets+awayCleanSheets, "38%/28%");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Air passes", homeAirPasses, awayAirPasses, homeAirPasses+awayAirPasses, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Ground passes", homeGroundPasses, awayGroundPasses, homeGroundPasses+awayGroundPasses, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Loose passes (not received)", homeLoosePasses, awayLoosePasses, homeLoosePasses+awayLoosePasses, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Pass out of bounds", homePassOutOfBounds, awayPassOutOfBounds, homePassOutOfBounds+awayPassOutOfBounds, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "THRU attempts", homeThruAttempts, awayThruAttempts, homeThruAttempts+awayThruAttempts, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "THRU completed", homeThruCompleted, awayThruCompleted, homeThruCompleted+awayThruCompleted, "");
        System.out.printf("║ %-38s  %6d  %6d  %8d  %6s%n", "Total passes+carries", homePasses+homeCarries, awayPasses+awayCarries, totalPasses+totalCarries, "");
        System.out.printf("║ %-38s  %6.1f%% %6.1f%%  %8.1f%%  %6s%n", "Save rate (of Sot)", 0.0, 0.0, savePct, "25-35%");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════════╝");

        System.out.println("\n--- REAL WORLD REFERENCE ---");
        System.out.println("Goals/match: 2.5-3.2 | Shots: 25-35 | Shots OT: 35-40% | Passes: 500-700 | Pass acc: 75-85%");
        System.out.println("Corners: 9-11 | Throw-ins: 4-6 | Offsides: 2-5 | Yellow: 2-4 | Red: 0.1-0.3");
        System.out.println("Penalties: ~0.3/match | Free kicks: ~10-15 | Clean sheets: ~38% home, ~28% away");
        System.out.println("BTTS: 55-60% | Conversion: 10-12% | Saves: 25-35% of shots on target");

        System.out.println("\n--- EVENT TYPES ---");
        Map<String, Integer> eventTypes = new TreeMap<>();
        for (MatchEvent ev : result.events()) {
            String t = ev.type() == null ? "null" : ev.type();
            eventTypes.merge(t, 1, Integer::sum);
        }
        eventTypes.forEach((k, v) -> System.out.printf("  %-45s: %d%n", k, v));

        System.out.println("\n--- LOG CHANNEL COUNTS ---");
        Map<String, Integer> channelCount = new TreeMap<>();
        for (LogEntry e : result.logs()) {
            String ch = e.getChannel() == null ? "null" : e.getChannel();
            channelCount.merge(ch, 1, Integer::sum);
        }
        channelCount.forEach((k, v) -> System.out.printf("  %-45s: %d%n", k, v));

        System.out.println("\n--- KEY ISSUES ---");
        if (totalPasses < 400) System.out.println("  !! Passes too low: " + totalPasses + " (real: 500-700)");
        if (totalGoalKicks > 20) System.out.println("  !! Goal kicks way too high: " + totalGoalKicks + " (real: 3-5)");
        if (conversion < 8) System.out.println("  !! Conversion too low: " + String.format("%.1f", conversion) + "% (real: 10-12%)");
        if (totalCarries > 300) System.out.println("  !! Carries too high: " + totalCarries + " (real: 80-150)");
        if (totalYellow < 1) System.out.println("  !! Yellow cards too low: " + totalYellow + " (real: 2-4)");
        if (totalDuels > 200) System.out.println("  !! Duels too high: " + totalDuels + " (real: 30-50)");
        if (totalPasses+totalCarries < 500) System.out.println("  !! Total passes+carries too low: " + (totalPasses+totalCarries) + " (should be 500+)");
    }
}
