package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.*;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class MatchQAIntegrationTest {

    private static final int MATCHES_TO_RUN = 10;
    private static final int MAX_MINUTES_TO_TRACK = 10;

    record PlayerPositionAudit(
            long playerId,
            String playerName,
            String teamSide,
            String position,
            double desiredX,
            double desiredY,
            double actualX,
            double actualY,
            String intent,
            String reason,
            double distanceFromDesired
    ) {}

    record BallCarrierAudit(
            int minute,
            int tick,
            long carrierId,
            String carrierName,
            String teamSide,
            double carrierX,
            double carrierY,
            double ballX,
            double ballY,
            String decision
    ) {}

    record MinuteSnapshot(
            int minute,
            int tick,
            double ballX,
            double ballY,
            BallCarrierAudit carrierAudit,
            List<PlayerPositionAudit> playerAudits
    ) {}

    record MatchSummary(
            int matchNumber,
            int homeGoals,
            int awayGoals,
            double homePossession,
            double awayPossession,
            int homeShots,
            int awayShots,
            int homeCorners,
            int awayCorners,
            int homeFouls,
            int awayFouls,
            int homeYellowCards,
            int awayYellowCards,
            int homeRedCards,
            int awayRedCards,
            int totalEvents,
            int totalTicks,
            List<MinuteSnapshot> snapshots
    ) {}

    @Test
    void qaTenMatchesPositioningAndStats() {
        List<MatchSummary> summaries = new ArrayList<>();

        for (int m = 0; m < MATCHES_TO_RUN; m++) {
            summaries.add(runSingleMatchQA(m + 1));
        }

        printAggregateReport(summaries);
    }

    private MatchSummary runSingleMatchQA(int matchNumber) {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("QA Home " + matchNumber, "QA Away " + matchNumber);
        MatchResult result = orchestrator.simulate(matchId);

        List<MinuteSnapshot> snapshots = new ArrayList<>();

        // We need to re-run the simulation to capture per-minute snapshots
        // because MatchResult only stores the final state + tick history.
        // Instead, we'll approximate from tickHistory.
        for (TickSnapshot tick : result.tickHistory()) {
            if (tick.tick() % 120 == 0 && tick.tick() > 0) {
                int minute = tick.tick() / 120;
                if (minute > MAX_MINUTES_TO_TRACK) break;

                // Find closest non-GK player to ball as carrier proxy
                PlayerSnapshot closestToBall = null;
                double closestDist = Double.MAX_VALUE;
                BallState ball = tick.ball();
                for (PlayerSnapshot p : tick.players()) {
                    if (p.position() == Position.GK) continue;
                    double dist = p.distanceToPoint(ball.x(), ball.y());
                    if (dist < closestDist) {
                        closestDist = dist;
                        closestToBall = p;
                    }
                }

                BallCarrierAudit carrierAudit = null;
                if (closestToBall != null && closestDist < 5.0) {
                    String decision = heuristicDecision(closestToBall, ball.x(), ball.y());
                    carrierAudit = new BallCarrierAudit(
                            minute, tick.tick(),
                            closestToBall.playerId(), closestToBall.name(), closestToBall.teamSide(),
                            closestToBall.x(), closestToBall.y(),
                            ball.x(), ball.y(),
                            decision
                    );
                }

                List<PlayerPositionAudit> audits = new ArrayList<>();
                for (PlayerSnapshot snap : tick.players()) {
                    double[] desired = approximateDesiredPosition(snap, ball.x(), ball.y());
                    double distFromDesired = snap.distanceToPoint(desired[0], desired[1]);

                    String reason = deriveReason(snap, distFromDesired, desired);

                    audits.add(new PlayerPositionAudit(
                            snap.playerId(), snap.name(), snap.teamSide(), snap.position().name(),
                            desired[0], desired[1],
                            snap.x(), snap.y(),
                            snap.intent().name(), reason,
                            distFromDesired
                    ));
                }

                snapshots.add(new MinuteSnapshot(minute, tick.tick(), ball.x(), ball.y(), carrierAudit, audits));
            }
        }

        return new MatchSummary(
                matchNumber,
                result.homeGoals(), result.awayGoals(),
                result.homePossession(), result.awayPossession(),
                result.homeShots(), result.awayShots(),
                result.homeCorners(), result.awayCorners(),
                result.homeFouls(), result.awayFouls(),
                result.homeYellowCards(), result.awayYellowCards(),
                result.homeRedCards(), result.awayRedCards(),
                result.events().size(), result.totalTicks(),
                snapshots
        );
    }

    private String deriveReason(PlayerSnapshot snap, double _distFromDesired, double[] _desired) {
        return switch (snap.intent()) {
            case CHASE_BALL -> "CHASE_BALL";
            case PRESS -> "PRESS_CARRIER";
            case MARK -> "MARK_OPPONENT";
            case SUPPORT -> "SUPPORT_PASS_TARGET";
            case MAKE_RUN -> "MAKE_FORWARD_RUN";
            case HOLD_POSITION -> "HOLD_FOR_PASS";
            case CARRY_BALL -> "CARRYING";
            case RETURN_TO_SHAPE -> "OFFSIDE_RETREAT";
            default -> "MOVING_TO_SHAPE";
        };
    }

    private String heuristicDecision(PlayerSnapshot carrier, double _ballX, double _ballY) {
        double distToGoal = carrier.teamSide().equals("HOME")
                ? carrier.distanceToPoint(96.0, 50.0)
                : carrier.distanceToPoint(4.0, 50.0);

        if (distToGoal < 10.0) return "SHOOT";
        else if (distToGoal < 25.0) return "PASS_OR_SHOOT";
        else return "PASS_OR_CARRY";
    }

    private double[] approximateDesiredPosition(PlayerSnapshot snap, double ballX, double ballY) {
        return switch (snap.position()) {
            case GK -> snap.teamSide().equals("HOME") ? new double[]{5.0, 50.0} : new double[]{95.0, 50.0};
            case DEF -> {
                double baseX = snap.teamSide().equals("HOME") ? 20.0 : 80.0;
                yield new double[]{baseX + (ballX - 50.0) * 0.15, snap.y()};
            }
            case MID -> {
                double baseX = snap.teamSide().equals("HOME") ? 40.0 : 60.0;
                yield new double[]{baseX + (ballX - 50.0) * 0.25, snap.y()};
            }
            case WNG -> {
                double baseX = snap.teamSide().equals("HOME") ? 35.0 : 65.0;
                double baseY = snap.y() < 50 ? 15.0 : 85.0;
                yield new double[]{baseX + (ballX - 50.0) * 0.2, baseY};
            }
            case ATT -> {
                double baseX = snap.teamSide().equals("HOME") ? 75.0 : 25.0;
                yield new double[]{baseX + (ballX - 50.0) * 0.3, 50.0};
            }
        };
    }

    private void printAggregateReport(List<MatchSummary> summaries) {
        System.out.println("\n========================================");
        System.out.println("   10-MATCH QA AGGREGATE REPORT");
        System.out.println("========================================");

        // --- Match stats ---
        int totalHomeGoals = 0, totalAwayGoals = 0;
        double totalHomePoss = 0, totalAwayPoss = 0;
        int totalHomeShots = 0, totalAwayShots = 0;
        int totalHomeCorners = 0, totalAwayCorners = 0;
        int totalHomeFouls = 0, totalAwayFouls = 0;
        int totalHomeYC = 0, totalAwayYC = 0;
        int totalHomeRC = 0, totalAwayRC = 0;
        int totalEvents = 0, totalTicks = 0;

        // --- Positioning ---
        List<Double> allDistances = new ArrayList<>();
        Map<String, Long> intentCounts = new HashMap<>();
        Map<String, Long> reasonCounts = new HashMap<>();
        Map<String, Integer> deviationsByReason = new HashMap<>();

        // --- Ball position ---
        List<Double> ballXs = new ArrayList<>();
        List<Double> ballYs = new ArrayList<>();

        for (MatchSummary s : summaries) {
            totalHomeGoals += s.homeGoals();
            totalAwayGoals += s.awayGoals();
            totalHomePoss += s.homePossession();
            totalAwayPoss += s.awayPossession();
            totalHomeShots += s.homeShots();
            totalAwayShots += s.awayShots();
            totalHomeCorners += s.homeCorners();
            totalAwayCorners += s.awayCorners();
            totalHomeFouls += s.homeFouls();
            totalAwayFouls += s.awayFouls();
            totalHomeYC += s.homeYellowCards();
            totalAwayYC += s.awayYellowCards();
            totalHomeRC += s.homeRedCards();
            totalAwayRC += s.awayRedCards();
            totalEvents += s.totalEvents();
            totalTicks += s.totalTicks();

            for (MinuteSnapshot snap : s.snapshots()) {
                ballXs.add(snap.ballX());
                ballYs.add(snap.ballY());

                if (snap.carrierAudit() != null) {
                    BallCarrierAudit ca = snap.carrierAudit();
                    intentCounts.merge("CARRIER_" + ca.decision(), 1L, Long::sum);
                }

                for (PlayerPositionAudit audit : snap.playerAudits()) {
                    allDistances.add(audit.distanceFromDesired());
                    intentCounts.merge(audit.intent(), 1L, Long::sum);
                    reasonCounts.merge(audit.reason(), 1L, Long::sum);

                    if (audit.distanceFromDesired() > 5.0) {
                        deviationsByReason.merge(audit.reason(), 1, Integer::sum);
                    }
                }
            }
        }

        int n = summaries.size();

        System.out.println("\n--- MATCH STATISTICS (AVERAGE) ---");
        System.out.printf("Goals: HOME %.1f - AWAY %.1f (total %.1f)%n",
                (double) totalHomeGoals / n, (double) totalAwayGoals / n,
                (double) (totalHomeGoals + totalAwayGoals) / n);
        System.out.printf("Possession: HOME %.1f%% - AWAY %.1f%%%n",
                totalHomePoss / n, totalAwayPoss / n);
        System.out.printf("Shots: HOME %.1f - AWAY %.1f (total %.1f)%n",
                (double) totalHomeShots / n, (double) totalAwayShots / n,
                (double) (totalHomeShots + totalAwayShots) / n);
        System.out.printf("Corners: HOME %.1f - AWAY %.1f (total %.1f)%n",
                (double) totalHomeCorners / n, (double) totalAwayCorners / n,
                (double) (totalHomeCorners + totalAwayCorners) / n);
        System.out.printf("Fouls: HOME %.1f - AWAY %.1f (total %.1f)%n",
                (double) totalHomeFouls / n, (double) totalAwayFouls / n,
                (double) (totalHomeFouls + totalAwayFouls) / n);
        System.out.printf("Cards: HOME Y%.1f/R%.1f - AWAY Y%.1f/R%.1f%n",
                (double) totalHomeYC / n, (double) totalHomeRC / n,
                (double) totalAwayYC / n, (double) totalAwayRC / n);
        System.out.printf("Events per match: %.1f%n", (double) totalEvents / n);
        System.out.printf("Ticks per match: %.1f%n", (double) totalTicks / n);

        System.out.println("\n--- BALL POSITION ANALYSIS ---");
        double avgBallX = ballXs.stream().mapToDouble(d -> d).average().orElse(50);
        double avgBallY = ballYs.stream().mapToDouble(d -> d).average().orElse(50);
        System.out.printf("Avg ball position: (%.1f, %.1f)%n", avgBallX, avgBallY);
        System.out.printf("Ball in HOME half: %.1f%%%n", 100.0 * ballXs.stream().filter(x -> x > 50).count() / ballXs.size());
        System.out.printf("Ball in AWAY half: %.1f%%%n", 100.0 * ballXs.stream().filter(x -> x < 50).count() / ballXs.size());

        // Ball position by minute
        System.out.println("\nBall position by minute (first 10):");
        Map<Integer, List<Double>> ballXByMinute = new HashMap<>();
        Map<Integer, List<Double>> ballYByMinute = new HashMap<>();
        for (MatchSummary s : summaries) {
            for (MinuteSnapshot snap : s.snapshots()) {
                ballXByMinute.computeIfAbsent(snap.minute(), k -> new ArrayList<>()).add(snap.ballX());
                ballYByMinute.computeIfAbsent(snap.minute(), k -> new ArrayList<>()).add(snap.ballY());
            }
        }
        for (int min = 1; min <= MAX_MINUTES_TO_TRACK; min++) {
            List<Double> xs = ballXByMinute.getOrDefault(min, List.of());
            List<Double> ys = ballYByMinute.getOrDefault(min, List.of());
            if (!xs.isEmpty()) {
                System.out.printf("  Min %2d: ball at (%.1f, %.1f)%n",
                        min, xs.stream().mapToDouble(d -> d).average().orElse(50),
                        ys.stream().mapToDouble(d -> d).average().orElse(50));
            }
        }

        System.out.println("\n--- PLAYER POSITIONING ANALYSIS ---");
        double avgDist = allDistances.stream().mapToDouble(d -> d).average().orElse(0);
        double maxDist = allDistances.stream().mapToDouble(d -> d).max().orElse(0);
        long onTarget = allDistances.stream().filter(d -> d < 3.0).count();
        long nearTarget = allDistances.stream().filter(d -> d < 5.0).count();
        long farFromTarget = allDistances.stream().filter(d -> d > 8.0).count();

        System.out.printf("Avg distance from desired: %.2f units%n", avgDist);
        System.out.printf("Max distance from desired: %.2f units%n", maxDist);
        System.out.printf("On target (d < 3.0): %d / %d (%.1f%%)%n",
                onTarget, allDistances.size(), 100.0 * onTarget / allDistances.size());
        System.out.printf("Near target (d < 5.0): %d / %d (%.1f%%)%n",
                nearTarget, allDistances.size(), 100.0 * nearTarget / allDistances.size());
        System.out.printf("Far from target (d > 8.0): %d / %d (%.1f%%)%n",
                farFromTarget, allDistances.size(), 100.0 * farFromTarget / allDistances.size());

        System.out.println("\nIntent distribution:");
        intentCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-25s %4d%n", e.getKey(), e.getValue()));

        System.out.println("\nDeviation reasons (when d > 5.0):");
        deviationsByReason.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-25s %4d%n", e.getKey(), e.getValue()));

        System.out.println("\nAll deviation reasons:");
        reasonCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-25s %4d%n", e.getKey(), e.getValue()));

        System.out.println("\n--- REALISM CHECKS ---");
        boolean possessionOk = totalHomePoss / n >= 35 && totalHomePoss / n <= 65;
        boolean shotsOk = (totalHomeShots + totalAwayShots) / n >= 8 && (totalHomeShots + totalAwayShots) / n <= 30;
        boolean cornersOk = (totalHomeCorners + totalAwayCorners) / n >= 3;
        boolean foulsOk = (totalHomeFouls + totalAwayFouls) / n >= 8;

        System.out.printf("  Possession balance (35-65%%): %s (%.1f%%)%n",
                possessionOk ? "PASS" : "WARN", totalHomePoss / n);
        System.out.printf("  Total shots (8-30): %s (%.1f)%n",
                shotsOk ? "PASS" : "WARN", (double) (totalHomeShots + totalAwayShots) / n);
        System.out.printf("  Corners >= 3: %s (%.1f)%n",
                cornersOk ? "PASS" : "WARN", (double) (totalHomeCorners + totalAwayCorners) / n);
        System.out.printf("  Fouls >= 8: %s (%.1f)%n",
                foulsOk ? "PASS" : "WARN", (double) (totalHomeFouls + totalAwayFouls) / n);

        // Assertions
        assertTrue(totalTicks > 0, "Should have ticks");
        assertTrue(totalEvents > 0, "Should have events");
        assertTrue(possessionOk, "Possession should be roughly balanced");
        assertTrue(shotsOk, "Shots should be in realistic range");
    }
}
