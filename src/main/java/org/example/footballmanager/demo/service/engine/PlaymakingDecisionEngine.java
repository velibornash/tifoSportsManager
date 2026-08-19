package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Playmaking decision engine — generates and scores action options.
 * Decision quality layer (separate from execution quality).
 */
public class PlaymakingDecisionEngine {

    private static final double PRESSURE_RADIUS = 4.0;
    private static final double MAX_PRESSURE = 50.0;
    private static final double MAX_DANGER = 50.0;

    private final MatchState state;
    private final PlayerSelectionEngine selection;
    private final OptionSelector selector;
    private final VisionFilter visionFilter;

    public PlaymakingDecisionEngine(MatchState state, PlayerSelectionEngine selection, java.util.Random random) {
        this.state = state;
        this.selection = selection;
        this.selector = new OptionSelector(random);
        this.visionFilter = new VisionFilter(random);
    }

    public DecisionOption decide() {
        Player carrier = state.getCarrier();
        if (carrier == null) {
            return new DecisionOption(DecisionType.CARRY, 0, "no carrier — fallback to CARRY");
        }

        DecisionContext ctx = buildContext(carrier);
        List<DecisionOption> options = generateOptions(ctx);
        visionFilter.applyVisionFilter(ctx, options);

        List<DecisionOption> visible = options.stream()
                .filter(DecisionOption::isVisible)
                .collect(Collectors.toList());

        if (visible.isEmpty()) {
            for (DecisionOption opt : options) {
                if (opt.getType() == DecisionType.PASS || opt.getType() == DecisionType.CARRY) {
                    opt.setVisible(true);
                }
            }
            visible = options.stream()
                    .filter(DecisionOption::isVisible)
                    .collect(Collectors.toList());
        }

        return selector.select(ctx, visible);
    }

    private DecisionContext buildContext(Player carrier) {
        Position pos = carrier.getPosition();
        double row = pos.getRow();
        boolean home = "HOME".equals(carrier.getTeam());

        boolean isGoalkeeper = "GK".equals(carrier.getRole());
        boolean canShoot = !isGoalkeeper && (home ? row >= ActionEngine.SHOOT_MIN_ROW : row <= 3);
        boolean inFinalThird = home ? row >= 6 : row <= 2;
        boolean onWing = pos.getColumn() <= 2 || pos.getColumn() >= 5;
        boolean inOpponentHalf = home ? row >= 4 : row <= 4;
        boolean isKickoff = state.isKickoffActionPending()
                || (row == 4 && pos.getColumn() == 3.5
                    && (state.getRound() == 1 || state.isCelebrating()));

        double pressure = calculatePressure(carrier);
        double danger = calculateDanger(carrier);
        double fieldPosition = home ? row : 8 - row;

        List<Player> teammates = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p == carrier) continue;
            if (!p.getTeam().equals(carrier.getTeam())) continue;
            if (p.isLocked() || state.isBlockedAfterDuel(p)) continue;
            teammates.add(p);
        }

        List<Player> opponents = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            if (p.isLocked() || state.isBlockedAfterDuel(p)) continue;
            opponents.add(p);
        }

        return new DecisionContext(
                carrier, state.getBall().getBallState(), state.getBall().getPosition(),
                teammates, opponents, pressure, danger, fieldPosition,
                carrier.getSkills().playmaking(),
                home, isGoalkeeper, inFinalThird, onWing, inOpponentHalf, canShoot, isKickoff,
                new ArrayList<>());
    }

    private List<DecisionOption> generateOptions(DecisionContext ctx) {
        List<DecisionOption> options = new ArrayList<>();

        if (ctx.isKickoff()) {
            DecisionOption pass = generateKickoffPass(ctx);
            if (pass != null) options.add(pass);
            options.add(new DecisionOption(DecisionType.CARRY, 0, "kickoff fallback CARRY"));
            return options;
        }

        if (!ctx.isGoalkeeper()) options.add(scoreCarry(ctx));

        DecisionOption pass = scorePass(ctx);
        if (pass != null) options.add(pass);

        double passThreshold = pass != null ? pass.getScore() : 0;
        options.add(scoreClear(ctx, passThreshold));

        DecisionOption thru = scoreThru(ctx);
        if (thru != null) options.add(thru);

        if (ctx.canShoot()) options.add(scoreShot(ctx));

        if (ctx.inFinalThird() && ctx.onWing() && ctx.inOpponentHalf()) {
            DecisionOption cross = scoreCross(ctx);
            if (cross != null) options.add(cross);
            DecisionOption center = scoreCenter(ctx);
            if (center != null) options.add(center);
        } else if (ctx.inFinalThird() && !ctx.onWing() && ctx.inOpponentHalf()) {
            DecisionOption center = scoreCenter(ctx);
            if (center != null) options.add(center);
        }

        return options;
    }

    // --- Scoring methods (identical to demo/PlaymakingDecisionEngine) ---

    private DecisionOption generateKickoffPass(DecisionContext ctx) {
        Player carrier = ctx.player();
        boolean home = ctx.isHome();
        Player bestReceiver = null;
        double bestScore = -1;
        for (Player candidate : ctx.teammates()) {
            if (isOwnGoalkeeperOrDefensiveRow(candidate, carrier.getTeam())) continue;
            boolean validRow = home ? (candidate.getPosition().getRow() < 4)
                    : (candidate.getPosition().getRow() > 4);
            if (!validRow) continue;
            double openness = receiverOpenness(candidate, ctx.opponents());
            double score = openness * 1.2 + 50;
            if (score > bestScore) { bestScore = score; bestReceiver = candidate; }
        }
        if (bestReceiver == null) return null;
        return new DecisionOption(DecisionType.PASS, bestReceiver, bestScore, "kickoff pass to backward receiver");
    }

    private DecisionOption scorePass(DecisionContext ctx) {
        Player carrier = ctx.player();
        boolean home = ctx.isHome();
        double carrierRow = carrier.getPosition().getRow();
        double carrierCol = carrier.getPosition().getColumn();

        int receiverCount = receiverCountForPM(ctx.playmaking());
        List<Player> nearest = selection.nearestTeamTo(carrier, receiverCount);
        if (nearest.isEmpty()) return null;

        boolean inFinalRows = home ? (carrierRow >= 6) : (carrierRow <= 2);
        boolean isKickoff = carrierRow == 4 && carrierCol == 3.5;

        Player bestReceiver = null;
        double bestScore = -1;

        for (Player receiver : nearest) {
            if (receiver == carrier) continue;
            if (isOwnGoalkeeperOrDefensiveRow(receiver, carrier.getTeam())) continue;
            if (inFinalRows) {
                double candidateRow = receiver.getPosition().getRow();
                boolean validRow = home ? (candidateRow >= carrierRow) : (candidateRow <= carrierRow);
                if (!validRow) continue;
            }
            if (isKickoff) {
                double candidateRow = receiver.getPosition().getRow();
                boolean validRow = home ? (candidateRow < 4) : (candidateRow > 4);
                if (!validRow) continue;
            }
            double openness = receiverOpenness(receiver, ctx.opponents());
            double quality = (receiver.getSkills().technique() + receiver.getSkills().passing()) / 2.0 * 0.8;
            double progression = forwardProgression(carrier, receiver);
            double safety = 10.0 - Math.min(10.0, receiverPressure(receiver, ctx.opponents()));
            double pressure = ctx.pressure() * 0.4;
            double score = openness * 0.5 + quality * 0.4 + progression * 0.6 + safety - pressure;
            if (score > bestScore) { bestScore = score; bestReceiver = receiver; }
        }
        if (bestReceiver == null) return null;
        return new DecisionOption(DecisionType.PASS, bestReceiver, bestScore, "pass scored");
    }

    private DecisionOption scoreThru(DecisionContext ctx) {
        Player carrier = ctx.player();
        if (!ctx.inOpponentHalf() || ctx.isGoalkeeper()) return null;
        Player runner = findThruRunner(carrier);
        if (runner == null) return null;
        double spaceBehind = spaceBehindDefense(runner, ctx.opponents());
        double receiverRun = runner.getSkills().pace() / 2.0;
        double progression = forwardProgression(carrier, runner) * 1.5;
        double interceptionRisk = interceptionRisk(carrier, runner, ctx.opponents()) * 0.5;
        double pressure = ctx.pressure() * 0.3;
        double score = spaceBehind * 0.6 + receiverRun * 0.3 + progression * 0.4
                - interceptionRisk * 0.5 - pressure;
        return new DecisionOption(DecisionType.THRU, runner, score, "thru scored");
    }

    private DecisionOption scoreCarry(DecisionContext ctx) {
        Player carrier = ctx.player();
        double availableSpace = availableForwardSpace(carrier);
        double dribbleAdvantage = (carrier.getSkills().technique() + carrier.getSkills().striker()) / 2.0 * 0.3;
        double progression = ctx.fieldPosition() / 7.0 * 20;
        double pressure = ctx.pressure() * 0.4;
        double score = availableSpace * 0.5 + dribbleAdvantage + progression - pressure;
        return new DecisionOption(DecisionType.CARRY, score, "carry scored");
    }

    private DecisionOption scoreClear(DecisionContext ctx, double passThreshold) {
        double danger = ctx.danger();
        double pressure = ctx.pressure();
        double lackOfSafeOptions = Math.max(0, 40 - passThreshold) * 0.3;
        double score = danger * 0.6 + pressure * 0.4 + lackOfSafeOptions;
        if (ctx.inOpponentHalf()) score *= 0.05;
        return new DecisionOption(DecisionType.CLEAR, score, "clear scored");
    }

    private DecisionOption scoreShot(DecisionContext ctx) {
        Player carrier = ctx.player();
        Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
        double distanceToGoal = SimUtils.distance(carrier.getPosition(), goal);
        double goalProximity = Math.max(0, (1.0 - distanceToGoal / 12.0)) * 30;
        double shootingSpace = openSpaceAround(carrier, ctx.opponents()) * 2;
        double strikerQuality = carrier.getSkills().striker() * 0.8;
        double pressure = ctx.pressure() * 0.3;
        double score = goalProximity * 1.5 + shootingSpace * 0.8 + strikerQuality - pressure;
        return new DecisionOption(DecisionType.SHOT, score, "shot scored");
    }

    private DecisionOption scoreCross(DecisionContext ctx) {
        Player carrier = ctx.player();
        int boxAttackers = countBoxAttackers(ctx);
        if (boxAttackers == 0) return null;
        double boxPresence = boxAttackers * 8.0;
        double crossingQuality = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 2.0 * 0.5;
        double progression = forwardProgression(carrier, goalPosition(ctx)) * 0.8;
        double safety = 10.0 - Math.min(10.0, ctx.pressure() * 0.3);
        double score = boxPresence * 0.4 + crossingQuality + progression * 0.3 + safety - ctx.pressure() * 0.3;
        return new DecisionOption(DecisionType.CROSS, score, "cross scored");
    }

    private DecisionOption scoreCenter(DecisionContext ctx) {
        Player carrier = ctx.player();
        int boxAttackers = countBoxAttackers(ctx);
        if (boxAttackers == 0) return null;
        double boxPresence = boxAttackers * 8.0;
        double crossingQuality = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 2.0 * 0.5;
        double progression = forwardProgression(carrier, goalPosition(ctx)) * 0.6;
        double safety = 10.0 - Math.min(10.0, ctx.pressure() * 0.2);
        double score = boxPresence * 0.4 + crossingQuality + progression * 0.3 + safety - ctx.pressure() * 0.3;
        return new DecisionOption(DecisionType.CENTER, score, "center scored");
    }

    // --- Helpers ---

    private int receiverCountForPM(double pm) {
        if (pm >= 16) return 8;
        if (pm >= 11) return 6;
        if (pm >= 6) return 5;
        return 3;
    }

    private double calculatePressure(Player carrier) {
        Position pos = carrier.getPosition();
        double pressure = 0;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            double dist = SimUtils.distance(p.getPosition(), pos);
            if (dist <= PRESSURE_RADIUS) {
                pressure += (PRESSURE_RADIUS - dist) / PRESSURE_RADIUS * 10;
            }
        }
        return Math.min(MAX_PRESSURE, pressure);
    }

    private double calculateDanger(Player carrier) {
        boolean home = "HOME".equals(carrier.getTeam());
        double ownGoalRow = home ? 1.0 : 7.0;
        double distToOwnGoal = Math.abs(carrier.getPosition().getRow() - ownGoalRow);
        return (7.0 - distToOwnGoal) / 7.0 * MAX_DANGER;
    }

    private boolean isOwnGoalkeeperOrDefensiveRow(Player player, String team) {
        if ("GK".equals(player.getRole())) return true;
        return "HOME".equals(team) ? player.getPosition().getRow() <= 1.0
                : player.getPosition().getRow() >= 7.0;
    }

    private double receiverOpenness(Player receiver, List<Player> opponents) {
        double minDist = Double.MAX_VALUE;
        for (Player opp : opponents) {
            double dist = SimUtils.distance(receiver.getPosition(), opp.getPosition());
            if (dist < minDist) minDist = dist;
        }
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }

    private double receiverPressure(Player receiver, List<Player> opponents) {
        Position pos = receiver.getPosition();
        double pressure = 0;
        for (Player p : opponents) {
            double dist = SimUtils.distance(p.getPosition(), pos);
            if (dist <= PRESSURE_RADIUS) {
                pressure += (PRESSURE_RADIUS - dist) / PRESSURE_RADIUS * 10;
            }
        }
        return Math.min(MAX_PRESSURE, pressure);
    }

    private double forwardProgression(Player carrier, Player target) {
        boolean home = "HOME".equals(carrier.getTeam());
        double progress = home
                ? (target.getPosition().getRow() - carrier.getPosition().getRow())
                : (carrier.getPosition().getRow() - target.getPosition().getRow());
        return Math.max(0, progress) * 5;
    }

    private double forwardProgression(Player carrier, Position goal) {
        boolean home = "HOME".equals(carrier.getTeam());
        double progress = home
                ? (goal.getRow() - carrier.getPosition().getRow())
                : (carrier.getPosition().getRow() - goal.getRow());
        return Math.max(0, progress) * 4;
    }

    private Player findThruRunner(Player carrier) {
        boolean home = "HOME".equals(carrier.getTeam());
        List<Player> nearest = selection.nearestTeamTo(carrier, 6);
        for (Player p : nearest) {
            if (p == carrier || "GK".equals(p.getRole())) continue;
            boolean ahead = home
                    ? p.getPosition().getRow() > carrier.getPosition().getRow()
                    : p.getPosition().getRow() < carrier.getPosition().getRow();
            if (!ahead) continue;
            if (home && p.getPosition().getRow() <= 1) continue;
            if (!home && p.getPosition().getRow() >= 7) continue;
            return p;
        }
        return null;
    }

    private double spaceBehindDefense(Player runner, List<Player> opponents) {
        boolean home = "HOME".equals(runner.getTeam());
        Position runnerPos = runner.getPosition();
        double minDist = Double.MAX_VALUE;
        for (Player opp : opponents) {
            boolean ahead = home
                    ? opp.getPosition().getRow() > runnerPos.getRow()
                    : opp.getPosition().getRow() < runnerPos.getRow();
            if (!ahead) continue;
            double dist = SimUtils.distance(runnerPos, opp.getPosition());
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 40;
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }

    private double interceptionRisk(Player carrier, Player runner, List<Player> opponents) {
        Position c = carrier.getPosition();
        Position r = runner.getPosition();
        double minDist = Double.MAX_VALUE;
        for (Player opp : opponents) {
            double dist = pointToLineDistance(opp.getPosition(), c, r);
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 0;
        return Math.max(0, 20 - minDist * 5);
    }

    private static double pointToLineDistance(Position p, Position a, Position b) {
        double dx = b.getColumn() - a.getColumn();
        double dy = b.getRow() - a.getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) return SimUtils.distance(p, a);
        double t = ((p.getColumn() - a.getColumn()) * dx + (p.getRow() - a.getRow()) * dy) / (len * len);
        t = Math.max(0, Math.min(1, t));
        double projX = a.getColumn() + t * dx;
        double projY = a.getRow() + t * dy;
        return SimUtils.distance(p, new Position(projY, projX));
    }

    private double availableForwardSpace(Player carrier) {
        boolean home = "HOME".equals(carrier.getTeam());
        double row = carrier.getPosition().getRow();
        double toGoal = home ? (7.0 - row) : (row - 1.0);
        return Math.max(0, toGoal) * 3;
    }

    private double openSpaceAround(Player carrier, List<Player> opponents) {
        double minDist = Double.MAX_VALUE;
        for (Player p : opponents) {
            double dist = SimUtils.distance(carrier.getPosition(), p.getPosition());
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 40;
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }

    private int countBoxAttackers(DecisionContext ctx) {
        boolean home = ctx.isHome();
        int count = 0;
        for (Player p : ctx.teammates()) {
            if ("GK".equals(p.getRole())) continue;
            double pr = p.getPosition().getRow();
            boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
            if (inBox) count++;
        }
        return count;
    }

    private static Position goalPosition(DecisionContext ctx) {
        return "HOME".equals(ctx.player().getTeam())
                ? ActionEngine.GOAL_POSITION : new Position(1, 3.5);
    }
}
