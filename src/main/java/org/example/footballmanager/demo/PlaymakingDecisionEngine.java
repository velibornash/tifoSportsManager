package org.example.footballmanager.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Odgovornost: PLAYMAKING KAO DECISION-QUALITY LAYER.
 *
 * <p>Ovaj engine zamenjuje nasumičan izbor akcija u {@link SimulationStepEngine}
 * sa playmaking-omerno informisanim odlukama. Ključna filozofija:</p>
 *
 * <pre>
 * PASSING → execution quality (ExecutionQuality)
 * PLAYMAKING → decision quality (ovaj engine)
 * </pre>
 *
 * <p>Playmaking ne meri <em>kako dobro</em> igrač izvede pas — već <em>kako dobro
 * vidi opcije</em> i <em>koliko često bira najbolju</em>.Svaki put kada igrač
 * dobije loptu, engine:</p>
 * <ol>
 *   <li>Generiše moguće akcije (zavisno od situacije)</li>
 *   <li>Primenjuje PM vidni filter (koji tipovi su vidljivi)</li>
 *   <li>Poenišjava vidljive opcije (grubo, transparentno)</li>
 *   <li>Bira opciju na osnovu tačnosti (PM tabelirana)</li>
 * </ol>
 *
 * <p>Izbor nije 100% maksimalan — niži PM izaziva karakter igrača kroz
 * podunoćane odluke.</p>
 *
 * <p>Konstrukcija: {@link PlaymakingDecisionEngine(state, selection, random)}.</p>
 *
 * @see DecisionType
 * @see DecisionOption
 * @see DecisionContext
 */
public class PlaymakingDecisionEngine {

    /** Opponent proximity threshold for pressure weighting (cells). */
    private static final double PRESSURE_RADIUS = 4.0;
    /** Max pressure sum (normalized). */
    private static final double MAX_PRESSURE = 50.0;
    /** Max danger value (normalized). */
    private static final double MAX_DANGER = 50.0;

    private final SimulationState state;
    private final PlayerSelectionEngine selection;
    private final OptionSelector selector;
    private final VisionFilter visionFilter;

    public PlaymakingDecisionEngine(SimulationState state, PlayerSelectionEngine selection, Random random) {
        this.state = state;
        this.selection = selection;
        this.selector = new OptionSelector(random);
        this.visionFilter = new VisionFilter(random);
    }

    /**
     * Glavni ulaz: generiše i bira jednu opcija na osnovu trenutnog nosaoca.
     *
     * @return {@link DecisionOption} sa izabranim {@link DecisionType}-om i
     *         opcionim ciljnim igračem (receiver za PASS/THRU).
     */
    public DecisionOption decide() {
        Player carrier = state.getCarrier();
        if (carrier == null) {
            DecisionOption fallback = new DecisionOption(DecisionType.CARRY, 0,
                    "no carrier — fallback to CARRY");
            logDecision(null, List.of(fallback), List.of(fallback), fallback);
            return fallback;
        }

        DecisionContext ctx = buildContext(carrier);
        List<DecisionOption> options = generateOptions(ctx);
        visionFilter.applyVisionFilter(ctx, options);

        List<DecisionOption> visible = options.stream()
                .filter(DecisionOption::isVisible)
                .collect(Collectors.toList());

        if (visible.isEmpty()) {
            // Safety net: ensure at least PASS or CARRY is visible
            for (DecisionOption opt : options) {
                if (opt.getType() == DecisionType.PASS || opt.getType() == DecisionType.CARRY) {
                    opt.setVisible(true);
                }
            }
            visible = options.stream()
                    .filter(DecisionOption::isVisible)
                    .collect(Collectors.toList());
        }

        DecisionOption selected = selector.select(ctx, visible);
        logDecision(ctx, options, visible, selected);
        return selected;
    }

    // ── Context building ──────────────────────────────────────────

    private DecisionContext buildContext(Player carrier) {
        Position pos = carrier.getPosition();
        double row = pos.getRow();
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());

        boolean isGoalkeeper = "GK".equals(carrier.getRole());
        boolean canShoot = !isGoalkeeper && (home ? row >= ActionEngine.SHOOT_MIN_ROW : row <= 3);
        boolean inFinalThird = home ? row >= 6 : row <= 2;
        boolean onWing = pos.getColumn() <= 2 || pos.getColumn() >= 5;
        boolean inOpponentHalf = home ? row >= 4 : row <= 4;
        boolean isKickoff = row == 4 && pos.getColumn() == 3.5
                && (state.getRound() == 1 || state.isCelebrating());

        double pressure = calculatePressure(carrier);
        double danger = calculateDanger(carrier);
        double fieldPosition = home ? row : 8 - row; // 1..7, how far forward

        // Teammates: all eligible (not locked, not blocked-after-duel), excluding carrier
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

        double pm = carrier.getSkills().playmaking();

        return new DecisionContext(
                carrier,
                state.getBall().getBallState(),
                state.getBall().getPosition(),
                teammates, opponents,
                pressure, danger, fieldPosition, pm,
                home, isGoalkeeper, inFinalThird, onWing, inOpponentHalf, canShoot, isKickoff,
                new ArrayList<>());
    }

    // ── Option generation ─────────────────────────────────────────

    private List<DecisionOption> generateOptions(DecisionContext ctx) {
        List<DecisionOption> options = new ArrayList<>();
        Player carrier = ctx.player();

        if (ctx.isKickoff()) {
            // Kickoff: only backward PASS
            DecisionOption pass = generateKickoffPass(ctx);
            if (pass != null) options.add(pass);
            options.add(new DecisionOption(DecisionType.CARRY, 0,
                    "kickoff fallback CARRY"));
            return options;
        }

        // CARRY for non-goalkeepers
        if (!ctx.isGoalkeeper()) {
            options.add(scoreCarry(ctx));
        }

        // PASS — must be scored BEFORE CLEAR so CLEAR can use the real pass score
        DecisionOption pass = scorePass(ctx);
        if (pass != null) options.add(pass);

        // CLEAR is always a legal option (never automatic — scored against everything else).
        // passThreshold = best PASS score (0 if no PASS available)
        double passThreshold = pass != null ? pass.getScore() : 0;
        options.add(scoreClear(ctx, passThreshold));

        // THRU — only in opponent half with a runner ahead
        DecisionOption thru = scoreThru(ctx);
        if (thru != null) options.add(thru);

        // SHOT — only when canShoot
        if (ctx.canShoot()) {
            options.add(scoreShot(ctx));
        }

        // CROSS / CENTER — only in final third
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

    // ── Scoring ────────────────────────────────────────────────────

    /** Generate a kickoff pass to the nearest backward teammate. */
    private DecisionOption generateKickoffPass(DecisionContext ctx) {
        Player carrier = ctx.player();
        boolean home = ctx.isHome();
        double carrierRow = carrier.getPosition().getRow();

        Player bestReceiver = null;
        double bestScore = -1;

        for (Player candidate : ctx.teammates()) {
            if (isOwnGoalkeeperOrDefensiveRow(candidate, carrier.getTeam())) continue;
            double candidateRow = candidate.getPosition().getRow();
            // Kickoff: receiver must be behind the carrier (in own half)
            boolean validRow = home ? (candidateRow < 4) : (candidateRow > 4);
            if (!validRow) continue;

            double openness = receiverOpenness(candidate, ctx.opponents());
            double score = openness * 1.2 + 50; // kickoff pass always safe-ish
            if (score > bestScore) {
                bestScore = score;
                bestReceiver = candidate;
            }
        }

        if (bestReceiver == null) {
            return null;
        }
        return new DecisionOption(DecisionType.PASS, bestReceiver, bestScore,
                "kickoff pass to backward receiver");
    }

    /**
     * PASS SCORE = target_openness + target_quality + forward_progression + safety − pressure
     */
    private DecisionOption scorePass(DecisionContext ctx) {
        Player carrier = ctx.player();
        boolean home = ctx.isHome();
        double carrierRow = carrier.getPosition().getRow();
        double carrierCol = carrier.getPosition().getColumn();

        // Consider more receivers for higher PM (vision breadth)
        int receiverCount = receiverCountForPM(ctx.playmaking());
        List<Player> nearest = selection.nearestTeamTo(carrier, receiverCount);
        if (nearest.isEmpty()) return null;

        boolean inFinalRows = home ? (carrierRow >= 6) : (carrierRow <= 2);
        boolean isKickoff = carrierRow == 4 && carrierCol == 3.5;

        Player bestReceiver = null;
        double bestScore = -1;
        String bestReason = "";

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

            if (score > bestScore) {
                bestScore = score;
                bestReceiver = receiver;
                bestReason = String.format(Locale.ROOT,
                        "openness=%.1f quality=%.1f prog=%.1f safety=%.1f pressure=%.1f",
                        openness, quality, progression, safety, pressure);
            }
        }

        if (bestReceiver == null) {
            return null; // No eligible receiver — PASS not available as option
        }

        // If no eligible receiver found, CLEAR fallback will dominate
        if (bestScore < 30) {
            bestReason += " [WEAK PASS — no safe options]";
        }

        return new DecisionOption(DecisionType.PASS, bestReceiver, bestScore, bestReason);
    }

    /**
     * THRU SCORE = space_behind_defense + receiver_run + progression − interception_risk − pressure
     * Only available in opponent half with a runner ahead of the carrier.
     */
    private DecisionOption scoreThru(DecisionContext ctx) {
        Player carrier = ctx.player();
        if (!ctx.inOpponentHalf()) return null;
        if (ctx.isGoalkeeper()) return null;

        Player runner = findThruRunner(carrier);
        if (runner == null) return null;

        double spaceBehind = spaceBehindDefense(runner, ctx.opponents());
        double receiverRun = runner.getSkills().pace() / 2.0; // 0.5-10
        double progression = forwardProgression(carrier, runner) * 1.5; // thru passes are high-value progression
        double interceptionRisk = interceptionRisk(carrier, runner, ctx.opponents()) * 0.5;
        double pressure = ctx.pressure() * 0.3;

        double score = spaceBehind * 0.6 + receiverRun * 0.3 + progression * 0.4
                - interceptionRisk * 0.5 - pressure;

        String reason = String.format(Locale.ROOT,
                "space=%.1f run=%.1f prog=%.1f intRisk=%.1f pressure=%.1f",
                spaceBehind, receiverRun, progression, interceptionRisk, pressure);

        return new DecisionOption(DecisionType.THRU, runner, score, reason);
    }

    /**
     * CARRY SCORE = available_space + dribble_advantage + progression − pressure
     */
    private DecisionOption scoreCarry(DecisionContext ctx) {
        Player carrier = ctx.player();
        double availableSpace = availableForwardSpace(carrier);
        double dribbleAdvantage = (carrier.getSkills().technique() + carrier.getSkills().striker()) / 2.0 * 0.3;
        double progression = ctx.fieldPosition() / 7.0 * 20; // further forward = more to gain by carrying
        double pressure = ctx.pressure() * 0.4;

        double score = availableSpace * 0.5 + dribbleAdvantage + progression - pressure;

        String reason = String.format(Locale.ROOT,
                "space=%.1f dribble=%.1f prog=%.1f pressure=%.1f",
                availableSpace, dribbleAdvantage, progression, pressure);

        return new DecisionOption(DecisionType.CARRY, score, reason);
    }

    /**
     * CLEAR SCORE = defensive_danger + pressure + lack_of_safe_options
     *
     * <p>CLEAR is NEVER automatic — it is a scored option whose weight rises
     * with defensive danger, pressure, and absence of safe passing options.
     * Even defenders can choose PASS/CARRY if those options score higher.</p>
     */
    private DecisionOption scoreClear(DecisionContext ctx, double passThreshold) {
        Player carrier = ctx.player();
        double danger = ctx.danger();
        double pressure = ctx.pressure();

        // Lack of safe options: if no good PASS exists, CLEAR becomes more attractive
        double lackOfSafeOptions = Math.max(0, 40 - passThreshold) * 0.3;

        double score = danger * 0.6 + pressure * 0.4 + lackOfSafeOptions;

        // CLEAR is a defensive action: only attractive near own goal.
        // NEVER attractive when in the opponent's half (attacking zone) —
        // the player should PASS, CARRY, SHOT, or THRU instead.
        if (ctx.inOpponentHalf()) {
            score *= 0.05; // near-zero — eliminates CLEAR as a real choice when attacking
        }

        String reason = String.format(Locale.ROOT,
                "danger=%.1f pressure=%.1f noSafeOpts=%.1f oppHalf=%s",
                danger, pressure, lackOfSafeOptions, ctx.inOpponentHalf());

        return new DecisionOption(DecisionType.CLEAR, score, reason);
    }

    /**
     * SHOT SCORE = goal_proximity + shooting_space + striker_quality − pressure
     *
     * <p>SHOT must be competitive with CLEAR when the carrier is in a genuine
     * shooting position (close to goal, good striker skill, some space).</p>
     */
    private DecisionOption scoreShot(DecisionContext ctx) {
        Player carrier = ctx.player();
        boolean home = ctx.isHome();
        Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
        double distanceToGoal = MovementEngine.distance(carrier.getPosition(), goal);
        double goalProximity = Math.max(0, (1.0 - distanceToGoal / 12.0)) * 30; // closer = higher
        double shootingSpace = openSpaceAround(carrier, ctx.opponents()) * 2;
        double strikerQuality = carrier.getSkills().striker() * 0.8; // 0.8-16
        double pressure = ctx.pressure() * 0.3;

        double score = goalProximity * 1.5 + shootingSpace * 0.8 + strikerQuality - pressure;

        String reason = String.format(Locale.ROOT,
                "prox=%.1f space=%.1f strike=%.1f pressure=%.1f",
                goalProximity, shootingSpace, strikerQuality, pressure);

        return new DecisionOption(DecisionType.SHOT, score, reason);
    }

    /**
     * CROSS: from the wing into the box.
     * Score based on box presence and crossing quality.
     */
    private DecisionOption scoreCross(DecisionContext ctx) {
        Player carrier = ctx.player();
        int boxAttackers = countBoxAttackers(ctx);
        if (boxAttackers == 0) return null;

        double boxPresence = boxAttackers * 8.0; // 8-64
        double crossingQuality = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 2.0 * 0.5;
        double progression = forwardProgression(carrier, goalPosition(ctx)) * 0.8;
        double safety = 10.0 - Math.min(10.0, ctx.pressure() * 0.3);

        double score = boxPresence * 0.4 + crossingQuality + progression * 0.3 + safety - ctx.pressure() * 0.3;

        String reason = String.format(Locale.ROOT,
                "boxAttackers=%d boxPresence=%.1f cross=%.1f prog=%.1f",
                boxAttackers, boxPresence, crossingQuality, progression);

        return new DecisionOption(DecisionType.CROSS, score, reason);
    }

    /**
     * CENTER: from central position into the box.
     * Score based on box presence.
     */
    private DecisionOption scoreCenter(DecisionContext ctx) {
        Player carrier = ctx.player();
        int boxAttackers = countBoxAttackers(ctx);
        if (boxAttackers == 0) return null;

        double boxPresence = boxAttackers * 8.0;
        double crossingQuality = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 2.0 * 0.5;
        double progression = forwardProgression(carrier, goalPosition(ctx)) * 0.6;
        double safety = 10.0 - Math.min(10.0, ctx.pressure() * 0.2);

        double score = boxPresence * 0.4 + crossingQuality + progression * 0.3 + safety - ctx.pressure() * 0.3;

        String reason = String.format(Locale.ROOT,
                "boxAttackers=%d boxPresence=%.1f cross=%.1f prog=%.1f",
                boxAttackers, boxPresence, crossingQuality, progression);

        return new DecisionOption(DecisionType.CENTER, score, reason);
    }

    // ── Scoring helpers ───────────────────────────────────────────

    private int receiverCountForPM(double pm) {
        // More receivers visible to higher PM players
        if (pm >= 16) return 8;
        if (pm >= 11) return 6;
        if (pm >= 6) return 5;
        return 3; // PM 1-5: limited vision, nearest few
    }

    private double calculatePressure(Player carrier) {
        Position pos = carrier.getPosition();
        double pressure = 0;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            double dist = MovementEngine.distance(p.getPosition(), pos);
            if (dist <= PRESSURE_RADIUS) {
                pressure += (PRESSURE_RADIUS - dist) / PRESSURE_RADIUS * 10;
            }
        }
        return Math.min(MAX_PRESSURE, pressure);
    }

    private double calculateDanger(Player carrier) {
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        double ownGoalRow = home ? 1.0 : 7.0;
        double distToOwnGoal = Math.abs(carrier.getPosition().getRow() - ownGoalRow);
        return (7.0 - distToOwnGoal) / 7.0 * MAX_DANGER;
    }

    private boolean isOwnGoalkeeperOrDefensiveRow(Player player, String team) {
        if ("GK".equals(player.getRole())) return true;
        return SimulationState.TEAM_HOME.equals(team)
                ? player.getPosition().getRow() <= 1.0
                : player.getPosition().getRow() >= 7.0;
    }

    /**
     * Openness of a receiver: distance to nearest opponent (0-4 cells → 0-40 scale).
     * Higher = more open.
     */
    private double receiverOpenness(Player receiver, List<Player> opponents) {
        double minDist = Double.MAX_VALUE;
        for (Player opp : opponents) {
            double dist = MovementEngine.distance(receiver.getPosition(), opp.getPosition());
            if (dist < minDist) minDist = dist;
        }
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }

    /**
     * Pressure on a specific receiver: distance-weighted count of nearby opponents.
     * Returns 0-50.
     */
    private double receiverPressure(Player receiver, List<Player> opponents) {
        Position pos = receiver.getPosition();
        double pressure = 0;
        for (Player p : opponents) {
            double dist = MovementEngine.distance(p.getPosition(), pos);
            if (dist <= PRESSURE_RADIUS) {
                pressure += (PRESSURE_RADIUS - dist) / PRESSURE_RADIUS * 10;
            }
        }
        return Math.min(MAX_PRESSURE, pressure);
    }

    /**
     * Forward progression: how much closer to goal the target is vs carrier.
     * For HOME: higher row = closer to goal. For AWAY: lower row = closer to goal.
     */
    private double forwardProgression(Player carrier, Player target) {
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        double carrierRow = carrier.getPosition().getRow();
        double targetRow = target.getPosition().getRow();
        double progress = home ? (targetRow - carrierRow) : (carrierRow - targetRow);
        return Math.max(0, progress) * 5; // 0-30ish
    }

    /**
     * Forward progression from carrier toward a goal position.
     */
    private double forwardProgression(Player carrier, Position goal) {
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        double carrierRow = carrier.getPosition().getRow();
        double goalRow = goal.getRow();
        double progress = home ? (goalRow - carrierRow) : (carrierRow - goalRow);
        return Math.max(0, progress) * 4;
    }

    /**
     * Find a teammate ahead of the carrier who can receive a thru pass
     * (replicates ActionEngine.findThruRunner logic).
     */
    private Player findThruRunner(Player carrier) {
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        List<Player> nearest = selection.nearestTeamTo(carrier, 6);
        for (Player p : nearest) {
            if (p == carrier) continue;
            if ("GK".equals(p.getRole())) continue;
            boolean ahead = home
                    ? p.getPosition().getRow() > carrier.getPosition().getRow()
                    : p.getPosition().getRow() < carrier.getPosition().getRow();
            if (!ahead) continue;
            // Forbidden rows — own defensive zone
            if (home && p.getPosition().getRow() <= 1) continue;
            if (!home && p.getPosition().getRow() >= 7) continue;
            return p;
        }
        return null;
    }

    /**
     * Space behind defense: distance from runner to the nearest opponent
     * that is ahead of them (toward the goal).
     */
    private double spaceBehindDefense(Player runner, List<Player> opponents) {
        boolean home = SimulationState.TEAM_HOME.equals(runner.getTeam());
        Position runnerPos = runner.getPosition();
        double minDist = Double.MAX_VALUE;
        for (Player opp : opponents) {
            double oppRow = opp.getPosition().getRow();
            // Only consider opponents ahead of the runner (closer to goal)
            boolean ahead = home ? oppRow > runnerPos.getRow() : oppRow < runnerPos.getRow();
            if (!ahead) continue;
            double dist = MovementEngine.distance(runnerPos, opp.getPosition());
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) {
            // No defenders ahead — lots of space
            return 40;
        }
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }

    /**
     * Interception risk: nearest opponent distance to the line from carrier to runner.
     * Lower distance = higher risk.
     */
    private double interceptionRisk(Player carrier, Player runner, List<Player> opponents) {
        Position c = carrier.getPosition();
        Position r = runner.getPosition();
        double minDist = Double.MAX_VALUE;
        for (Player opp : opponents) {
            double dist = pointToLineDistance(opp.getPosition(), c, r);
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 0;
        return Math.max(0, 20 - minDist * 5); // closer = higher risk
    }

    private static double pointToLineDistance(Position p, Position a, Position b) {
        double dx = b.getColumn() - a.getColumn();
        double dy = b.getRow() - a.getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) {
            return MovementEngine.distance(p, a);
        }
        // Projection
        double t = ((p.getColumn() - a.getColumn()) * dx + (p.getRow() - a.getRow()) * dy) / (len * len);
        t = Math.max(0, Math.min(1, t));
        double projX = a.getColumn() + t * dx;
        double projY = a.getRow() + t * dy;
        return MovementEngine.distance(p, new Position(projY, projX));
    }

    /**
     * Available forward space for the carrier: check cells ahead and
     * how far the carrier can move before being blocked.
     */
    private double availableForwardSpace(Player carrier) {
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        Position pos = carrier.getPosition();
        double row = pos.getRow();
        // Distance to own goal line (for forward direction)
        double toGoal = home ? (7.0 - row) : (row - 1.0);
        double toOwnGoal = home ? (row - 1.0) : (7.0 - row);
        // More space forward = higher score
        return Math.max(0, toGoal) * 3;
    }

    /**
     * Open space around the carrier (for shooting): min distance to nearest opponent.
     */
    private double openSpaceAround(Player carrier, List<Player> opponents) {
        double minDist = Double.MAX_VALUE;
        for (Player p : opponents) {
            double dist = MovementEngine.distance(carrier.getPosition(), p.getPosition());
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 40;
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }

    private int countBoxAttackers(DecisionContext ctx) {
        Player carrier = ctx.player();
        boolean home = ctx.isHome();
        int count = 0;
        for (Player p : ctx.teammates()) {
            double pr = p.getPosition().getRow();
            boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
            if (inBox) count++;
        }
        return count;
    }

    private static Position goalPosition(DecisionContext ctx) {
        return SimulationState.TEAM_HOME.equals(ctx.player().getTeam())
                ? ActionEngine.GOAL_POSITION : new Position(1, 3.5);
    }

    // ── Debug logging ────────────────────────────────────────────

    private void logDecision(DecisionContext ctx,
                             List<DecisionOption> allOptions,
                             List<DecisionOption> visible,
                             DecisionOption selected) {
        Player carrier = ctx.player();
        StringBuilder sb = new StringBuilder();
        sb.append("DECISION: ").append(carrier.getLabel())
          .append(" [PM=" + String.format("%.0f", ctx.playmaking()) + "]")
          .append(" pos=").append(formatPos(carrier.getPosition()))
          .append(" pressure=").append(String.format("%.1f", ctx.pressure()))
          .append(" danger=").append(String.format("%.1f", ctx.danger()));

        state.log(sb.toString());

        // Log all options with visibility marker
        for (DecisionOption opt : allOptions) {
            String marker = opt.isVisible() ? "[V]" : "[ ]";
            state.log("  " + marker + " " + opt.getType()
                    + (opt.getTarget() != null ? "->" + opt.getTarget().getLabel() : "")
                    + " score=" + String.format("%.1f", opt.getScore())
                    + " | " + opt.getReason());
        }

        state.log("SELECTED: " + selected.getType()
                + (selected.getTarget() != null ? "->" + selected.getTarget().getLabel() : "")
                + " | " + selected.getReason());
    }

    private static String formatPos(Position p) {
        return String.format(Locale.ROOT, "(%.2f,%.2f)", p.getRow(), p.getColumn());
    }
}
