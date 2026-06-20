package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.*;

public class FullMatchDetailedDiagnostic {

    private static final double PITCH_LEFT = 4.0;
    private static final double PITCH_RIGHT = 96.0;
    private static final double PITCH_TOP = 4.0;
    private static final double PITCH_BOTTOM = 94.0;

    @Test
    void comprehensiveMatchAnalysis() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        List<MatchEvent> events = result.events();

        Map<Long, String> playerNames = new HashMap<>();
        if (!ticks.isEmpty()) {
            for (var ps : ticks.get(0).players()) {
                playerNames.put(ps.playerId(), ps.name() + "(" + ps.teamSide() + "," + ps.position() + ")");
            }
        }

        System.out.println("=" .repeat(120));
        System.out.println("  COMPREHENSIVE MATCH ANALYSIS");
        System.out.println("=" .repeat(120));

        // ──────────────────────────────────────────────────
        // 1. BALL PHYSICS — does the ball ever leave the pitch?
        // ──────────────────────────────────────────────────
        System.out.println();
        System.out.println("  BALL PHYSICS");
        System.out.println("  ────────────");
        double ballMinX = 999, ballMaxX = -999, ballMinY = 999, ballMaxY = -999;
        int outOfBoundsX = 0, outOfBoundsY = 0;
        double ballTotalDist = 0, bxPrev = -1, byPrev = -1;
        for (var t : ticks) {
            double bx = t.ball().x(), by = t.ball().y();
            if (bx < ballMinX) ballMinX = bx;
            if (bx > ballMaxX) ballMaxX = bx;
            if (by < ballMinY) ballMinY = by;
            if (by > ballMaxY) ballMaxY = by;
            if (bx < -2 || bx > 102) outOfBoundsX++;
            if (by < -2 || by > 102) outOfBoundsY++;
            if (bxPrev >= 0) ballTotalDist += Math.sqrt(Math.pow(bx - bxPrev, 2) + Math.pow(by - byPrev, 2));
            bxPrev = bx; byPrev = by;
        }
        System.out.printf("  Ball X range: %.1f to %.1f (pitch=%.0f-%.0f)%n", ballMinX, ballMaxX, PITCH_LEFT, PITCH_RIGHT);
        System.out.printf("  Ball Y range: %.1f to %.1f (pitch=%.0f-%.0f)%n", ballMinY, ballMaxY, PITCH_TOP, PITCH_BOTTOM);
        System.out.printf("  Out-of-bounds (beyond ±2 of pitch): X=%d, Y=%d%n", outOfBoundsX, outOfBoundsY);
        System.out.printf("  Total ball movement: %.0f units%n", ballTotalDist);

        // ──────────────────────────────────────────────────
        // 2. SET PIECE EVENTS — do they exist and where?
        // ──────────────────────────────────────────────────
        System.out.println();
        System.out.println("  SET PIECES & STOPPAGES");
        System.out.println("  ─────────────────────");
        var corners = events.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.CORNER).toList();
        var throwIns = events.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.THROW_IN).toList();
        var goalKicks = events.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.GOAL_KICK).toList();
        var freeKicks = events.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.FREE_KICK).toList();
        var goals = events.stream().filter(e -> e instanceof GoalEvent).toList();
        var offsides = events.stream().filter(e -> e instanceof OffsideEvent).toList();

        System.out.printf("  CORNERS: %d%n", corners.size());
        System.out.printf("  THROW-INS: %d%n", throwIns.size());
        System.out.printf("  GOAL KICKS: %d%n", goalKicks.size());
        System.out.printf("  FREE KICKS: %d%n", freeKicks.size());
        System.out.printf("  GOALS: %d%n", goals.size());
        System.out.printf("  OFFSIDES: %d%n", offsides.size());

        if (!corners.isEmpty()) {
            System.out.println();
            System.out.println("  Corner locations:");
            for (var e : corners) {
                var sp = (SetPieceEvent) e;
                String at = "";
                if (sp.x() < 10 && sp.y() < 10) at = "LEFT_NEAR";
                else if (sp.x() < 10 && sp.y() > 90) at = "LEFT_FAR";
                else if (sp.x() > 90 && sp.y() < 10) at = "RIGHT_NEAR";
                else if (sp.x() > 90 && sp.y() > 90) at = "RIGHT_FAR";
                System.out.printf("    min=%d  (%s)  x=%.0f y=%.0f  %s%n", sp.minute(), sp.teamSide(), sp.x(), sp.y(), at);
            }
        }

        // ──────────────────────────────────────────────────
        // 3. PER-PLAYER MOVEMENT ANALYSIS
        // ──────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  PER-PLAYER MOVEMENT ANALYSIS");
        System.out.println("=" .repeat(120));

        // Collect per-player stats
        record PlayerMov(long id, String name, String team, String pos,
                         double minX, double maxX, double minY, double maxY,
                         double totalDist, int stationaryTicks, int totalTicks,
                         double avgSpeed, double maxSpeed) {}

        List<PlayerMov> playerStats = new ArrayList<>();
        for (int pi = 0; pi < ticks.get(0).players().size(); pi++) {
            long pid = ticks.get(0).players().get(pi).playerId();
            String pInfo = playerNames.get(pid);
            if (pInfo == null) continue;
            String[] parts = pInfo.split("\\(");
            String pName = parts[0];
            String pRest = parts.length > 1 ? parts[1].replace(")", "") : "";
            String[] restParts = pRest.split(",");
            String pTeam = restParts.length > 0 ? restParts[0] : "?";
            String pPos = restParts.length > 1 ? restParts[1] : "?";

            double mnX = 999, mxX = -999, mnY = 999, mxY = -999;
            double totDist = 0;
            int still = 0, total = 0;
            double lx = -1, ly = -1;
            double maxSpd = 0;

            for (var t : ticks) {
                if (t.minute() > 90) break;
                var snap = t.players().stream().filter(p -> p.playerId() == pid).findFirst();
                if (snap.isEmpty()) continue;
                double x = snap.get().x(), y = snap.get().y();
                if (x < mnX) mnX = x; if (x > mxX) mxX = x;
                if (y < mnY) mnY = y; if (y > mxY) mxY = y;
                if (lx >= 0) {
                    double d = Math.sqrt(Math.pow(x - lx, 2) + Math.pow(y - ly, 2));
                    totDist += d;
                    if (d > maxSpd) maxSpd = d;
                    if (d < 0.05) still++;
                }
                lx = x; ly = y;
                total++;
            }

            double avgSpd = total > 0 ? totDist / total : 0;
            playerStats.add(new PlayerMov(pid, pName, pTeam, pPos,
                mnX, mxX, mnY, mxY, totDist, still, total, avgSpd, maxSpd));
        }

        // Print table
        System.out.println();
        System.out.printf("  %-5s %-20s %-6s %-5s %8s %8s %8s %8s %8s %5s %8s%n",
            "ID", "Name", "CTeam", "Pos", "X min", "X max", "Y min", "Y max", "Dist", "Still%", "Max/tick");
        System.out.println("  " + "-".repeat(110));

        for (var ps : playerStats) {
            double stillPct = ps.totalTicks > 0 ? 100.0 * ps.stationaryTicks / ps.totalTicks : 0;
            System.out.printf("  %-5d %-20s %-6s %-5s %8.1f %8.1f %8.1f %8.1f %8.0f %5.0f%% %8.3f%n",
                ps.id, ps.name, ps.team, ps.pos,
                ps.minX, ps.maxX, ps.minY, ps.maxY,
                ps.totalDist, stillPct, ps.maxSpeed);
        }

        // ──────────────────────────────────────────────────
        // 4. SENSIBILITY CHECKS
        // ──────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  SENSIBILITY CHECKS");
        System.out.println("=" .repeat(120));

        // 4a. CPlayer position sanity
        System.out.println();
        System.out.println("  4a. POSITIONAL SANITY");
        System.out.println("  " + "-".repeat(60));
        int failGkX = 0, failDefAttHalf = 0, failDefInOwnBox = 0, failAttInOwnBox = 0;
        for (var ps : playerStats) {
            if ("GK".equals(ps.pos)) {
                if ("HOME".equals(ps.team) && ps.maxX > 50) {
                    System.out.printf("  ❌ HOME GK %s went to opponent half! maxX=%.1f%n", ps.name, ps.maxX);
                    failGkX++;
                }
                if ("AWAY".equals(ps.team) && ps.minX < 50) {
                    System.out.printf("  ❌ AWAY GK %s went to opponent half! minX=%.1f%n", ps.name, ps.minX);
                    failGkX++;
                }
            }
            // Defender position sanity
            if ("DEF".equals(ps.pos)) {
                if ("HOME".equals(ps.team) && ps.maxX > 95) {
                    System.out.printf("  ❌ HOME DEF %s went beyond opponent goal! maxX=%.1f%n", ps.name, ps.maxX);
                }
                if ("AWAY".equals(ps.team) && ps.minX < 5) {
                    System.out.printf("  ❌ AWAY DEF %s went beyond own goal! minX=%.1f%n", ps.name, ps.minX);
                }
            }
        }
        if (failGkX == 0) System.out.println("  ✅ All GKs stayed in their own half");

        // 4b. Ball out of play
        System.out.println();
        System.out.println("  4b. BALL OUT OF PLAY");
        System.out.println("  " + "-".repeat(60));
        System.out.printf("  Corners: %d, Throw-ins: %d, Goal kicks: %d%n", corners.size(), throwIns.size(), goalKicks.size());
        if (corners.size() > 0) System.out.println("  ✅ Ball DOES go out for corners");
        else System.out.println("  ❌ No corners — ball never reaches end line?");
        if (throwIns.size() > 0) System.out.println("  ✅ Ball DOES go out for throw-ins");
        else System.out.println("  ❌ No throw-ins — ball never goes over sideline?");
        if (goalKicks.size() > 0) System.out.println("  ✅ Ball DOES go for goal kicks");
        else System.out.println("  ❌ No goal kicks — ball never goes behind goal?");

        // 4c. CPlayer reset after stoppages
        System.out.println();
        System.out.println("  4c. PLAYER RESET AFTER STOPPAGES");
        System.out.println("  " + "-".repeat(60));

        var stoppages = events.stream()
            .filter(e -> e instanceof SetPieceEvent || e instanceof OffsideEvent || e instanceof GoalEvent || e instanceof PenaltyEvent)
            .toList();

        int resetsOk = 0, resetsChecked = 0;
        for (var e : stoppages) {
            int stopTick = e.tick();
            // Find a tick ~60 ticks after the stoppage (about 1 second later at 60 TPM)
            TickSnapshot after = null;
            for (var t : ticks) {
                if (t.tick() >= stopTick + 60) { after = t; break; }
            }
            if (after == null) continue;
            resetsChecked++;

            var homeGk = after.players().stream().filter(p -> "HOME".equals(p.teamSide()) && p.position() == Position.GK).findFirst();
            var awayGk = after.players().stream().filter(p -> "AWAY".equals(p.teamSide()) && p.position() == Position.GK).findFirst();
            if (homeGk.isEmpty() || awayGk.isEmpty()) continue;

            boolean homeGkOk = homeGk.get().x() < 20;
            boolean awayGkOk = awayGk.get().x() > 80;
            if (homeGkOk && awayGkOk) resetsOk++;
        }

        System.out.printf("  GK back in position after stoppage: %d/%d (%.0f%%)%n",
            resetsOk, resetsChecked, resetsChecked > 0 ? 100.0 * resetsOk / resetsChecked : 0);

        // 4d. Same-line player proximity (formation cohesion)
        System.out.println();
        System.out.println("  4d. FORMATION COHESION (same-line X spread)");
        System.out.println("  " + "-".repeat(60));

        for (String team : List.of("HOME", "AWAY")) {
            System.out.printf("  %s:%n", team);
            for (String posName : List.of("DEF", "MID", "ATT")) {
                var playersOfLine = playerStats.stream()
                    .filter(p -> p.team.equals(team) && p.pos.equals(posName))
                    .toList();
                if (playersOfLine.size() < 2) continue;
                double maxSpread = 0;
                // Track the widest spread between any two players of the same line
                for (int pi = 0; pi < ticks.size(); pi += 60) {
                    var t = ticks.get(pi);
                    if (t.minute() > 90) break;
                    var lineXs = t.players().stream()
                        .filter(p -> p.teamSide().equals(team) && p.position().name().equals(posName))
                        .mapToDouble(PlayerSnapshot::x)
                        .sorted()
                        .toArray();
                    if (lineXs.length >= 2) {
                        double spread = lineXs[lineXs.length - 1] - lineXs[0];
                        if (spread > maxSpread) maxSpread = spread;
                    }
                }
                System.out.printf("    %s: max X spread between players=%.1f units", posName, maxSpread);
                if (maxSpread > 40) System.out.print(" ⚠️  very wide");
                else if (maxSpread < 3) System.out.print(" ⚠️  very narrow (clustered?)");
                System.out.println();
            }
        }

        // 4e. No-teleportation check
        System.out.println();
        System.out.println("  4e. TELEPORTATION CHECK");
        System.out.println("  " + "-".repeat(60));
        double globalMaxDist = 0;
        for (var ps : playerStats) {
            if (ps.maxSpeed > globalMaxDist) globalMaxDist = ps.maxSpeed;
        }
        System.out.printf("  Max distance any player moved in a single tick: %.3f%n", globalMaxDist);
        System.out.printf("  %s (threshold: 1 tick = ~%d match-seconds at 60 TPM)%n",
            globalMaxDist < 14 ? "✅ No teleportation" : "⚠️  Possible teleportation", 1);

        // 4f. CPlayer movement range by position
        System.out.println();
        System.out.println("  4f. POSITION-APPROPRIATE MOVEMENT RANGE");
        System.out.println("  " + "-".repeat(60));
        for (var ps : playerStats) {
            double xRange = ps.maxX - ps.minX;
            double yRange = ps.maxY - ps.minY;
            boolean sensible = true;
            String note = "";
            if ("GK".equals(ps.pos)) {
                sensible = xRange < 30 && yRange < 30;
                if (!sensible) note = " ⚠️  GK covers too much ground!";
            } else if ("DEF".equals(ps.pos)) {
                if (xRange > 70) note = " ⚠️  DEF covers huge X range";
            } else if ("ATT".equals(ps.pos)) {
                if (xRange < 15) note = " ⚠️  ATT barely moves in X";
            }
            System.out.printf("  %-20s %-6s %-5s  X=%.0f-%.0f(%.0f) Y=%.0f-%.0f(%.0f)  Dist=%.0f%s%n",
                ps.name, ps.team, ps.pos,
                ps.minX, ps.maxX, xRange,
                ps.minY, ps.maxY, yRange,
                ps.totalDist, note);
        }

        // ──────────────────────────────────────────────────
        // 5. FINAL MATCH RESULT
        // ──────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  FINAL RESULT");
        System.out.println("=" .repeat(120));
        int homeG = (int) events.stream().filter(e -> e instanceof GoalEvent g && "HOME".equals(g.teamSide())).count();
        int awayG = (int) events.stream().filter(e -> e instanceof GoalEvent g && "AWAY".equals(g.teamSide())).count();
        System.out.printf("  HOME %d - %d AWAY%n", homeG, awayG);
        System.out.printf("  Events: goals=%d, corners=%d, throw-ins=%d, goal-kicks=%d, free-kicks=%d, offsides=%d, cards=%d%n",
            goals.size(), corners.size(), throwIns.size(), goalKicks.size(), freeKicks.size(), offsides.size(),
            events.stream().filter(e -> e instanceof CardEvent).count());

        System.out.println();
        System.out.println("  ✓ Comprehensive diagnostic complete.");
    }
}
