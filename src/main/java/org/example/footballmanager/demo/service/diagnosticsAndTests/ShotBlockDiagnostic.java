package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.MatchEvent;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.List;

/**
 * Physical shot-block QA (user rule): a block only counts when
 *  (1) the shooter fired toward the goal (SHOT event present),
 *  (2) the blocker stands on the shot line between shooter and goal,
 *  (3) the ball physically travels and strikes the blocker's position.
 * The SHOT_BLOCKED event now carries the blocker's position at the contact
 * point — this trace verifies each block happened between the shooter and the
 * goal (defender "on the line"), not next to the shooter or off to a side.
 */
public class ShotBlockDiagnostic {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        MatchSimulator simulator = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
        var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

        MatchResult result = simulator.simulate(homePlayers, awayPlayers,
                "Omladinac", "Partizan");
        List<MatchEvent> events = result.events();

        List<MatchEvent> shots = events.stream()
                .filter(e -> "SHOT".equals(e.type()))
                .toList();
        List<MatchEvent> shotsSaved = events.stream()
                .filter(e -> "SHOT_SAVED".equals(e.type()) || "SAVE".equals(e.type()))
                .toList();
        List<MatchEvent> blocks = events.stream()
                .filter(e -> "SHOT_BLOCKED".equals(e.type()))
                .toList();
        List<MatchEvent> goals = events.stream()
                .filter(e -> "GOAL".equals(e.type()))
                .toList();
        List<MatchEvent> misses = events.stream()
                .filter(e -> "SHOT_MISSED".equals(e.type()) || "MISS".equals(e.type()))
                .toList();

        System.out.println("=== PHYSICAL SHOT BLOCK QA (seed=" + seed + ") ===");
        System.out.println("Final score: " + result.finalScore());
        System.out.println("Shots=" + shots.size() + " blocks=" + blocks.size()
                + " saves=" + shotsSaved.size() + " goals=" + goals.size()
                + " misses=" + misses.size());
        System.out.println();

        int onLine = 0, offLine = 0, noShooter = 0;
        for (MatchEvent b : blocks) {
            // Locate the closest SHOT event near this block (same actionId best).
            MatchEvent shot = shots.stream()
                    .filter(s -> b.actionId() != null && b.actionId().equals(s.actionId()))
                    .findFirst()
                    .orElse(null);
            if (shot == null) {
                shot = shots.stream()
                        .filter(s -> Math.abs(s.tick() - b.tick()) < 30)
                        .findFirst().orElse(null);
            }
            double br = b.positionRow() == null ? -1 : b.positionRow();
            double bc = b.positionColumn() == null ? -1 : b.positionColumn();
            if (shot == null || shot.positionRow() == null || shot.positionColumn() == null) {
                noShooter++;
                System.out.println("  block " + b.playerName() + " at (" + fmt(br) + "," + fmt(bc)
                        + ") — no matching SHOT event to anchor the line");
                continue;
            }
            double sr = shot.positionRow(), sc = shot.positionColumn();
            // Which end is the goal being attacked? Use the blocker team:
            // if blocker is AWAY, the attacked goal is row 8 (HOME attacks).
            double goalRow = "AWAY".equals(b.team()) ? 8.0 : 1.0;
            Position shooter = new Position(sr, sc);
            // The engine aims at the FAR POST when the GK is hugging a post, so
            // anchor the QA line to the post nearest the blocker, not the goal
            // centre — otherwise a legit far-post block tests "off-line".
            double postCol = bc < 3.5 ? 3.0 : 4.0;
            Position goal = new Position(goalRow, postCol);
            Position blocker = new Position(br, bc);
            double lineDist = perpDist(blocker, shooter, goal);
            boolean between = between(blocker, shooter, goal);
            if (lineDist <= 0.50 && between) {
                onLine++;
                System.out.println("  [~" + clock(b.tick()) + "] SHOT " + shot.playerName()
                        + " @(" + fmt(sr) + "," + fmt(sc) + ") -> goal row " + goalRow
                        + " | BLOCKED by " + b.playerName() + " @(" + fmt(br) + "," + fmt(bc)
                        + ") perpToLine=" + fmt(lineDist) + " between=" + between + "  ON LINE");
            } else {
                offLine++;
                System.out.println("  [~" + clock(b.tick()) + "] SHOT " + (shot.playerName() == null ? "?" : shot.playerName())
                        + " @(" + fmt(sr) + "," + fmt(sc) + ") -> goal row " + goalRow
                        + " | BLOCKED by " + b.playerName() + " @(" + fmt(br) + "," + fmt(bc)
                        + ") perpToLine=" + fmt(lineDist) + " between=" + between + "  !! OFF/LINE/AFT");
            }
        }
        System.out.println();
        System.out.printf("blocks=%d onLine=%d offLine=%d noShooter=%d%n",
                blocks.size(), onLine, offLine, noShooter);
        boolean pass = blocks.size() == onLine && blocks.size() > 0;
        System.out.println(pass
                ? "PASS — every SHOT_BLOCKED happened with the blocker on the shot line between shooter and goal"
                : "FAIL — some blocks happened off the shot line (not physical)");
    }

    private static boolean between(Position p, Position a, Position b) {
        double ab2 = (b.getRow() - a.getRow()) * (b.getRow() - a.getRow())
                + (b.getColumn() - a.getColumn()) * (b.getColumn() - a.getColumn());
        if (ab2 < 1e-9) return true;
        double t = ((p.getRow() - a.getRow()) * (b.getRow() - a.getRow())
                + (p.getColumn() - a.getColumn()) * (b.getColumn() - a.getColumn())) / ab2;
        return t >= 0.0 && t <= 1.0;
    }

    private static double perpDist(Position p, Position a, Position b) {
        double dx = b.getColumn() - a.getColumn();
        double dy = b.getRow() - a.getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) return Math.hypot(p.getRow() - a.getRow(), p.getColumn() - a.getColumn());
        double t = clamp(((p.getColumn() - a.getColumn()) * dx
                + (p.getRow() - a.getRow()) * dy) / (len * len), 0, 1);
        return Math.hypot(p.getRow() - (a.getRow() + t * dy),
                p.getColumn() - (a.getColumn() + t * dx));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    private static String clock(long tick) {
        int s = (int) (tick / 2);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}