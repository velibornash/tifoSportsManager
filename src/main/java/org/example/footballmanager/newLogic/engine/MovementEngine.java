package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.*;

public final class MovementEngine {

    /** Base step per tick for a player with pace=1 (scaled for 60 ticks/min) */
    private static final double PACE_STEP_MIN = 0.07;
    /** Max step per tick for a player with pace=20 (scaled for 60 ticks/min) */
    private static final double PACE_STEP_MAX = 0.84;
    /** Carrier dribble multiplier on top of base step */
    private static final double DRIBBLE_MULT = 1.45;
    /** Loose ball chase multiplier */
    private static final double CHASE_MULT = 1.35;
    /** Max blend toward ball — cell anchor stays primary (>= 0.55 cell weight) */
    private static final double MAX_BALL_INFLUENCE = 0.22; // reduced so formation anchors remain primary
    /** Separates same-team players */
    private static final double SEPARATION_DIST = 5.8;

    private static final double MIN_X = MatchState.MIN_X, MAX_X = MatchState.MAX_X;
    private static final double MIN_Y = MatchState.MIN_Y, MAX_Y = MatchState.MAX_Y;
    private static final Random RNG = new Random();

    private MovementEngine() {}

    // ── BLEND SYSTEM ──────────────────────────────────────────

    public static void startBlend(MatchState state, long playerId, double targetX, double targetY, int ticks) {
        PlayerSnapshot snap = state.snapshotById(playerId);
        if (snap == null) return;
        double dx = targetX - snap.x();
        double dy = targetY - snap.y();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.5) return;
        state.blendTargets.put(playerId, new MatchState.BlendTarget(
            clamp(targetX, MIN_X, MAX_X), clamp(targetY, MIN_Y, MAX_Y), ticks, ticks
        ));
    }

    public static void processBlends(MatchState state) {
        var iter = state.blendTargets.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long pid = entry.getKey();
            MatchState.BlendTarget bt = entry.getValue();
            PlayerSnapshot snap = state.snapshotById(pid);
            if (snap == null) { iter.remove(); continue; }

            double dx = bt.targetX() - snap.x();
            double dy = bt.targetY() - snap.y();
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist < 0.5) {
                int i = state.playerSnapshots.indexOf(snap);
                if (i >= 0) {
                    state.playerSnapshots.set(i, new PlayerSnapshot(snap.playerId(), snap.name(), snap.teamSide(),
                        snap.position(), bt.targetX(), bt.targetY(), snap.state(), snap.hasBall()));
                }
                iter.remove();
                continue;
            }

            int remaining = Math.max(1, bt.ticksRemaining());
            double stepX = dx / remaining;
            double stepY = dy / remaining;
            double maxStepPerTick = paceMaxStep(state.playerById(pid), 1.0);
            double mag = Math.sqrt(stepX * stepX + stepY * stepY);
            if (mag > maxStepPerTick) {
                double scale = maxStepPerTick / mag;
                stepX *= scale;
                stepY *= scale;
            }

            double nx = snap.x() + stepX;
            double ny = snap.y() + stepY;

            int i = state.playerSnapshots.indexOf(snap);
            if (i >= 0) {
                state.playerSnapshots.set(i, new PlayerSnapshot(snap.playerId(), snap.name(), snap.teamSide(),
                    snap.position(), clamp(nx, MIN_X, MAX_X), clamp(ny, MIN_Y, MAX_Y), snap.state(), snap.hasBall()));
            }

            if (bt.ticksRemaining() > 0) {
                entry.setValue(bt.dec());
            }
        }
    }

    // ── CORE: UPDATE ALL MOVEMENT ────────────────────────────

    public static void updateAllMovement(MatchState state) {
        updateAllMovement(state, -1);
    }

    public static void updateAllMovement(MatchState state, long excludePlayerId) {
        processBlends(state);

        int[] ballZoneArr = ZonePositionCalculator.ballZone(state.ball.x(), state.ball.y());
        int bx = ballZoneArr[0], by = ballZoneArr[1];

        Team homeTeam = state.match.homeTeam();
        Team awayTeam = state.match.awayTeam();

        for (var snap : state.playerSnapshots) {
            if (snap.playerId() == (state.carrierId != null ? state.carrierId : -1)) continue;
            if (snap.playerId() == excludePlayerId) continue;
            if (state.blendTargets.containsKey(snap.playerId())) continue;

            Player player = state.playerById(snap.playerId());
            if (player == null) continue;

            if (state.stoppage == MatchState.StoppageType.KICK_OFF) {
                double[] kickoffTarget = kickoffHoldTarget(snap);
                double[] moved = moveTowards(snap.x(), snap.y(), kickoffTarget[0], kickoffTarget[1], paceMaxStep(player, 0.65));
                int i = state.playerSnapshots.indexOf(snap);
                if (i >= 0) {
                    state.playerSnapshots.set(i, new PlayerSnapshot(snap.playerId(), snap.name(), snap.teamSide(),
                        snap.position(), clamp(moved[0], MIN_X, MAX_X), clamp(moved[1], MIN_Y, MAX_Y), snap.state(), snap.hasBall()));
                }
                continue;
            }

            // Resolve slot and tactics for this player
            String slotKey = resolveSlotKey(state, snap);
            TacticRules tactics = resolveTactics(snap, homeTeam, awayTeam);
            boolean inPossession = snap.teamSide().equals(state.possessionTeam);

            if (!inPossession && snap.position() != Position.GK && state.possessionTeam != null
                && OffsideTracker.isOffside(state, snap)) {
                int streak = state.playerOffsideStreak.getOrDefault(snap.playerId(), 0) + 1;
                state.playerOffsideStreak.put(snap.playerId(), streak);
                if (streak >= 3) {
                    double offsideLine = DecisionEngine.calculateOffsideLine(state, snap.teamSide());
                    double retreatX = retreatOffsideX(snap, offsideLine);
                    double retreatY = retreatOffsideY(snap);
                    double[] moved = moveTowards(snap.x(), snap.y(), retreatX, retreatY, paceMaxStep(player, 0.75));
                    int i = state.playerSnapshots.indexOf(snap);
                    if (i >= 0) {
                        state.playerSnapshots.set(i, new PlayerSnapshot(snap.playerId(), snap.name(), snap.teamSide(),
                            snap.position(), clamp(moved[0], MIN_X, MAX_X), clamp(moved[1], MIN_Y, MAX_Y),
                            "RETREAT", snap.hasBall()));
                    }
                    continue;
                }
            } else {
                state.playerOffsideStreak.put(snap.playerId(), 0);
            }

            double[] target = ZonePositionCalculator.tacticalTarget(
                player, snap.teamSide(), inPossession, bx, by,
                slotKey, tactics
            );

            if (state.restartMode != null && state.possessionAgeTicks < 6 && snap.position() != Position.GK && !snap.hasBall()) {
                double holdLine = "HOME".equals(snap.teamSide()) ? 49.0 : 51.0;
                double lineAllowance = switch (snap.position()) {
                    case DEF -> 8.0;
                    case MID -> 4.5;
                    case WNG -> 3.0;
                    case ATT -> 1.5;
                    case GK -> 0.0;
                };
                if ("HOME".equals(snap.teamSide())) {
                    target[0] = Math.min(target[0], holdLine - lineAllowance);
                } else {
                    target[0] = Math.max(target[0], holdLine + lineAllowance);
                }
            }

            target = applyBallInfluence(state, snap, player, inPossession, target);

            double stepMult = 1.0;
            if (snap.playerId() == excludePlayerId) {
                stepMult = CHASE_MULT;
            } else if (!inPossession && snap.position() != Position.GK) {
                double distToBall = snap.distanceToPoint(state.ball.x(), state.ball.y());
                if (distToBall < 16.0) {
                    stepMult = 1.0 + (16.0 - distToBall) / 16.0 * 0.35;
                }
            }

            double step = paceMaxStep(player, stepMult);
            double[] moved = moveTowards(snap.x(), snap.y(), target[0], target[1], step);
            int i = state.playerSnapshots.indexOf(snap);
            if (i >= 0) {
                state.playerSnapshots.set(i, new PlayerSnapshot(snap.playerId(), snap.name(), snap.teamSide(),
                    snap.position(), clamp(moved[0], MIN_X, MAX_X), clamp(moved[1], MIN_Y, MAX_Y), snap.state(), snap.hasBall()));
            }
        }

        separatePlayers(state);
        enforceGoalkeeperArea(state);
    }

    private static double retreatOffsideX(PlayerSnapshot snap, double offsideLine) {
        if ("HOME".equals(snap.teamSide())) {
            return Math.min(offsideLine - 2.5, snap.x() - 2.0);
        }
        return Math.max(offsideLine + 2.5, snap.x() + 2.0);
    }

    private static double retreatOffsideY(PlayerSnapshot snap) {
        double centerBias = 50.0;
        double laneBias = (snap.playerId() % 5 - 2) * 2.2;
        return clamp(centerBias + laneBias, MIN_Y, MAX_Y);
    }

    private static double[] kickoffHoldTarget(PlayerSnapshot snap) {
        boolean home = "HOME".equals(snap.teamSide());
        return switch (snap.position()) {
            case GK -> new double[]{home ? 10.0 : 90.0, 50.0};
            case DEF -> new double[]{home ? 22.0 : 78.0, clamp(snap.y(), 16.0, 84.0)};
            case MID -> new double[]{home ? 36.0 : 64.0, clamp(snap.y(), 18.0, 82.0)};
            case WNG -> new double[]{home ? 40.0 : 60.0, clamp(snap.y(), 12.0, 88.0)};
            case ATT -> new double[]{home ? 44.0 : 56.0, clamp(snap.y(), 20.0, 80.0)};
        };
    }

    private static String resolveSlotKey(MatchState state, PlayerSnapshot snap) {
        if (state == null || snap == null) return null;
        String slotKey = state.playerSlotKeys.get(snap.playerId());
        if (slotKey != null) return slotKey;
        return switch (snap.position()) {
            case GK -> "GK";
            case DEF -> "DCL";
            case MID -> "CM";
            case WNG -> "WL";
            case ATT -> "ST";
        };
    }

    private static TacticRules resolveTactics(PlayerSnapshot snap, Team homeTeam, Team awayTeam) {
        Team team = "HOME".equals(snap.teamSide()) ? homeTeam : awayTeam;
        return team != null ? team.tacticRules() : null;
    }

    /**
     * Cell/slot target stays primary; nearby ball — especially contested — can bend the run.
     */
    private static double[] applyBallInfluence(MatchState state, PlayerSnapshot snap, Player player,
                                               boolean inPossession, double[] cellTarget) {
        if (cellTarget == null || snap.position() == Position.GK) return cellTarget;

        double bx = state.ball.x();
        double by = state.ball.y();
        double distToBall = snap.distanceToPoint(bx, by);
        if (distToBall > 36.0) return cellTarget;

        double influence = 0.0;
        Position line = snap.position();

        if (inPossession) {
            if (distToBall < 30.0) {
                influence = switch (line) {
                    case ATT, WNG -> 0.06 + (30.0 - distToBall) / 30.0 * 0.14;
                    case MID -> 0.08 + (30.0 - distToBall) / 30.0 * 0.18;
                    case DEF -> 0.03 + (30.0 - distToBall) / 30.0 * 0.07;
                    default -> 0.0;
                };
            }
        } else {
            if (distToBall < 34.0) {
                influence = switch (line) {
                    case DEF -> 0.10 + (34.0 - distToBall) / 34.0 * 0.24;
                    case MID -> 0.08 + (34.0 - distToBall) / 34.0 * 0.20;
                    case WNG -> 0.06 + (34.0 - distToBall) / 34.0 * 0.16;
                    case ATT -> 0.04 + (34.0 - distToBall) / 34.0 * 0.10;
                    default -> 0.0;
                };
            }
        }

        String oppSide = state.oppositeTeam(snap.teamSide());
        int opponentsNear = countNearBall(state, bx, by, oppSide, 9.0);
        int teammatesNear = countNearBall(state, bx, by, snap.teamSide(), 9.0);
        boolean contested = opponentsNear > 0 && (teammatesNear > 0 || state.carrierId != null);

        if (contested && distToBall < 20.0 && (line == Position.DEF || line == Position.MID || line == Position.WNG)) {
            // smaller contested bias to avoid large group pulls off anchors
            influence += 0.06 + (20.0 - distToBall) / 20.0 * 0.12;
        }
        if (state.carrierId == null && !state.ballInTransit && distToBall < 16.0) {
            // reduce loose-ball snap bias so single loose-ball pickups don't drag all players
            influence += 0.06 + (16.0 - distToBall) / 16.0 * 0.10;
        }

        influence = Math.min(MAX_BALL_INFLUENCE, influence);
        if (influence <= 0.01) return cellTarget;

        return new double[]{
            lerp(cellTarget[0], bx, influence),
            lerp(cellTarget[1], by, influence)
        };
    }

    private static int countNearBall(MatchState state, double bx, double by, String teamSide, double radius) {
        int count = 0;
        for (var ps : state.playerSnapshots) {
            if (!teamSide.equals(ps.teamSide()) || ps.position() == Position.GK) continue;
            if (ps.distanceToPoint(bx, by) <= radius) count++;
        }
        return count;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ── DRIBBLE / CHASE / MOVE TOWARD ────────────────────────

    public static void moveCarrierTowardsGoal(MatchState state, Player player, double stepOverride) {
        PlayerSnapshot snap = state.snapshotById(player.id());
        if (snap == null) return;
        double targetX = "HOME".equals(snap.teamSide()) ? 88.0 : 12.0;
        double step = stepOverride > 0 ? stepOverride * player.movementModifier() : paceMaxStep(player, DRIBBLE_MULT);
        double[] moved = moveTowards(snap.x(), snap.y(), targetX, 50.0, step);
        updateSnapshotPosition(state, player.id(), clamp(moved[0], MIN_X, MAX_X), clamp(moved[1], MIN_Y, MAX_Y));
    }

    public static void movePlayerTowards(MatchState state, Player player, double targetX, double targetY, double step) {
        PlayerSnapshot snap = state.snapshotById(player.id());
        if (snap == null) return;
        double effectiveStep = step > 0 ? step * player.movementModifier() : paceMaxStep(player, 1.0);
        double[] moved = moveTowards(snap.x(), snap.y(), targetX, targetY, effectiveStep);
        updateSnapshotPosition(state, player.id(), clamp(moved[0], MIN_X, MAX_X), clamp(moved[1], MIN_Y, MAX_Y));
    }

    public static void movePlayerTowardsBall(MatchState state, Player player, double step) {
        movePlayerTowards(state, player, state.ball.x(), state.ball.y(), step);
    }

    // ── BLEND TO FORMATION ────────────────────────────────────

    public static void blendToFormation(MatchState state) {
        state.blendTargets.clear();
        int[] ballZoneArr = ZonePositionCalculator.ballZone(state.ball.x(), state.ball.y());
        int bx = ballZoneArr[0], by = ballZoneArr[1];

        Team homeTeam = state.match.homeTeam();
        Team awayTeam = state.match.awayTeam();

        for (var snap : state.playerSnapshots) {
            Player player = state.playerById(snap.playerId());
            if (player == null) continue;

            String slotKey = resolveSlotKey(state, snap);
            TacticRules tactics = resolveTactics(snap, homeTeam, awayTeam);

            double[] target = ZonePositionCalculator.tacticalTarget(
                player, snap.teamSide(), snap.teamSide().equals(state.possessionTeam), bx, by,
                slotKey, tactics
            );
            startBlend(state, player.id(), target[0], target[1], 80);
        }
    }

    // ── SET PIECE POSITIONING ─────────────────────────────────

    public static void blendToSetPiece(MatchState state, String setPieceType, String restartTeam) {
        state.blendTargets.clear();
        for (var snap : state.playerSnapshots) {
            Player player = state.playerById(snap.playerId());
            if (player == null) continue;
            double[] target = calcSetPieceTarget(player, snap.teamSide(), setPieceType, restartTeam);
            startBlend(state, player.id(), target[0], target[1], 50);
        }
    }

    private static double[] calcSetPieceTarget(Player player, String side, String type, String restartTeam) {
        boolean isAttacking = side.equals(restartTeam);
        boolean home = "HOME".equals(side);

        if (player.position() == Position.GK) {
            double gkX = home ? 6.0 : 94.0;
            if ("CORNER".equals(type) && !isAttacking) {
                gkX = home ? 4.0 : 96.0; // GK on near post
            }
            return new double[]{gkX, 50.0};
        }

        switch (type) {
            case "CORNER" -> {
                if (isAttacking) {
                    // Attackers near far post and penalty spot
                    double baseX = home ? 84.0 : 16.0;
                    double yOff = 28.0 + (player.id() % 5) * 10.0;
                    return new double[]{baseX, yOff};
                } else {
                    // Defenders back, mark area
                    double baseX = home ? 16.0 : 84.0;
                    double yOff = 20.0 + (player.id() % 6) * 10.0;
                    return new double[]{baseX, yOff};
                }
            }
            case "GOAL_KICK" -> {
                if (isAttacking) {
                    // Push up
                    double baseX = home ? 55.0 : 45.0;
                    double yOff = 18.0 + (player.id() % 7) * 10.0;
                    return new double[]{baseX, yOff};
                } else {
                    // Drop deep, spread wide
                    double baseX = switch (player.position()) {
                        case DEF -> home ? 22.0 : 78.0;
                        case MID, WNG -> home ? 38.0 : 62.0;
                        case ATT -> home ? 55.0 : 45.0;
                        default -> home ? 50.0 : 50.0;
                    };
                    double yOff = 12.0 + (player.id() % 8) * 10.0;
                    return new double[]{baseX, clamp(yOff, 8.0, 92.0)};
                }
            }
            case "FREE_KICK" -> {
                if (!isAttacking) {
                    // Simple wall: 3 players between ball and goal
                    return new double[]{home ? 18.0 : 82.0, 45.0 + (player.id() % 3) * 5.0};
                }
                // Attacking: crowd the box
                double baseX = home ? 80.0 : 20.0;
                double yOff = 30.0 + (player.id() % 5) * 10.0;
                return new double[]{baseX, yOff};
            }
            default -> {
                // THROW_IN or other: spread out
                double baseX = home ? 50.0 : 50.0;
                double yOff = 20.0 + (player.id() % 7) * 10.0;
                return new double[]{baseX, yOff};
            }
        }
    }

    // ── INITIAL POSITIONS (gradual deployment) ────────────────

    public static void initializePositions(MatchState state) {
        Team home = state.match.homeTeam();
        Team away = state.match.awayTeam();

        state.playerSnapshots.clear();
        state.blendTargets.clear();
        deployTeamToFormation(state, home, "HOME");
        deployTeamToFormation(state, away, "AWAY");
    }

    private static void deployTeamToFormation(MatchState state, Team team, String side) {
        List<String> slotKeys = team.slotKeys() != null && team.slotKeys().size() == team.startingXI().size()
            ? team.slotKeys()
            : ZonePositionCalculator.buildSlotKeys(team.formation(), team.startingXI());

        for (int i = 0; i < team.startingXI().size(); i++) {
            var p = team.startingXI().get(i);
            String slotKey = i < slotKeys.size() ? slotKeys.get(i) : "CM";
            double[] pos = ZonePositionCalculator.anchorCenterForSlot(slotKey, side);
            double jitterX = ((p.id() % 3) - 1) * 0.25;
            double jitterY = ((p.id() % 4) - 1.5) * 0.30;
            state.playerSnapshots.add(new PlayerSnapshot(
                p.id(), p.name(), side, p.position(),
                clamp(pos[0] + jitterX, MIN_X, MAX_X),
                clamp(pos[1] + jitterY, MIN_Y, MAX_Y),
                "IDLE", false
            ));
        }
    }

    private static double[] kickoffFormationTarget(Position position, String side, int lineIdx) {
        boolean home = "HOME".equals(side);
        double x = switch (position) {
            case GK -> home ? 10.0 : 90.0;
            case DEF -> home ? 26.0 : 74.0;
            case MID -> home ? 38.0 : 62.0;
            case WNG -> home ? 40.0 : 60.0;
            case ATT -> home ? 44.0 : 56.0;
        };
        double[] lanes = {18.0, 31.0, 44.0, 56.0, 69.0, 82.0};
        double y = position == Position.GK ? 50.0 : lanes[Math.min(lineIdx, lanes.length - 1)];
        return new double[]{x, y};
    }

    // ── UTILITY ───────────────────────────────────────────────

    private static double paceMaxStep(Player player, double multiplier) {
        if (player == null) return 2.4;
        int pace = player.skills().pace();
        double base = PACE_STEP_MIN + (pace / 20.0) * (PACE_STEP_MAX - PACE_STEP_MIN);
        return base * multiplier * player.movementModifier();
    }

    public static void updateSnapshotPosition(MatchState state, long playerId, double x, double y) {
        PlayerSnapshot snap = state.snapshotById(playerId);
        if (snap == null) return;
        int i = state.playerSnapshots.indexOf(snap);
        if (i >= 0) {
            state.playerSnapshots.set(i, new PlayerSnapshot(snap.playerId(), snap.name(), snap.teamSide(),
                snap.position(), x, y, snap.state(), snap.hasBall()));
        }
    }

    private static double paceStep(Player p, double mult) {
        if (p == null) return 2.4;
        int pace = p.skills().pace();
        double base = PACE_STEP_MIN + (pace / 20.0) * (PACE_STEP_MAX - PACE_STEP_MIN);
        return base * mult * p.movementModifier();
    }

    private static void separatePlayers(MatchState state) {
        separateTeam(state.homeSnapshots(), state);
        separateTeam(state.awaySnapshots(), state);
    }

    private static void separateTeam(List<PlayerSnapshot> team, MatchState state) {
        for (int i = 0; i < team.size(); i++) {
            var a = team.get(i);
            for (int j = i + 1; j < team.size(); j++) {
                var b = team.get(j);
                double dist = a.distanceTo(b);
                if (dist >= SEPARATION_DIST) continue;
                if (!shouldSeparate(a, b, dist)) continue;

                double dx = a.x() - b.x();
                double dy = a.y() - b.y();
                if (Math.abs(dx) < 0.01) dx = RNG.nextBoolean() ? 1 : -1;
                if (Math.abs(dy) < 0.01) dy = RNG.nextBoolean() ? 0.8 : -0.8;
                double norm = Math.sqrt(dx * dx + dy * dy);
                double factor = (SEPARATION_DIST - Math.max(dist, 0.1)) / 1.6;

                double pushX = (dx / norm) * factor;
                double pushY = (dy / norm) * factor;
                double cap = separationCap(a, b);
                pushX = clamp(pushX, -cap, cap);
                pushY = clamp(pushY, -cap, cap);

                int ai = state.playerSnapshots.indexOf(a);
                int bj = state.playerSnapshots.indexOf(b);
                if (ai >= 0 && bj >= 0) {
                    state.playerSnapshots.set(ai, new PlayerSnapshot(a.playerId(), a.name(), a.teamSide(), a.position(),
                        clamp(a.x() + pushX, MIN_X, MAX_X), clamp(a.y() + pushY, MIN_Y, MAX_Y), a.state(), a.hasBall()));
                    state.playerSnapshots.set(bj, new PlayerSnapshot(b.playerId(), b.name(), b.teamSide(), b.position(),
                        clamp(b.x() - pushX, MIN_X, MAX_X), clamp(b.y() - pushY, MIN_Y, MAX_Y), b.state(), b.hasBall()));
                }
            }
        }
    }

    private static boolean shouldSeparate(PlayerSnapshot a, PlayerSnapshot b, double dist) {
        if (dist < 3.0) return true;
        return a.position() == b.position()
            || Math.abs(a.x() - b.x()) < 12.0
            || Math.abs(a.y() - b.y()) < 10.0;
    }

    private static double separationCap(PlayerSnapshot a, PlayerSnapshot b) {
        Position line = a.position() == b.position() ? a.position() : (a.position().ordinal() < b.position().ordinal() ? a.position() : b.position());
        return switch (line) {
            case GK -> 0.10;
            case DEF -> 0.18;
            case MID -> 0.24;
            case WNG -> 0.30;
            case ATT -> 0.30;
        };
    }

    private static void enforceGoalkeeperArea(MatchState state) {
        for (var snap : state.playerSnapshots) {
            if (snap.position() != Position.GK) continue;
            double clampedX = "HOME".equals(snap.teamSide())
                ? clamp(snap.x(), MIN_X, 34.0)
                : clamp(snap.x(), 66.0, MAX_X);
            double clampedY = clamp(snap.y(), MIN_Y, MAX_Y);
            if (clampedX != snap.x() || clampedY != snap.y()) {
                int index = state.playerSnapshots.indexOf(snap);
                if (index >= 0) {
                    state.playerSnapshots.set(index, new PlayerSnapshot(
                        snap.playerId(), snap.name(), snap.teamSide(), snap.position(),
                        clampedX, clampedY, snap.state(), snap.hasBall()
                    ));
                }
            }
        }
    }

    static double[] moveTowards(double fromX, double fromY, double toX, double toY, double maxStep) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.1) return new double[]{fromX, fromY};
        double step = Math.min(dist, maxStep);
        double factor = step / dist;
        return new double[]{fromX + dx * factor, fromY + dy * factor};
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
