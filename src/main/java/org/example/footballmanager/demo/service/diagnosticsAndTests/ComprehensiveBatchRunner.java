package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Action;
import org.example.footballmanager.demo.service.model.PassHeight;
import org.example.footballmanager.demo.service.result.*;

import java.util.*;

/**
 * Runs N matches and outputs comprehensive football analytics.
 * Stats come primarily from TeamMatchStats and MatchEvent records.
 * Log parsing only for metrics not tracked in stats/events.
 */
public class ComprehensiveBatchRunner {

    public static void main(String[] args) {
        int numMatches = 500;
        int skill = 14;
        if (args.length > 0) {
            try { numMatches = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        if (args.length > 1) {
            try { skill = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }

        // ── Per-team accumulators (all from TeamMatchStats) ──
        int homeGoals = 0, awayGoals = 0;
        int homeShots = 0, awayShots = 0;
        int homeShotsOnTarget = 0, awayShotsOnTarget = 0;
        int homePassesAttempted = 0, awayPassesAttempted = 0;
        int homePassesCompleted = 0, awayPassesCompleted = 0;
        int homeFouls = 0, awayFouls = 0;
        int homeYellowCards = 0, awayYellowCards = 0;
        int homeRedCards = 0, awayRedCards = 0;
        int homeCorners = 0, awayCorners = 0;
        int homeOffsides = 0, awayOffsides = 0;
        int homeThrowIns = 0, awayThrowIns = 0;
        int homeGoalKicks = 0, awayGoalKicks = 0;
        int homeInterceptions = 0, awayInterceptions = 0;
        int homeClearances = 0, awayClearances = 0;
        int homePenaltiesAwarded = 0, awayPenaltiesAwarded = 0;
        int homePenaltiesScored = 0, awayPenaltiesScored = 0;
        int homeFreeKicks = 0, awayFreeKicks = 0;
        int homeCleanSheets = 0, awayCleanSheets = 0;
        int homePossessionChanges = 0, awayPossessionChanges = 0;
        int homePassLoose = 0, awayPassLoose = 0;
        int homeChases = 0, awayChases = 0;
        int homeChaseWins = 0, awayChaseWins = 0;
        int homeCarries = 0, awayCarries = 0;
        int homeAerialDuels = 0, awayAerialDuels = 0;
        int homeTackleDuels = 0, awayTackleDuels = 0;
        int homeDribbleDuels = 0, awayDribbleDuels = 0;
        int homeGoalsFromCross = 0, awayGoalsFromCross = 0;
        int homeGoalsFromCenter = 0, awayGoalsFromCenter = 0;
        int homeGoalsFromThru = 0, awayGoalsFromThru = 0;
        int homeGoalsFromOpenPlay = 0, awayGoalsFromOpenPlay = 0;
        int homeGoalsFromPenalty = 0, awayGoalsFromPenalty = 0;
        int homeGoalsFromFreeKick = 0, awayGoalsFromFreeKick = 0;
        int homeGoalsFromCorner = 0, awayGoalsFromCorner = 0;
        int homeFreeKickShotsOnGoal = 0, awayFreeKickShotsOnGoal = 0;
        int homeFreeKickGoals = 0, awayFreeKickGoals = 0;
        int homeDribbles = 0, awayDribbles = 0;

        // ── From MatchEvent records ──
        int varOffsideConfirmed = 0, varOffsideOverturned = 0;
        int varGoalConfirmed = 0, varGoalOverturned = 0;
        int varRedConfirmed = 0, varRedOverturned = 0;
        int varPenaltyConfirmed = 0, varPenaltyOverturned = 0;
        int homeVarReviews = 0, awayVarReviews = 0;
        int homeSaves = 0, awaySaves = 0;
        int homeBlocks = 0, awayBlocks = 0;
        int homeDeflections = 0, awayDeflections = 0;
        int homePenaltiesMissed = 0, awayPenaltiesMissed = 0;
        int homePenaltiesSaved = 0, awayPenaltiesSaved = 0;

        // ── From logs ──
        int homeCrosses = 0, awayCrosses = 0;
        int homeCrossesCompleted = 0, awayCrossesCompleted = 0;
        int homeCenters = 0, awayCenters = 0;
        int homeCentersCompleted = 0, awayCentersCompleted = 0;
        int homeAirPasses = 0, awayAirPasses = 0;
        int homeAirPassCompleted = 0, awayAirPassCompleted = 0;
        int homeGroundPasses = 0, awayGroundPasses = 0;
        int homeGroundPassCompleted = 0, awayGroundPassCompleted = 0;
        int homeOffsideRetreats = 0, awayOffsideRetreats = 0;
        int homeInjuries = 0, awayInjuries = 0;
        int homeSubstitutions = 0, awaySubstitutions = 0;

        List<String> scorelines = new ArrayList<>();

        System.out.println("=== COMPREHENSIVE BATCH: " + numMatches + " MATCHES | SKILL=" + skill + " ===\n");

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numMatches; i++) {
            long seed = 1000 + i * 7L;
            MatchSimulator sim = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Home", skill);
            var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Away", skill);
            MatchResult result = sim.simulate(homePlayers, awayPlayers, "Home", "Away");

            int hg = result.homeGoals(), ag = result.awayGoals();
            homeGoals += hg; awayGoals += ag;
            homeShots += result.homeStats().shots(); awayShots += result.awayStats().shots();
            homeShotsOnTarget += result.homeStats().shotsOnTarget(); awayShotsOnTarget += result.awayStats().shotsOnTarget();
            homePassesAttempted += result.homeStats().passesAttempted(); awayPassesAttempted += result.awayStats().passesAttempted();
            homePassesCompleted += result.homeStats().passesCompleted(); awayPassesCompleted += result.awayStats().passesCompleted();
            homeFouls += result.homeStats().fouls(); awayFouls += result.awayStats().fouls();
            homeYellowCards += result.homeStats().yellowCards(); awayYellowCards += result.awayStats().yellowCards();
            homeRedCards += result.homeStats().redCards(); awayRedCards += result.awayStats().redCards();
            homeCorners += result.homeStats().corners(); awayCorners += result.awayStats().corners();
            homeOffsides += result.homeStats().offsides(); awayOffsides += result.awayStats().offsides();
            homeThrowIns += result.homeStats().getThrowInCount(); awayThrowIns += result.awayStats().getThrowInCount();
            homeGoalKicks += result.homeStats().getGoalKickCount(); awayGoalKicks += result.awayStats().getGoalKickCount();
            homeInterceptions += result.homeStats().getInterceptionCount(); awayInterceptions += result.awayStats().getInterceptionCount();
            homePossessionChanges += result.homeStats().getPassOutOfBoundsCount(); awayPossessionChanges += result.awayStats().getPassOutOfBoundsCount();
            homePassLoose += result.homeStats().getLooseBallCount(); awayPassLoose += result.awayStats().getLooseBallCount();
            homeSaves += Math.max(0, result.homeStats().shotsOnTarget() - result.homeStats().goals());
            awaySaves += Math.max(0, result.awayStats().shotsOnTarget() - result.awayStats().goals());
            homeBlocks += result.homeStats().blocks(); awayBlocks += result.awayStats().blocks();
            homeDeflections += result.homeStats().deflections(); awayDeflections += result.awayStats().deflections();
            homeClearances += result.homeStats().clearances(); awayClearances += result.awayStats().clearances();
            if (ag == 0) homeCleanSheets++;
            if (hg == 0) awayCleanSheets++;

            // ── Parse MatchEvent records ──
            String lastGoalSource = "open";
            String lastRestartType = "open";
            for (var event : result.events()) {
                String t = event.type();
                String d = event.description() != null ? event.description() : "";
                boolean evHome = "HOME".equals(event.team());

                if (t != null && t.startsWith("VAR_")) {
                    if (evHome) homeVarReviews++; else awayVarReviews++;
                    if (t.contains("OFFSIDE")) {
                        if (t.contains("OVERTURNED")) { varOffsideOverturned++; }
                        else { varOffsideConfirmed++; }
                    } else if (t.contains("GOAL")) {
                        if (t.contains("OVERTURNED")) { varGoalOverturned++; }
                        else { varGoalConfirmed++; }
                    } else if (t.contains("RED")) {
                        if (t.contains("OVERTURNED")) { varRedOverturned++; }
                        else { varRedConfirmed++; }
                    } else if (t.contains("PENALTY")) {
                        if (!t.contains("OVERTURNED")) { varPenaltyConfirmed++; }
                        else { varPenaltyOverturned++; }
                    }
                }
                if (t != null && t.equals("PENALTY_KICK")) {
                    if (evHome) homePenaltiesAwarded++; else awayPenaltiesAwarded++;
                }
                if (t != null && t.equals("PENALTY_GOAL")) {
                    if (evHome) homePenaltiesScored++; else awayPenaltiesScored++;
                }
                if (t != null && t.equals("PENALTY_SAVED")) {
                    if (evHome) homePenaltiesSaved++; else awayPenaltiesSaved++;
                }
                if (t != null && t.equals("PENALTY_MISS")) {
                    if (evHome) homePenaltiesMissed++; else awayPenaltiesMissed++;
                }
                if (t != null && t.equals("SHOT_SAVED") && !evHome) { /* saves counted via sot - goals */ }
                if (t != null && t.equals("GOAL")) {
                    if (d.contains("PENALTY")) {
                        if (evHome) homeGoalsFromPenalty++; else awayGoalsFromPenalty++;
                    } else if (d.contains("FREE_KICK")) {
                        if (evHome) homeGoalsFromFreeKick++; else awayGoalsFromFreeKick++;
                        if (lastRestartType.equals("freekick")) {
                            if (evHome) homeFreeKickGoals++; else awayFreeKickGoals++;
                        }
                    } else if (lastGoalSource.equals("cross")) {
                        if (evHome) homeGoalsFromCross++; else awayGoalsFromCross++;
                    } else if (lastGoalSource.equals("center")) {
                        if (evHome) homeGoalsFromCenter++; else awayGoalsFromCenter++;
                    } else if (lastGoalSource.equals("thru")) {
                        if (evHome) homeGoalsFromThru++; else awayGoalsFromThru++;
                    } else if (lastRestartType.equals("corner")) {
                        if (evHome) homeGoalsFromCorner++; else awayGoalsFromCorner++;
                    } else {
                        if (evHome) homeGoalsFromOpenPlay++; else awayGoalsFromOpenPlay++;
                    }
                    lastGoalSource = "open";
                    lastRestartType = "open";
                }
            }

            // ── Parse LogEntry records ──
            for (LogEntry e : result.logs()) {
                String desc = e.getDescription();
                String ch = e.getChannel();

                // Determine team — team field can be null for some entries
                String team = e.getTeam();
                if (team == null) {
                    if (desc.contains("HOME ")) team = "HOME";
                    else if (desc.contains("AWAY ")) team = "AWAY";
                    else if (desc.contains("Home ") || desc.startsWith("Home ")) team = "HOME";
                    else if (desc.contains("Away ") || desc.startsWith("Away ")) team = "AWAY";
                    else team = null;
                }
                boolean isHome = "HOME".equals(team);

                if (ch.equals("KICKOFF")) { lastGoalSource = "open"; lastRestartType = "open"; }

                // Track restart types
                if (ch.equals("CORNER") || desc.contains("CORNER")) { lastRestartType = "corner"; }
                if (ch.equals("FREE_KICK") || desc.contains("FREE_KICK") || desc.contains("free kick")) { lastRestartType = "freekick"; }

                // Track shots from free kicks
                if (lastRestartType.equals("freekick") && desc.startsWith("ACTION: SHOT")) {
                    if (isHome) homeFreeKickShotsOnGoal++; else awayFreeKickShotsOnGoal++;
                }

                // Dribble duels
                if (ch.equals("DUEL") && desc.contains("DRIBBLE")) {
                    if (isHome) homeDribbles++; else awayDribbles++;
                }

                // VAR entries in logs
                if (ch.equals("VAR")) {
                    if (desc.contains("OVERTURNED")) {
                        if (desc.contains("OFFSIDE")) varOffsideOverturned++;
                    } else {
                        if (desc.contains("OFFSIDE")) varOffsideConfirmed++;
                    }
                }

                // FOUL channel → free kicks awarded
                if (ch.equals("FOUL")) {
                    if (isHome) homeFreeKicks++; else awayFreeKicks++;
                }

                // CARD channel
                if (ch.equals("CARD") && desc.contains("YELLOW")) {
                    if (isHome) homeYellowCards++; else awayYellowCards++;
                }

                // OFFSIDE — retreats are logged under channel INFO with a
                // description containing "OFFSIDE RETREAT" (space, not underscore),
                // so match on the description regardless of channel.
                if (desc.contains("OFFSIDE RETREAT")) {
                    if (isHome) homeOffsideRetreats++; else awayOffsideRetreats++;
                }

                // INFO channel
                if (ch.equals("INFO")) {
                    if (desc.contains("INJURY") || desc.contains("injury")) {
                        if (isHome) homeInjuries++; else awayInjuries++;
                    }
                    if (desc.contains("SUB") || desc.contains("substitution")) {
                        if (isHome) homeSubstitutions++; else awaySubstitutions++;
                    }
                }

                // DUEL — determine team from desc (format: "CHASE WINNER: Away 1 reached..." or "DUEL: Away vs Home...")
                if (ch.equals("DUEL") || ch.equals("CHASE")) {
                    if (ch.equals("CHASE")) {
                        if (isHome) homeChases++; else awayChases++;
                        if (desc.contains("CHASE WINNER")) {
                            if (isHome) homeChaseWins++; else awayChaseWins++;
                        }
                    }
                    if (ch.equals("DUEL")) {
                        if (desc.contains("AERIAL")) {
                            if (isHome) homeAerialDuels++; else awayAerialDuels++;
                        }
                        if (desc.contains("TACKLE") || desc.contains("SHOT")) {
                            if (isHome) homeTackleDuels++; else awayTackleDuels++;
                        }
                        if (desc.contains("DRIBBLE")) {
                            if (isHome) homeDribbleDuels++; else awayDribbleDuels++;
                        }
                    }
                }

                // ACTION entries
                if (desc.startsWith("ACTION:")) {
                    // Cross
                    if (desc.contains("ACTION: CROSS")) {
                        if (isHome) homeCrosses++; else awayCrosses++;
                        lastGoalSource = "cross";
                    }
                    // Center
                    if (desc.contains("ACTION: CENTER")) {
                        if (isHome) homeCenters++; else awayCenters++;
                        lastGoalSource = "center";
                    }
                    // THRU
                    if (desc.startsWith("THRU ") || desc.contains("THRU PASS:")) {
                        lastGoalSource = "thru";
                    }
                    // Carry
                    if (desc.startsWith("ACTION: CARRY")) {
                        if (isHome) homeCarries++; else awayCarries++;
                    }
                    // Clearance
                    if (desc.contains("CLEAR:")) {
                        if (isHome) homeClearances++; else awayClearances++;
                    }
                    // Air vs ground pass
                    if (desc.startsWith("ACTION: PASS")) {
                        Object ctx = e.getContext();
                        boolean isAir = (ctx instanceof Action a && a.getPassHeight() == PassHeight.AIR);
                        if (isAir) {
                            if (isHome) { homeAirPasses++; } else { awayAirPasses++; }
                        } else {
                            if (isHome) { homeGroundPasses++; } else { awayGroundPasses++; }
                        }
                    }
                }

                // OUTCOME entries
                if (desc.startsWith("OUTCOME:")) {
                    // Cross received
                    if (desc.contains("OUTCOME: CROSS") && (desc.contains("RECEIVED") || desc.contains("PASS_RECEIVED"))) {
                        if (isHome) homeCrossesCompleted++; else awayCrossesCompleted++;
                    }
                    // Center received
                    if (desc.contains("OUTCOME: CENTER") && (desc.contains("RECEIVED") || desc.contains("PASS_RECEIVED"))) {
                        if (isHome) homeCentersCompleted++; else awayCentersCompleted++;
                    }
                    // Pass received — air vs ground
                    if (desc.contains("OUTCOME: PASS") && !desc.contains("THRU")) {
                        Object ctx = e.getContext();
                        boolean isAir = (ctx instanceof Action a && a.getPassHeight() == PassHeight.AIR);
                        if (desc.contains("PASS_RECEIVED") || desc.contains("RECEIVED")) {
                            if (isAir) {
                                if (isHome) homeAirPassCompleted++; else awayAirPassCompleted++;
                            } else {
                                if (isHome) homeGroundPassCompleted++; else awayGroundPassCompleted++;
                            }
                        }
                    }
                    // Shot blocked
                    if (desc.contains("SHOT BLOCKED")) {
                        if (isHome) homeBlocks++; else awayBlocks++;
                    }
                    // Deflection
                    if (desc.contains("deflected") || desc.contains("DEFLECTION")) {
                        if (isHome) homeDeflections++; else awayDeflections++;
                    }
                }
            }

            scorelines.add(hg + "-" + ag);
            if ((i + 1) % 50 == 0) {
                System.out.printf("  %d/%d done (%.1f goals/match avg)%n",
                        i + 1, numMatches, (homeGoals + awayGoals) / (double) (i + 1));
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("%nCompleted in %d seconds%n%n", elapsed / 1000);

        // ═══════════════════════════════════════
        // PRINT REPORT
        // ═══════════════════════════════════════
        int N = numMatches;
        double Nd = N;

        int totalGoals = homeGoals + awayGoals;
        int totalShots = homeShots + awayShots;
        int totalSot = homeShotsOnTarget + awayShotsOnTarget;
        int totalPassAtt = homePassesAttempted + awayPassesAttempted;
        int totalPassComp = homePassesCompleted + awayPassesCompleted;
        int totalCorners = homeCorners + awayCorners;
        int totalOffsides = homeOffsides + awayOffsides;
        int totalThrowIns = homeThrowIns + awayThrowIns;
        int totalGoalKicks = homeGoalKicks + awayGoalKicks;
        int totalFouls = homeFouls + awayFouls;
        int totalYellow = homeYellowCards + awayYellowCards;
        int totalRed = homeRedCards + awayRedCards;
        int totalVar = homeVarReviews + awayVarReviews;
        int totalPenaltiesAwarded = homePenaltiesAwarded + awayPenaltiesAwarded;
        int totalPenaltiesScored = homePenaltiesScored + awayPenaltiesScored;
        int totalFreeKicks = homeFreeKicks + awayFreeKicks;
        int totalSaves = homeSaves + awaySaves;
        int totalBlocks = homeBlocks + awayBlocks;
        int totalDeflections = homeDeflections + awayDeflections;
        int totalChases = homeChases + awayChases;
        int totalChaseWins = homeChaseWins + awayChaseWins;
        int totalCarries = homeCarries + awayCarries;
        int totalAerialDuels = homeAerialDuels + awayAerialDuels;
        int totalTackleDuels = homeTackleDuels + awayTackleDuels;
        int totalDribbleDuels = homeDribbleDuels + awayDribbleDuels;
        int zeroZero = 0;
        int btts = 0;
        for (String s : scorelines) {
            String[] parts = s.split("-");
            if (Integer.parseInt(parts[0]) == 0 && Integer.parseInt(parts[1]) == 0) zeroZero++;
            if (Integer.parseInt(parts[0]) > 0 && Integer.parseInt(parts[1]) > 0) btts++;
        }

        System.out.println("╔════════════════════════════════════════════════════════════════════════╗");
        System.out.printf  ("║  COMPREHENSIVE ANALYSIS — %d MATCHES | SKILL=%d                           ║%n", N, skill);
        System.out.println("╚════════════════════════════════════════════════════════════════════════╝");

        // ── GOALS ──
        System.out.println("\n═══ 1. GOALS ═══");
        System.out.printf("  Total:              %d (%.2f per match)%n", totalGoals, totalGoals / Nd);
        System.out.printf("  HOME:              %d (%.2f per match)%n", homeGoals, homeGoals / Nd);
        System.out.printf("  AWAY:              %d (%.2f per match)%n", awayGoals, awayGoals / Nd);
        System.out.printf("  0-0 draws:         %d (%.0f%%)%n", zeroZero, 100.0 * zeroZero / N);
        System.out.printf("  BTTS:              %d (%.0f%%)%n", btts, 100.0 * btts / N);

        // ── SHOTS ──
        System.out.println("\n═══ 2. SHOTS ═══");
        System.out.printf("  Total shots:       %d (%.1f per match)%n", totalShots, totalShots / Nd);
        System.out.printf("  HOME shots:        %d (%.1f per match)%n", homeShots, homeShots / Nd);
        System.out.printf("  AWAY shots:        %d (%.1f per match)%n", awayShots, awayShots / Nd);
        System.out.printf("  Shots on target:   %d (%.0f%%)%n", totalSot, totalShots > 0 ? 100.0 * totalSot / totalShots : 0);
        System.out.printf("  HOME Sot:          %d (%.0f%%)%n", homeShotsOnTarget, homeShots > 0 ? 100.0 * homeShotsOnTarget / homeShots : 0);
        System.out.printf("  AWAY Sot:          %d (%.0f%%)%n", awayShotsOnTarget, awayShots > 0 ? 100.0 * awayShotsOnTarget / awayShots : 0);
        System.out.printf("  Conversion rate:   %.1f%% (goals/shots)%n", totalShots > 0 ? 100.0 * totalGoals / totalShots : 0);
        System.out.printf("  Shots per goal:    %.1f%n", totalGoals > 0 ? (double) totalShots / totalGoals : 0);
        System.out.printf("  Saves:             %d (%.0f%% of Sot)%n", totalSaves, totalSot > 0 ? 100.0 * totalSaves / totalSot : 0);

        // ── PASSING ──
        System.out.println("\n═══ 3. PASSING ═══");
        System.out.printf("  Total passes:      %d/%d (%.0f%%)%n",
                totalPassComp, totalPassAtt, totalPassAtt > 0 ? 100.0 * totalPassComp / totalPassAtt : 0);
        System.out.printf("  Passes per match:  %.0f%n", totalPassAtt / Nd);
        System.out.printf("  Air passes:        %d/%d (%.0f%%)%n",
                homeAirPassCompleted + awayAirPassCompleted, homeAirPasses + awayAirPasses,
                (homeAirPasses + awayAirPasses) > 0 ? 100.0 * (homeAirPassCompleted + awayAirPassCompleted) / (homeAirPasses + awayAirPasses) : 0);
        System.out.printf("  Ground passes:     %d/%d (%.0f%%)%n",
                homeGroundPassCompleted + awayGroundPassCompleted, homeGroundPasses + awayGroundPasses,
                (homeGroundPasses + awayGroundPasses) > 0 ? 100.0 * (homeGroundPassCompleted + awayGroundPassCompleted) / (homeGroundPasses + awayGroundPasses) : 0);

        // ── CROSSES / CENTERS ──
        System.out.println("\n═══ 4. WIDE PLAY ═══");
        int totalCrosses = homeCrosses + awayCrosses;
        int totalCrossesComp = homeCrossesCompleted + awayCrossesCompleted;
        int totalCenters = homeCenters + awayCenters;
        int totalCentersComp = homeCentersCompleted + awayCentersCompleted;
        System.out.printf("  Crosses:           %d (%.1f/match) — completed: %d (%.0f%%)%n",
                totalCrosses, totalCrosses / Nd, totalCrossesComp, totalCrosses > 0 ? 100.0 * totalCrossesComp / totalCrosses : 0);
        System.out.printf("  Centers:           %d (%.1f/match) — completed: %d (%.0f%%)%n",
                totalCenters, totalCenters / Nd, totalCentersComp, totalCenters > 0 ? 100.0 * totalCentersComp / totalCenters : 0);
        System.out.printf("  Goals from cross:  %d (%.0f%% of goals)%n",
                homeGoalsFromCross + awayGoalsFromCross, totalGoals > 0 ? 100.0 * (homeGoalsFromCross + awayGoalsFromCross) / totalGoals : 0);
        System.out.printf("  Goals from center: %d (%.0f%% of goals)%n",
                homeGoalsFromCenter + awayGoalsFromCenter, totalGoals > 0 ? 100.0 * (homeGoalsFromCenter + awayGoalsFromCenter) / totalGoals : 0);

        // ── SET PIECES ──
        System.out.println("\n═══ 5. SET PIECES ═══");
        System.out.printf("  Corners:           %d (%.1f per match)%n", totalCorners, totalCorners / Nd);
        System.out.printf("  Throw-ins:         %d (%.1f per match)%n", totalThrowIns, totalThrowIns / Nd);
        System.out.printf("  Goal kicks:        %d (%.1f per match)%n", totalGoalKicks, totalGoalKicks / Nd);
        System.out.printf("  Free kicks:        %d (%.1f per match)%n", totalFreeKicks, totalFreeKicks / Nd);
        System.out.printf("  Penalties awarded: %d (%.2f per match)%n", totalPenaltiesAwarded, totalPenaltiesAwarded / Nd);
        if (totalPenaltiesAwarded > 0) {
            System.out.printf("  Penalties scored:   %d (%.0f%%)%n", totalPenaltiesScored, 100.0 * totalPenaltiesScored / totalPenaltiesAwarded);
        }

        // ── DISCIPLINE ──
        System.out.println("\n═══ 6. DISCIPLINE ═══");
        System.out.printf("  Fouls:             %d (%.1f per match)%n", totalFouls, totalFouls / Nd);
        System.out.printf("  Yellow cards:      %d (%.2f per match)%n", totalYellow, totalYellow / Nd);
        System.out.printf("  Red cards:         %d (%.2f per match)%n", totalRed, totalRed / Nd);

        // ── OFFSIDES ──
        System.out.println("\n═══ 7. OFFSIDES ═══");
        System.out.printf("  Total offsides:    %d (%.1f per match)%n", totalOffsides, totalOffsides / Nd);
        System.out.printf("  Offside retreats:  %d (%.1f per match)%n",
                homeOffsideRetreats + awayOffsideRetreats, (homeOffsideRetreats + awayOffsideRetreats) / Nd);

        // ── VAR ──
        int totalVarConfirmed = varOffsideConfirmed + varGoalConfirmed + varRedConfirmed + varPenaltyConfirmed;
        int totalVarOverturned = varOffsideOverturned + varGoalOverturned + varRedOverturned + varPenaltyOverturned;
        System.out.println("\n═══ 8. VAR ═══");
        System.out.printf("  Total VAR reviews: %d (%.1f per match)%n", totalVar, totalVar / Nd);
        System.out.printf("  Offside confirmed: %d | overturned: %d%n", varOffsideConfirmed, varOffsideOverturned);
        System.out.printf("  Goal confirmed:     %d | overturned: %d%n", varGoalConfirmed, varGoalOverturned);
        System.out.printf("  Red confirmed:      %d | overturned: %d%n", varRedConfirmed, varRedOverturned);
        System.out.printf("  Penalty confirmed:  %d | overturned: %d%n", varPenaltyConfirmed, varPenaltyOverturned);
        if (totalVarConfirmed + totalVarOverturned > 0) {
            System.out.printf("  Overturn rate:     %.0f%%%n", 100.0 * totalVarOverturned / (totalVarConfirmed + totalVarOverturned));
        }

        // ── DUELS & CHASES ──
        System.out.println("\n═══ 9. DUELS & CHASES ═══");
        int totalDuels = totalAerialDuels + totalTackleDuels + totalDribbleDuels;
        System.out.printf("  Total duels:       %d (%.1f per match)%n", totalDuels, totalDuels / Nd);
        System.out.printf("  Aerial duels:      %d (%.1f per match)%n", totalAerialDuels, totalAerialDuels / Nd);
        System.out.printf("  Tackle duels:      %d (%.1f per match)%n", totalTackleDuels, totalTackleDuels / Nd);
        System.out.printf("  Dribble duels:     %d (%.1f per match)%n", totalDribbleDuels, totalDribbleDuels / Nd);
        System.out.printf("  Chases:            %d (%.1f per match)%n", totalChases, totalChases / Nd);
        System.out.printf("  Carries:           %d (%.1f per match)%n", totalCarries, totalCarries / Nd);
        System.out.printf("  Blocks:            %d (%.1f per match)%n", totalBlocks, totalBlocks / Nd);
        System.out.printf("  Deflections:       %d (%.1f per match)%n", totalDeflections, totalDeflections / Nd);
        int totalInterceptions = homeInterceptions + awayInterceptions;
        System.out.printf("  Interceptions:     %d (%.1f per match)%n", totalInterceptions, totalInterceptions / Nd);

        // ── GOAL SOURCES ──
        System.out.println("\n═══ 10. GOAL SOURCES ═══");
        int goalsFromCrossT = homeGoalsFromCross + awayGoalsFromCross;
        int goalsFromCenterT = homeGoalsFromCenter + awayGoalsFromCenter;
        int goalsFromThruT = homeGoalsFromThru + awayGoalsFromThru;
        int goalsFromOpenT = homeGoalsFromOpenPlay + awayGoalsFromOpenPlay;
        int goalsFromPenaltyT = homeGoalsFromPenalty + awayGoalsFromPenalty;
        int goalsFromFreeKickT = homeGoalsFromFreeKick + awayGoalsFromFreeKick;
        int goalsFromCornerT = homeGoalsFromCorner + awayGoalsFromCorner;
        System.out.printf("  From cross:        %d (%.0f%%)%n", goalsFromCrossT, totalGoals > 0 ? 100.0 * goalsFromCrossT / totalGoals : 0);
        System.out.printf("  From center:       %d (%.0f%%)%n", goalsFromCenterT, totalGoals > 0 ? 100.0 * goalsFromCenterT / totalGoals : 0);
        System.out.printf("  From thru ball:    %d (%.0f%%)%n", goalsFromThruT, totalGoals > 0 ? 100.0 * goalsFromThruT / totalGoals : 0);
        System.out.printf("  From corner:       %d (%.0f%%)%n", goalsFromCornerT, totalGoals > 0 ? 100.0 * goalsFromCornerT / totalGoals : 0);
        System.out.printf("  From open play:    %d (%.0f%%)%n", goalsFromOpenT, totalGoals > 0 ? 100.0 * goalsFromOpenT / totalGoals : 0);
        System.out.printf("  From penalty:      %d (%.0f%%)%n", goalsFromPenaltyT, totalGoals > 0 ? 100.0 * goalsFromPenaltyT / totalGoals : 0);
        System.out.printf("  From free kick:    %d (%.0f%%)%n", goalsFromFreeKickT, totalGoals > 0 ? 100.0 * goalsFromFreeKickT / totalGoals : 0);

        // ── FREE KICK DETAILS ──
        int totalFkShotsOnGoal = homeFreeKickShotsOnGoal + awayFreeKickShotsOnGoal;
        int totalFkGoals = homeFreeKickGoals + awayFreeKickGoals;
        System.out.println("\n═══ 10b. FREE KICK DETAILS ═══");
        System.out.printf("  FK shots on goal:  %d (%.1f per match)%n", totalFkShotsOnGoal, totalFkShotsOnGoal / Nd);
        System.out.printf("  FK goals scored:   %d (%.1f per match)%n", totalFkGoals, totalFkGoals / Nd);
        if (totalFkShotsOnGoal > 0) {
            System.out.printf("  FK conversion:     %.0f%%%n", 100.0 * totalFkGoals / totalFkShotsOnGoal);
        }

        // ── CLEAN SHEETS ──
        System.out.println("\n═══ 11. CLEAN SHEETS ═══");
        System.out.printf("  HOME clean sheets: %d (%.0f%% of matches)%n", homeCleanSheets, 100.0 * homeCleanSheets / N);
        System.out.printf("  AWAY clean sheets: %d (%.0f%% of matches)%n", awayCleanSheets, 100.0 * awayCleanSheets / N);

        // ── PER-TEAM SUMMARY ──
        System.out.println("\n╔════════════════════════════════════════════════════════════════════════╗");
        System.out.println(  "║  PER-TEAM SUMMARY                                                   ║");
        System.out.println(  "╚════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  %-24s %6s %6s%n", "", "HOME", "AWAY");
        System.out.printf("  %-24s %6d %6d%n", "Goals", homeGoals, awayGoals);
        System.out.printf("  %-24s %6d %6d%n", "Shots", homeShots, awayShots);
        System.out.printf("  %-24s %6d %6d%n", "Shots on target", homeShotsOnTarget, awayShotsOnTarget);
        System.out.printf("  %-24s %6.0f%% %6.0f%%%n", "Shot accuracy",
                homeShots > 0 ? 100.0 * homeShotsOnTarget / homeShots : 0,
                awayShots > 0 ? 100.0 * awayShotsOnTarget / awayShots : 0);
        System.out.printf("  %-24s %6.0f%% %6.0f%%%n", "Conversion",
                homeShots > 0 ? 100.0 * homeGoals / homeShots : 0,
                awayShots > 0 ? 100.0 * awayGoals / awayShots : 0);
        System.out.printf("  %-24s %6d %6d%n", "Saves", homeSaves, awaySaves);
        System.out.printf("  %-24s %6d %6d%n", "Passes attempted", homePassesAttempted, awayPassesAttempted);
        System.out.printf("  %-24s %6d %6d%n", "Passes completed", homePassesCompleted, awayPassesCompleted);
        System.out.printf("  %-24s %6.0f%% %6.0f%%%n", "Pass accuracy",
                homePassesAttempted > 0 ? 100.0 * homePassesCompleted / homePassesAttempted : 0,
                awayPassesAttempted > 0 ? 100.0 * awayPassesCompleted / awayPassesAttempted : 0);
        System.out.printf("  %-24s %6d %6d%n", "Air passes", homeAirPasses, awayAirPasses);
        System.out.printf("  %-24s %6d %6d%n", "Ground passes", homeGroundPasses, awayGroundPasses);
        System.out.printf("  %-24s %6d %6d%n", "Crosses", homeCrosses, awayCrosses);
        System.out.printf("  %-24s %6d %6d%n", "Crosses completed", homeCrossesCompleted, awayCrossesCompleted);
        System.out.printf("  %-24s %6d %6d%n", "Centers", homeCenters, awayCenters);
        System.out.printf("  %-24s %6d %6d%n", "Centers completed", homeCentersCompleted, awayCentersCompleted);
        System.out.printf("  %-24s %6d %6d%n", "Carries", homeCarries, awayCarries);
        System.out.printf("  %-24s %6d %6d%n", "Dribbles", homeDribbles, awayDribbles);
        System.out.printf("  %-24s %6d %6d%n", "Duels (aerial)", homeAerialDuels, awayAerialDuels);
        System.out.printf("  %-24s %6d %6d%n", "Duels (tackle)", homeTackleDuels, awayTackleDuels);
        System.out.printf("  %-24s %6d %6d%n", "Duels (dribble)", homeDribbleDuels, awayDribbleDuels);
        System.out.printf("  %-24s %6d %6d%n", "Chases", homeChases, awayChases);
        System.out.printf("  %-24s %6d %6d%n", "Blocks", homeBlocks, awayBlocks);
        System.out.printf("  %-24s %6d %6d%n", "Deflections", homeDeflections, awayDeflections);
        System.out.printf("  %-24s %6d %6d%n", "Interceptions", homeInterceptions, awayInterceptions);
        System.out.printf("  %-24s %6d %6d%n", "Clearances", homeClearances, awayClearances);
        System.out.printf("  %-24s %6d %6d%n", "Corners", homeCorners, awayCorners);
        System.out.printf("  %-24s %6d %6d%n", "Throw-ins", homeThrowIns, awayThrowIns);
        System.out.printf("  %-24s %6d %6d%n", "Goal kicks", homeGoalKicks, awayGoalKicks);
        System.out.printf("  %-24s %6d %6d%n", "Free kicks", homeFreeKicks, awayFreeKicks);
        System.out.printf("  %-24s %6d %6d%n", "Penalties awarded", homePenaltiesAwarded, awayPenaltiesAwarded);
        System.out.printf("  %-24s %6d %6d%n", "Penalties scored", homePenaltiesScored, awayPenaltiesScored);
        System.out.printf("  %-24s %6d %6d%n", "Penalties missed", homePenaltiesMissed, awayPenaltiesMissed);
        System.out.printf("  %-24s %6d %6d%n", "Offsides", homeOffsides, awayOffsides);
        System.out.printf("  %-24s %6d %6d%n", "Offside retreats", homeOffsideRetreats, awayOffsideRetreats);
        System.out.printf("  %-24s %6d %6d%n", "Fouls", homeFouls, awayFouls);
        System.out.printf("  %-24s %6d %6d%n", "Yellow cards", homeYellowCards, awayYellowCards);
        System.out.printf("  %-24s %6d %6d%n", "Red cards", homeRedCards, awayRedCards);
        System.out.printf("  %-24s %6d %6d%n", "Injuries", homeInjuries, awayInjuries);
        System.out.printf("  %-24s %6d %6d%n", "Substitutions", homeSubstitutions, awaySubstitutions);
        System.out.printf("  %-24s %6d %6d%n", "VAR reviews", homeVarReviews, awayVarReviews);
        System.out.printf("  %-24s %6d %6d%n", "Clean sheets", homeCleanSheets, awayCleanSheets);
        System.out.printf("  %-24s %6d %6d%n", "Pass loose (missed)", homePassLoose, awayPassLoose);
        System.out.printf("  %-24s %6d %6d%n", "Possession changes", homePossessionChanges, awayPossessionChanges);

        System.out.println("\n  --- Goal Sources ---");
        System.out.printf("  %-24s %6d %6d%n", "From cross", homeGoalsFromCross, awayGoalsFromCross);
        System.out.printf("  %-24s %6d %6d%n", "From center", homeGoalsFromCenter, awayGoalsFromCenter);
        System.out.printf("  %-24s %6d %6d%n", "From thru ball", homeGoalsFromThru, awayGoalsFromThru);
        System.out.printf("  %-24s %6d %6d%n", "From open play", homeGoalsFromOpenPlay, awayGoalsFromOpenPlay);
        System.out.printf("  %-24s %6d %6d%n", "From penalty", homeGoalsFromPenalty, awayGoalsFromPenalty);
        System.out.printf("  %-24s %6d %6d%n", "From free kick", homeGoalsFromFreeKick, awayGoalsFromFreeKick);
        System.out.printf("  %-24s %6d %6d%n", "From corner", homeGoalsFromCorner, awayGoalsFromCorner);
        System.out.printf("  %-24s %6d %6d%n", "FK shots on goal", homeFreeKickShotsOnGoal, awayFreeKickShotsOnGoal);
        System.out.printf("  %-24s %6d %6d%n", "FK goals scored", homeFreeKickGoals, awayFreeKickGoals);

        // ── SCORE DISTRIBUTION ──
        System.out.println("\n═══ SCORE DISTRIBUTION ═══");
        Map<String, Integer> scoreDist = new TreeMap<>();
        for (String s : scorelines) scoreDist.merge(s, 1, Integer::sum);
        scoreDist.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(12)
                .forEach(e -> System.out.printf("  %-6s %4d (%.0f%%)%n",
                        e.getKey(), e.getValue(), 100.0 * e.getValue() / N));

        System.out.println("\n════════════════════════════════════════════════════════════════════════");
        System.out.println("End of report.");
    }
}
