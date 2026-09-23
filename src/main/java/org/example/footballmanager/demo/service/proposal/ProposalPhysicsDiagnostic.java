package org.example.footballmanager.demo.service.proposal;

import org.example.footballmanager.demo.service.proposal.engine.BallPhysicsEngine;
import org.example.footballmanager.demo.service.proposal.engine.MatchOrchestrator;
import org.example.footballmanager.demo.service.proposal.engine.MovementEngine;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.rules.OffsideService;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ProposalPhysicsDiagnostic — the 2-3 minute match test requested by the user
 * (2026-09-23). All 22 players fixed at skill 14, one seeded-neutral run of N
 * ticks (default 120 = 3 match-minutes @ 40 TPM), prints:
 *   1. per-minute snapshot — every player's pitch position + target, ball-relative
 *      offsets, and a target-sanity flag (OOB / crowded / far / no target);
 *   2. every DECISION vs ALL scored alternatives (chosen + PASS/DRIBBLE/SHOT/CLEAR);
 *   3. every PASS trace — origin -> receiver, landing position, outcome, flight time;
 *   4. every opponent-half pass offside trace — receiver position + 2nd-to-last
 *      defender's distance from the goal line at the moment of the pass
 *      (OffsideService.TRACE, plus fifa-forward-of-ball / in-own-half booleans);
 *   5. every SHOT trace — shooter, origin, on-target flag, outcome, where the ball
 *      actually ended up, flight time;
 *   6. ball speed and player speed statistics (min/max/avg cells per tick).
 * Plus a carrier stand-still detector (consecutive ticks in possession with no
 * movement) — evidence for "players unnaturally stop when in possession".
 */
public class ProposalPhysicsDiagnostic {

    // --- trace buckets ---
    private record PassTrace(int tick, String passer, String receiverLabel,
                             Position origin, Position receiverAtStrike,
                             String outcome, Position landing, int flightTicks) {}
    private record ShotTrace(int tick, String shooter, Position origin, boolean onTarget,
                             String outcome, Position end, int flightTicks) {}

    public static void main(String[] args) {
        int ticks = 120; // 3 match-minutes @ 40 TPM
        if (args.length > 0) ticks = Integer.parseInt(args[0]);

        System.out.println("=== Proposal Physics Diagnostic ===");
        System.out.println("All players skill 14 | " + ticks + " ticks (" + (ticks / 40.0) + " match-minutes)");

        MatchState state = new MatchState();
        addTeamSkill14(state, "HOME");
        addTeamSkill14(state, "AWAY");

        MatchOrchestrator orchestrator = new MatchOrchestrator(state);
        orchestrator.getRestartManager().handleKickoff(state, "HOME");
        for (Player p : state.getPlayers()) {
            state.setRoundStartPosition(p.getId(), p.getPosition());
            state.setRoundPaceSkill(p.getId(), 14);
        }
        OffsideService.TRACE = true;

        List<String> log = orchestrator.getEventLog();
        System.out.println("KICKOFF " + state.getCarrier().getLabel()
                + " | ball" + SimUtils.formatPos(state.getBall().getPosition()));

        // --- per-tick measurements ---
        Map<String, Position> prevPos = new HashMap<>();
        for (Player p : state.getPlayers()) prevPos.put(p.getId(), p.getPosition());

        List<PassTrace> passes = new ArrayList<>();
        List<ShotTrace> shots = new ArrayList<>();
        PassTrace curPass = null;

        // ball speed stats
        int ballMovingTicks = 0; double ballMin = 99, ballMax = 0, ballSum = 0;
        // player speed stats
        double pfxMax = 0, pfxSum = 0; int pfxCount = 0;
        double carrySum = 0; int carryCount = 0; double carryMax = 0;
        Map<String, Double> playerMax = new HashMap<>();
        Map<String, Double> playerSum = new HashMap<>();
        Map<String, Integer> playerCount = new HashMap<>();

        // carrier stand-still detector
        List<String> standStills = new ArrayList<>();
        String curCarrier = null; int standRun = 0; int standRunStart = 0;

        int openShotStartTick = -1; String openShooter = null;
        Position openShotOrigin = null; boolean openShotOnTarget = false;

        for (int t = 0; t < ticks; t++) {
            int logStartSize = log.size();
            int goalsBefore = state.getHomeGoals() + state.getAwayGoals();
            Position ballBefore = state.getBall().getPosition();
            orchestrator.tick();

            List<String> newLines = log.subList(logStartSize, log.size());
            int logEndSize = log.size(); // rebind for the pass/shot state machine below

            // --- 6. ball speed ---
            double ballSpeed = state.getBall().getSpeed();
            if (ballSpeed > BallPhysicsEngine.STOP_SPEED) {
                ballMovingTicks++;
                ballSum += ballSpeed;
                ballMin = Math.min(ballMin, ballSpeed);
                ballMax = Math.max(ballMax, ballSpeed);
            }

            // --- 6. player + carrier speed, stand-still ---
            Player carrier = state.getCarrier();
            if (carrier != null && !carrier.getId().equals(curCarrier)) {
                flushStand(standStills, curCarrier, standRun, standRunStart, t);
                curCarrier = carrier.getId(); standRun = 0; standRunStart = t;
            }
            for (Player p : state.getPlayers()) {
                double moved = SimUtils.distance(prevPos.get(p.getId()), p.getPosition());
                if (moved > 1e-9) {
                    pfxCount++; pfxSum += moved; pfxMax = Math.max(pfxMax, moved);
                    playerMax.merge(p.getId(), moved, Math::max);
                    playerSum.merge(p.getId(), moved, Double::sum);
                    playerCount.merge(p.getId(), 1, Integer::sum);
                }
                if (carrier != null && p.equals(carrier)) {
                    carryCount++; carrySum += moved; carryMax = Math.max(carryMax, moved);
                    if (moved < 0.02) {
                        standRun++;
                    } else {
                        flushStand(standStills, curCarrier, standRun, standRunStart, t);
                        standRun = 0; standRunStart = t;
                    }
                }
            }
            prevPos.clear();
            for (Player p : state.getPlayers()) prevPos.put(p.getId(), p.getPosition());

            // --- 1. per-minute snapshot of positions + targets ---
            if (t > 0 && t % 40 == 0) {
                snapshot(state, t);
            }

            // --- pass / shot state machine on the NEW log lines ---
            for (String line : newLines) {
                if (line.contains("EXEC PASS by ")) {
                    if (curPass != null) {
                        passes.add(new PassTrace(curPass.tick(), curPass.passer(), curPass.receiverLabel(),
                                curPass.origin(), curPass.receiverAtStrike(), "UNRESOLVED",
                                null, ticks - curPass.tick()));
                    }
                    curPass = parsePass(line, t);
                } else if (line.contains("EXEC SHOT by ")) {
                    // open shot tracked live below via lastShooter transitions
                } else if (curPass != null) {
                    Outcome o = parseOutcome(line);
                    if (o != null) {
                        passes.add(new PassTrace(curPass.tick(), curPass.passer(), curPass.receiverLabel(),
                                curPass.origin(), curPass.receiverAtStrike(), o.tag(),
                                o.pos() != null ? o.pos() : state.getBall().getPosition(),
                                t - curPass.tick()));
                        curPass = null;
                    }
                }
            }

            // --- 5. shot launch / flight tracking via lastShooter ---
            Player shooter = state.getLastShooter();
            if (openShooter == null && shooter != null) {
                openShooter = shooter.getLabel();
                openShotStartTick = t;
                openShotOrigin = state.getBall().getPosition();
                openShotOnTarget = state.isLastShotOnTarget();
            } else if (openShooter != null) {
                for (String line : log.subList(logStartSize, logEndSize)) {
                    Outcome o = parseShotOutcome(line);
                    if (o != null) {
                        shots.add(new ShotTrace(openShotStartTick, openShooter, openShotOrigin,
                                openShotOnTarget, o.tag(), o.pos() != null ? o.pos() : state.getBall().getPosition(),
                                t - openShotStartTick));
                        openShooter = null;
                        break;
                    }
                }
                if (openShooter != null && shooter == null) {
                    // consumed without a matching log line this tick
                    shots.add(new ShotTrace(openShotStartTick, openShooter, openShotOrigin,
                            openShotOnTarget, "RESOLVED", state.getBall().getPosition(),
                            t - openShotStartTick));
                    openShooter = null;
                }
            }

            // 0-0 safety: if the shot outcome line arrived but lastShooter was already
            // cleared earlier and we missed it, close on fresh carrier/goal.
            if (openShooter != null
                    && (state.getCarrier() != null
                        || state.getHomeGoals() + state.getAwayGoals() > goalsBefore
                        || state.getOobPending() != null)) {
                shots.add(new ShotTrace(openShotStartTick, openShooter, openShotOrigin,
                        openShotOnTarget, "END", state.getBall().getPosition(),
                        t - openShotStartTick));
                openShooter = null;
            }
        }
        flushStand(standStills, curCarrier, standRun, standRunStart, ticks);
        if (curPass != null) {
            passes.add(new PassTrace(curPass.tick(), curPass.passer(), curPass.receiverLabel(),
                    curPass.origin(), curPass.receiverAtStrike(), "IN-FLIGHT@END", null, ticks - curPass.tick()));
        }
        if (openShooter != null) {
            shots.add(new ShotTrace(openShotStartTick, openShooter, openShotOrigin,
                    openShotOnTarget, "IN-FLIGHT@END", state.getBall().getPosition(), ticks - openShotStartTick));
        }
        OffsideService.TRACE = false;

        // =====================================================================
        // OUTPUT
        // =====================================================================
        System.out.println("\n########## 1. FINAL POSITIONS ##########");
        for (Player p : state.getPlayers()) {
            System.out.println("  " + p.getLabel() + " " + p.getRole() + " pos" + SimUtils.formatPos(p.getPosition())
                    + " target" + (p.getTarget() == null ? " null" : SimUtils.formatPos(p.getTarget())));
        }
        System.out.println("  ball" + SimUtils.formatPos(state.getBall().getPosition())
                + " speed=" + String.format("%.3f", state.getBall().getSpeed()));

        System.out.println("\n########## 2. DECISION QUALITY ##########");
        printDecisionAnalysis(log);

        System.out.println("\n########## 3. PASS EXECUTION ##########");
        System.out.println("total passes: " + passes.size());
        Map<String, Integer> outBuckets = new HashMap<>();
        for (PassTrace pt : passes) outBuckets.merge(pt.outcome(), 1, Integer::sum);
        outBuckets.forEach((k, v) -> System.out.println("  " + k + ": " + v));
        System.out.println("flight ticks min/avg/max: "
                + passes.stream().mapToInt(PassTrace::flightTicks).min().orElse(-1) + " / "
                + String.format("%.2f", passes.stream().mapToInt(PassTrace::flightTicks).average().orElse(0)) + " / "
                + passes.stream().mapToInt(PassTrace::flightTicks).max().orElse(-1));
        System.out.println("pass deviation window (intended receiver pos vs landing):");
        int shown = 0;
        for (PassTrace pt : passes) {
            if (pt.landing() == null) continue;
            double dev = SimUtils.distance(pt.receiverAtStrike(), pt.landing());
            System.out.println("  " + pt.passer() + " -> " + pt.receiverLabel() + " " + pt.outcome()
                    + " | origin" + SimUtils.formatPos(pt.origin())
                    + " receiver@strike" + SimUtils.formatPos(pt.receiverAtStrike())
                    + " landing" + SimUtils.formatPos(pt.landing())
                    + " dev=" + String.format("%.2f", dev)
                    + " flight=" + pt.flightTicks() + "t");
            if (++shown >= 25) { System.out.println("  ... (truncated)"); break; }
        }

        System.out.println("\n########## 5. SHOT EXECUTION ##########");
        System.out.println("total shots: " + shots.size());
        Map<String, Integer> shotBuckets = new HashMap<>();
        for (ShotTrace st : shots) shotBuckets.merge(st.outcome(), 1, Integer::sum);
        shotBuckets.forEach((k, v) -> System.out.println("  " + k + ": " + v));
        long onTargetCount = shots.stream().filter(ShotTrace::onTarget).count();
        System.out.println("on-target flag: " + onTargetCount + "/" + shots.size()
                + " (" + (shots.isEmpty() ? 0 : 100.0 * onTargetCount / shots.size()) + "%)");
        for (ShotTrace st : shots) {
            double distOrigin = SimUtils.distance(st.origin(), goalFor(st.shooter()));
            double distEnd = st.end() == null ? Double.NaN : SimUtils.distance(st.end(), goalFor(st.shooter()));
            System.out.println("  " + st.shooter() + " at" + SimUtils.formatPos(st.origin())
                    + " distGoal=" + String.format("%.2f", distOrigin)
                    + (st.onTarget() ? " ON-TARGET" : " off-target")
                    + " -> " + st.outcome() + " end" + (st.end() == null ? "?" : SimUtils.formatPos(st.end()))
                    + " endDistGoal=" + (Double.isNaN(distEnd) ? "?" : String.format("%.2f", distEnd))
                    + " flight=" + st.flightTicks() + "t");
        }

        System.out.println("\n########## 6. SPEEDS ##########");
        System.out.println("BALL  moving ticks=" + ballMovingTicks + "/" + ticks
                + " min=" + (ballMovingTicks == 0 ? "n/a" : String.format("%.3f", ballMin))
                + " avg=" + (ballMovingTicks == 0 ? "n/a" : String.format("%.3f", ballSum / ballMovingTicks))
                + " max=" + (ballMovingTicks == 0 ? "n/a" : String.format("%.3f", ballMax))
                + " cells/tick (max physics limit " + BallPhysicsEngine.MAX_BALL_SPEED + ")");
        System.out.println("PLAYER all: avg=" + String.format("%.3f", pfxCount == 0 ? 0 : pfxSum / pfxCount)
                + " max=" + String.format("%.3f", pfxMax) + " cells/tick (pace14 cap "
                + String.format("%.3f", MovementEngine.playerSpeedFor(14)) + ")");
        System.out.println("PLAYER carrier: avg=" + String.format("%.3f", carryCount == 0 ? 0 : carrySum / carryCount)
                + " max=" + String.format("%.3f", carryMax) + " cells/tick");
        System.out.println("per-player max move (cells/tick):");
        playerMax.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.println("  " + e.getKey() + " max=" + String.format("%.3f", e.getValue())
                        + " avg=" + String.format("%.3f",
                                playerCount.getOrDefault(e.getKey(), 0) == 0 ? 0
                                        : playerSum.get(e.getKey()) / playerCount.get(e.getKey()))));

        System.out.println("\n########## 'UNNATURAL STOP WHILE IN POSSESSION' ##########");
        if (standStills.isEmpty()) {
            System.out.println("  no stand-still runs of >=3 ticks while holding the ball");
        } else {
            standStills.forEach(System.out::println);
        }
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    private static Position goalFor(String label) {
        return label.startsWith("A") ? new Position(1.0, 4.0) : new Position(8.0, 4.0);
    }

    private static void flushStand(List<String> out, String carrier, int run, int start, int now) {
        if (carrier != null && run >= 3) {
            out.add("  " + carrier + " STOOD " + run + " consecutive ticks t" + start + "-" + (now - 1)
                    + " (" + String.format("%.1f", start / 40.0 * 60.0) + "s match time)");
        }
    }

    private static void snapshot(MatchState state, int t) {
        StringBuilder sb = new StringBuilder();
        Player carrier = state.getCarrier();
        Position ball = state.getBall().getPosition();
        sb.append(String.format("[SNAP t=%d (%d:%02d)] ball", t, t / 40, t % 40 * 90 / 40))
                .append(SimUtils.formatPos(ball))
                .append(" carrier=").append(carrier == null ? "-" : carrier.getLabel())
                .append(" speed=").append(String.format("%.3f", state.getBall().getSpeed()));
        System.out.println(sb);
        for (Player p : state.getPlayers()) {
            Position pos = p.getPosition();
            Position target = p.getTarget();
            sb = new StringBuilder("  ").append(p.getLabel()).append(" ").append(p.getRole());
            sb.append(" P").append(SimUtils.formatPos(pos));
            sb.append(String.format(" relBall(%+.2f,%+.2f)", pos.getRow() - ball.getRow(), pos.getColumn() - ball.getColumn()));
            sb.append(" T").append(target == null ? "null" : SimUtils.formatPos(target));
            sb.append(" [").append(targetCheck(p, state)).append("]");
            if (state.getRestartTaker() != null && p.equals(state.getRestartTaker())) sb.append(" TAKER");
            if (carrier != null && p.equals(carrier)) sb.append(" (carrying)");
            System.out.println(sb);
        }
    }

    /** Sanity check on a player's current movement target. */
    private static String targetCheck(Player p, MatchState state) {
        if (p.isUnavailable()) return "out";
        Position target = p.getTarget();
        if (target == null) return "no-target";
        if (target.getRow() < 1.0 || target.getRow() > 8.0
                || target.getColumn() < 1.0 || target.getColumn() > 7.0) return "OOB-target";
        boolean isClaimSpot = SimUtils.distance(target, state.getBall().getPosition()) < 0.1;
        for (Player opp : state.getPlayers()) {
            if (opp.getTeam().equals(p.getTeam()) || opp.isUnavailable()) continue;
            if (!isClaimSpot && SimUtils.distance(opp.getPosition(), target) < 0.35) return "crowded";
        }
        if (SimUtils.distance(p.getPosition(), target) > 5.0) return "far";
        return "ok";
    }

    private static final Pattern EXEC_PASS = Pattern.compile(
            "EXEC PASS by (\\w+)\\(\\w+\\) at \\(([\\d.]+),([\\d.]+)\\) .*target (\\w+)\\(\\w+\\)\\(([\\d.]+),([\\d.]+)\\)");

    private static PassTrace parsePass(String line, int t) {
        Matcher m = EXEC_PASS.matcher(line);
        if (m.find()) {
            return new PassTrace(t, m.group(1), m.group(4),
                    new Position(Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))),
                    new Position(Double.parseDouble(m.group(5)), Double.parseDouble(m.group(6))),
                    null, null, 0);
        }
        return new PassTrace(t, "?", "?", null, null, null, null, 0);
    }

    private record Outcome(String tag, Position pos) {}

    /** Match a ball-landing line from BallResultHandler and pull the ball position. */
    private static Outcome parseOutcome(String line) {
        Position pos = null;
        int bi = line.indexOf("ball");
        if (bi >= 0) {
            int o = line.indexOf('(', bi), c = line.indexOf(')', o >= 0 ? o : 0);
            if (o >= 0 && c > o) {
                String[] xy = line.substring(o + 1, c).trim().split("[,;]");
                if (xy.length == 2) {
                    try {
                        pos = new Position(Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim()));
                    } catch (NumberFormatException ignored) { pos = null; }
                }
            }
        }
        if (line.contains("RECEIVE ")) return new Outcome("RECEIVE", pos);
        if (line.contains("INTERCEPT ")) return new Outcome("INTERCEPT", pos);
        if (line.contains("DEFLECT off ")) return new Outcome("DEFLECT", pos);
        if (line.contains("BLOCK ") && !line.contains("SHOT_BLOCKED")) return new Outcome("BLOCK", pos);
        if (line.contains("LOOSE BALL recovered")) return new Outcome("LOOSE", pos);
        if (line.contains("GK CATCH")) return new Outcome("GK_CATCH", pos);
        if (line.contains("OOB enter")) return new Outcome("OOB", pos);
        return null;
    }

    /** Match a shot-outcome line — must be a real SHOT_* / GOAL line. */
    private static Outcome parseShotOutcome(String line) {
        Position pos = null;
        String ball = "| ball(";
        int bi = line.indexOf(ball);
        if (bi >= 0) {
            int o = line.indexOf('(', bi), c = line.indexOf(')', o >= 0 ? o : 0);
            if (o >= 0 && c > o) {
                String[] xy = line.substring(o + 1, c).split("[,;]");
                if (xy.length == 2) {
                    try {
                        pos = new Position(Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim()));
                    } catch (NumberFormatException ignored) { pos = null; }
                }
            }
        }
        if (line.contains("SHOT_SAVED")) return new Outcome("SHOT_SAVED", pos);
        if (line.contains("SHOT_BLOCKED")) return new Outcome("SHOT_BLOCKED", pos);
        if (line.contains("SHOT_POST")) return new Outcome("SHOT_POST", pos);
        if (line.contains("SHOT_MISSED")) return new Outcome("SHOT_MISSED", pos);
        if (line.contains("*** GOAL ")) return new Outcome("GOAL", pos);
        return null;
    }

    /** Decision analysis: chosen vs alternatives from every DECISION log line. */
    private static void printDecisionAnalysis(List<String> log) {
        Pattern head = Pattern.compile("DECISION (\\w+)\\(\\w+\\) -> (\\w+) score=\\s*(-?[\\d.]+)");
        Pattern alt = Pattern.compile("\\| (PASS|DRIBBLE|SHOT|CLEAR)=(-?[\\d.]+)");
        Map<String, Integer> chosenCount = new HashMap<>();
        int withScores = 0; int loserGapSamples = 0; double gapSum = 0; int gapCount = 0;
        int shotsPicked = 0; int shotsNegative = 0;
        int total = 0;
        for (String line : log) {
            if (!line.contains("DECISION ")) continue;
            total++;
            Matcher hm = head.matcher(line);
            if (!hm.find()) continue;
            String chosen = hm.group(2);
            double chosenScore = Double.parseDouble(hm.group(3));
            chosenCount.merge(chosen, 1, Integer::sum);
            withScores++;

            Matcher am = alt.matcher(line);
            double bestAlt = -Double.MAX_VALUE;
            while (am.find()) {
                bestAlt = Math.max(bestAlt, Double.parseDouble(am.group(2)));
            }
            if (bestAlt > -Double.MAX_VALUE / 2) {
                double gap = bestAlt - chosenScore;
                if (gap > 1e-6) {
                    gapSum += gap; gapCount++;
                    if (loserGapSamples < 10) {
                        System.out.println("  HIGHER ALTERNATIVE: chose " + chosen + " (" + String.format("%.1f", chosenScore)
                                + ") but best alt " + String.format("%.1f", bestAlt) + " better by "
                                + String.format("%.1f", gap) + " :: " + line.substring(line.indexOf("DECISION ")));
                        loserGapSamples++;
                    }
                }
            }
            if ("SHOT".equals(chosen)) shotsPicked++;
        }
        System.out.println("decisions read: " + total + " (with scores: " + withScores + ")");
        chosenCount.forEach((k, v) -> System.out.println("  chosen " + k + ": " + v));
        System.out.println("decisions where a HIGHER-scoring alternative existed: " + gapCount
                + (gapCount > 0 ? " (avg gap " + String.format("%.2f", gapSum / gapCount) + ")" : ""));
    }

    /** Uniform skill-14 team, same formation/slots as MatchSimulationLauncher. */
    private static void addTeamSkill14(MatchState state, String team) {
        double rowR = team.equals("HOME") ? 2.5 : 6.5;
        double rowM = team.equals("HOME") ? 4.0 : 5.0;
        double rowA = team.equals("HOME") ? 6.0 : 3.0;
        String[][] rolesCols = {
            {"GK",  "0.0", "3.5"},
            {"DL",  String.valueOf(rowR), "1.5"},
            {"DR",  String.valueOf(rowR), "5.5"},
            {"DCL", String.valueOf(rowR + 0.5), "2.5"},
            {"DCR", String.valueOf(rowR + 0.5), "4.5"},
            {"ML",  String.valueOf(rowM), "1.5"},
            {"MR",  String.valueOf(rowM), "5.5"},
            {"CML", String.valueOf(rowM + 0.5), "2.5"},
            {"CMR", String.valueOf(rowM + 0.5), "4.5"},
            {"STL", String.valueOf(rowA), "3.0"},
            {"STR", String.valueOf(rowA), "4.0"},
        };
        PlayerSkills s14 = new PlayerSkills(14, 14, 14, 14, 14, 14, 14, 14);
        for (int i = 0; i < rolesCols.length; i++) {
            String role = rolesCols[i][0];
            Position pos = new Position(Double.parseDouble(rolesCols[i][1]), Double.parseDouble(rolesCols[i][2]));
            Player p = new Player(team + "-" + (i + 1), team.substring(0, 1) + (i + 1), team, role, pos, pos, s14, 180);
            state.getPlayers().add(p);
        }
    }
}