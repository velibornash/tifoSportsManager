package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MovementEngine {

    private static final double MIN_X = MatchState.MIN_X;
    private static final double MAX_X = MatchState.MAX_X;
    private static final double MIN_Y = MatchState.MIN_Y;
    private static final double MAX_Y = MatchState.MAX_Y;

    private static final double PACE_STEP_MIN = 0.04;
    private static final double PACE_STEP_MAX = 0.33;
    private static final double TICK_DURATION = 1.0 / 120.0;

    private static final Map<Long, BlendTarget> blendTargets = new ConcurrentHashMap<>();

    public MovementEngine() {}

    public static void initializePositions(MatchState state) {
        state.playerSnapshots.clear();
        for (Player p : state.match.homeTeam().startingXI()) {
            double[] pos = startingPosition(p, "HOME", state.match.homeTeam().startingXI().indexOf(p));
            state.playerSnapshots.add(PlayerSnapshot.fromPlayer(p, "HOME", pos[0], pos[1]));
        }
        for (Player p : state.match.awayTeam().startingXI()) {
            double[] pos = startingPosition(p, "AWAY", state.match.awayTeam().startingXI().indexOf(p));
            state.playerSnapshots.add(PlayerSnapshot.fromPlayer(p, "AWAY", pos[0], pos[1]));
        }
    }

    public static void moveToward(PlayerSnapshot snap, double targetX, double targetY, MatchState state) {
        if (state.stoppage != null && state.stoppageTicks > 0) return;

        double dx = targetX - snap.x();
        double dy = targetY - snap.y();
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist < 0.5) return;

        double speed = paceToSpeed(snap.pace()) * getFatigueModifier(snap);
        double moveDist = Math.min(speed, dist);

        double moveX = (dx / dist) * moveDist;
        double moveY = (dy / dist) * moveDist;

        double newX = snap.x() + moveX;
        double newY = snap.y() + moveY;

        newX = Math.max(MIN_X, Math.min(MAX_X, newX));
        newY = Math.max(MIN_Y, Math.min(MAX_Y, newY));

        snap.setPosition(newX, newY);
    }

    public static void startBlend(MatchState state, long playerId, double targetX, double targetY, int ticks) {
        BlendTarget bt = new BlendTarget(
            Math.max(MIN_X, Math.min(MAX_X, targetX)),
            Math.max(MIN_Y, Math.min(MAX_Y, targetY)),
            ticks, ticks
        );
        blendTargets.put(playerId, bt);
    }

    public static void processBlends(MatchState state) {
        var iter = blendTargets.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long pid = entry.getKey();
            BlendTarget bt = entry.getValue();
            PlayerSnapshot snap = state.snapshotById(pid);
            if (snap == null) { iter.remove(); continue; }

            double dx = bt.targetX - snap.x();
            double dy = bt.targetY - snap.y();
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist < 0.5) {
                snap.setPosition(bt.targetX, bt.targetY);
                iter.remove();
                continue;
            }

            int remaining = Math.max(1, bt.ticksRemaining);
            double stepX = dx / remaining;
            double stepY = dy / remaining;
            double maxStepPerTick = paceToSpeed(getPaceForPlayer(state, pid));
            double mag = Math.sqrt(stepX * stepX + stepY * stepY);
            if (mag > maxStepPerTick) {
                double scale = maxStepPerTick / mag;
                stepX *= scale;
                stepY *= scale;
            }

            double nx = snap.x() + stepX;
            double ny = snap.y() + stepY;

            nx = Math.max(MIN_X, Math.min(MAX_X, nx));
            ny = Math.max(MIN_Y, Math.min(MAX_Y, ny));

            snap.setPosition(nx, ny);

            if (bt.ticksRemaining > 0) {
                entry.setValue(bt.dec());
            }
        }
    }

    private record BlendTarget(double targetX, double targetY, int ticksRemaining, int totalTicks) {
        public BlendTarget dec() { return new BlendTarget(targetX, targetY, ticksRemaining - 1, totalTicks); }
    }

    private static int getPaceForPlayer(MatchState state, long pid) {
        PlayerSnapshot snap = state.snapshotById(pid);
        return snap != null ? snap.pace() : 10;
    }

    private static double[] startingPosition(Player p, String teamSide, int index) {
        double baseX = "HOME".equals(teamSide) ? 30.0 : 70.0;
        double baseY = 50.0;

        if (p.position() == Position.GK) {
            baseX = "HOME".equals(teamSide) ? 6.0 : 94.0;
            baseY = 50.0;
        } else if (p.position() == Position.DEF) {
            baseX = "HOME".equals(teamSide) ? 20.0 : 80.0;
            baseY = 20.0 + (index % 4) * 20.0;
        } else if (p.position() == Position.MID) {
            baseX = "HOME".equals(teamSide) ? 40.0 : 60.0;
            baseY = 25.0 + (index % 3) * 25.0;
        } else if (p.position() == Position.ATT || p.position() == Position.WNG) {
            baseX = "HOME".equals(teamSide) ? 60.0 : 40.0;
            baseY = 30.0 + (index % 3) * 20.0;
        }

        return new double[]{baseX, baseY};
    }

    private static double paceToSpeed(int pace) {
        double t = Math.max(0, Math.min(1, (pace - 1) / 19.0));
        return PACE_STEP_MIN + t * (PACE_STEP_MAX - PACE_STEP_MIN);
    }

    private static double getFatigueModifier(PlayerSnapshot snap) {
        return 1.0;
    }

    public static double[] getStartingPosition(Player player, String teamSide, List<PlayerSnapshot> teammates) {
        int samePosCount = 0;
        for (PlayerSnapshot t : teammates) {
            if (t.position() == player.position()) samePosCount++;
        }
        return startingPosition(player, teamSide, samePosCount);
    }
}
