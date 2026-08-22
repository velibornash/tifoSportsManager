package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ThreatAssessmentService threatService;
    private final PlayerPerceptionService perceptionService;
    private List<DecisionOption> lastScoredOptions = new ArrayList<>();
    private DecisionOption bestPassFallback = null;
    private String lastSelectionReason = "";
    // Pass exchange tracking: max 3 consecutive passes between same two players
    private final Map<String, Integer> passExchangeCount = new HashMap<>();
    private static final int MAX_PASS_EXCHANGES = 3;
    private String lastCarrierTeam = null;

    public PlaymakingDecisionEngine(MatchState state, PlayerSelectionEngine selection,
                                    ThreatAssessmentService threatService,
                                    PlayerPerceptionService perceptionService,
                                    java.util.Random random) {
        this.state = state;
        this.selection = selection;
        this.threatService = threatService;
        this.perceptionService = perceptionService;
        this.selector = new OptionSelector(random);
        this.visionFilter = new VisionFilter(random);
    }

    public List<DecisionOption> getLastScoredOptions() {
        return new ArrayList<>(lastScoredOptions);
    }

    public DecisionOption getBestPassFallback() {
        return bestPassFallback;
    }

    public String getLastSelectionReason() {
        return selector.getLastSelectionReason();
    }

    /**
     * Record a pass from one player to another for exchange limit tracking.
     */
    public void recordPassExchange(String passerId, String receiverId) {
        String key = passerId + "->" + receiverId;
        String reverseKey = receiverId + "->" + passerId;
        // Count both directions: A→B and B→A count toward the same exchange pair
        int count = Math.max(passExchangeCount.getOrDefault(key, 0),
                passExchangeCount.getOrDefault(reverseKey, 0));
        passExchangeCount.put(key, count + 1);
    }

    /**
     * Reset pass exchange tracking (call when possession changes or new action starts).
     */
    public void resetPassExchanges() {
        passExchangeCount.clear();
    }

    /**
     * Check if a pass to the given receiver would exceed the exchange limit.
     */
    private boolean isPassExchangeLimitReached(String passerId, String receiverId) {
        String key = passerId + "->" + receiverId;
        String reverseKey = receiverId + "->" + passerId;
        int count = Math.max(passExchangeCount.getOrDefault(key, 0),
                passExchangeCount.getOrDefault(reverseKey, 0));
        return count >= MAX_PASS_EXCHANGES;
    }

    public DecisionOption decide() {
        Player carrier = state.getCarrier();
        if (carrier == null) {
            return new DecisionOption(DecisionType.CARRY, 0, "no carrier — fallback to CARRY");
        }

        // Reset pass exchanges when possession changes teams
        String currentTeam = carrier.getTeam();
        if (lastCarrierTeam != null && !currentTeam.equals(lastCarrierTeam)) {
            resetPassExchanges();
        }
        lastCarrierTeam = currentTeam;

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

        DecisionOption result = selector.select(ctx, visible);
        lastSelectionReason = selector.getLastSelectionReason();

        // If the engine still wants to clear, pick the BEST-SCORED pass/carry (not first)
        if (result.getType() == DecisionType.CLEAR) {
            DecisionOption bestAlternative = null;
            for (DecisionOption opt : visible) {
                if ((opt.getType() == DecisionType.PASS || opt.getType() == DecisionType.CARRY)
                        && opt.getScore() > 0) {
                    if (bestAlternative == null || opt.getScore() > bestAlternative.getScore()) {
                        bestAlternative = opt;
                    }
                }
            }
            if (bestAlternative != null) {
                result = bestAlternative;
            }
        }

        lastScoredOptions = new ArrayList<>(visible);

        // Store best PASS option as fallback for THRU/CROSS/CENTER failures
        bestPassFallback = visible.stream()
                .filter(o -> o.getType() == DecisionType.PASS && o.getScore() > 0)
                .max(java.util.Comparator.comparingDouble(DecisionOption::getScore))
                .orElse(null);

        return result;
    }

    private DecisionContext buildContext(Player carrier) {
        Position pos = carrier.getPosition();
        double row = pos.getRow();
        boolean home = "HOME".equals(carrier.getTeam());

        boolean isGoalkeeper = "GK".equals(carrier.getRole());
        boolean canShoot = !isGoalkeeper && (home ? row >= ActionEngine.SHOOT_MIN_ROW : row <= 8 - ActionEngine.SHOOT_MIN_ROW);
        boolean inFinalThird = home ? row >= 5 : row <= 3;
        boolean onWing = pos.getColumn() <= 2 || pos.getColumn() >= 5;
        boolean inOpponentHalf = home ? row >= 4 : row <= 4;
        boolean isKickoff = state.isKickoffActionPending()
                || (row == 4 && pos.getColumn() == 3.5
                && (state.getRound() == 1 || state.isCelebrating()));

        // Use ThreatAssessmentService for danger (corePrinciples §6, §4.4).
        // Pressure is computed directly from nearby opponents — ThreatAssessmentService
        // returns 0 for the carrier's team which would make pressure meaningless.
        PlayerPerceptionService.PlayerPerception perception = perceptionService.perceive(carrier);
        double pressure = calculateCarrierPressure(carrier) * MAX_PRESSURE;
        double danger = threatService.evaluatePlayerThreat(carrier).teamThreatScore() * MAX_DANGER;
        double fieldPosition = home ? row : 8 - row;

        // Teammates/opponents filtered through perception (imperfect knowledge, §4.5)
        List<Player> teammates = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p == carrier) continue;
            if (!p.getTeam().equals(carrier.getTeam())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured() || state.isBlockedAfterDuel(p)) continue;
            teammates.add(p);
        }

        List<Player> opponents = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured() || state.isBlockedAfterDuel(p)) continue;
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
        double pm = ctx.playmaking();

        if (ctx.isKickoff()) {
            DecisionOption pass = generateKickoffPass(ctx);
            if (pass != null) options.add(pass);
            options.add(new DecisionOption(DecisionType.CARRY, 0, "kickoff fallback CARRY"));
            return options;
        }

        // PM-based scoring adjustments (corePrinciples §9.4):
        // Low PM players lack vision and composure. They see fewer options,
        // default to CLEAR under pressure, and rarely attempt CARRY or SHOT.
        // High PM players see all options and make better choices.
        double pmClearBonus = 0;
        double pmCarryPenalty = 0;
        double pmShotPenalty = 0;
        if (pm < 6) {
            // Low PM: CLEAR dominates, CARRY almost never, SHOT rarely
            pmClearBonus = 10.0;
            pmCarryPenalty = 15.0;
            pmShotPenalty = 8.0;
        } else if (pm < 11) {
            // Medium PM: mild CLEAR preference
            pmClearBonus = 4.0;
            pmCarryPenalty = 3.0;
            pmShotPenalty = 2.0;
        }
        // High PM (11+): no adjustment

        if (!ctx.isGoalkeeper() && !state.isSetPiecePending()) {
            DecisionOption carry = scoreCarry(ctx);
            carry.setScore(carry.getScore() - pmCarryPenalty);
            options.add(carry);
        }

        DecisionOption pass = scorePass(ctx);
        if (pass != null) options.add(pass);

        double passThreshold = pass != null ? pass.getScore() : 0;
        DecisionOption clear = scoreClear(ctx, passThreshold);
        clear.setScore(clear.getScore() + pmClearBonus);
        options.add(clear);

        DecisionOption thru = scoreThru(ctx);
        if (thru != null) options.add(thru);

        if (ctx.canShoot()) {
            DecisionOption shot = scoreShot(ctx);
            shot.setScore(shot.getScore() - pmShotPenalty);
            options.add(shot);
        }

        // CROSS: from wing in final third only — generates 15-25 crosses/match
        if (ctx.inFinalThird() && ctx.onWing()) {
            double carrierRow = ctx.player().getPosition().getRow();
            boolean home = ctx.isHome();
            boolean inTheBox = home ? (carrierRow >= 6) : (carrierRow <= 2);
            if (!inTheBox) {
                DecisionOption cross = scoreCross(ctx);
                if (cross != null) options.add(cross);
            }
        }
        // CENTER: from central positions in final third — generates 10-15 centers/match
        if (ctx.inFinalThird() && !ctx.onWing()) {
            double carrierRow = ctx.player().getPosition().getRow();
            boolean home = ctx.isHome();
            boolean inTheBox = home ? (carrierRow >= 6) : (carrierRow <= 2);
            if (!inTheBox) {
                DecisionOption center = scoreCenter(ctx);
                if (center != null) options.add(center);
            }
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
            double col = candidate.getPosition().getColumn();
            double sidelineDist = Math.min(col - 1, 6 - col);
            // Quadratic sideline penalty — very harsh for receivers near the touchline
            // to prevent kickoff passes going out of bounds.
            double sidelinePenalty = sidelineDist < 1.5 ? Math.pow(1.5 - sidelineDist, 2) * -15.0 : 0;
            // Reward receivers near the centre column (3.5) to steer kickoff passes
            // toward the middle of the pitch where they are safest.
            double centerBonus = (4.0 - Math.abs(col - 3.5)) * 3.0;
            double score = openness * 1.2 + 30 + sidelinePenalty + centerBonus;
            if (score > bestScore) {
                bestScore = score;
                bestReceiver = candidate;
            }
        }
        if (bestReceiver == null) return null;
        return new DecisionOption(DecisionType.PASS, bestReceiver, bestScore, "kickoff pass to backward receiver");
    }

    private DecisionOption scorePass(DecisionContext ctx) {
        Player carrier = ctx.player();
        boolean home = ctx.isHome();
        double carrierRow = carrier.getPosition().getRow();
        double carrierCol = carrier.getPosition().getColumn();

        // GK distributes to more players (not just 2 nearest) to avoid always passing to the same striker
        int receiverCount = ctx.isGoalkeeper() ? 5 : receiverCountForPM(ctx.playmaking());
        List<Player> nearest = selection.nearestTeamTo(carrier, receiverCount);
        if (nearest.isEmpty()) return null;

        boolean inFinalRows = home ? (carrierRow >= 6) : (carrierRow <= 2);
        boolean isKickoff = carrierRow == 4 && carrierCol == 3.5;

        Player bestReceiver = null;
        double bestScore = -1;
        int goodReceivers = 0; // count receivers with score > 10 (for team bonus)

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
            double quality = (receiver.getSkills().technique() + receiver.getSkills().passing()) / 2.0 * 0.6;
            double progression = forwardProgression(carrier, receiver) * 1.2;
            double lateralBackwardPenalty = lateralBackwardPenalty(carrier, receiver);
            double safety = 10.0 - Math.min(10.0, receiverPressure(receiver, ctx.opponents()));
            double pressure = ctx.pressure() * 0.2;
            double col = receiver.getPosition().getColumn();
            double sidelineDist = Math.min(col - 1, 6 - col);
            double sidelinePenalty = sidelineDist < 1.0 ? (1.0 - sidelineDist) * -6.0 : 0;
            boolean clearLane = isPassingLaneClear(carrier, receiver, ctx.opponents());
            double laneBonus = clearLane ? 3.0 : -1.5;
            // Distance penalty: long passes are riskier (higher deviation, more OOB)
            double passDistance = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
            // Skill-based distance preference: low-skill carriers heavily penalize long passes
            // passing 1-5 → 2.5x penalty, 6-10 → 1.5x, 11-15 → 1.0x, 16-20 → 0.5x
            double carrierPassing = carrier.getSkills().passing();
            double skillDistanceMultiplier = Math.max(0.5, 2.5 - (carrierPassing / 20.0) * 2.0);
            double distancePenalty = passDistance > 4.0 ? (passDistance - 4.0) * 3.0 * skillDistanceMultiplier : 0.0;
            // Minimum distance requirement: passes shorter than 1.5 cells are risky and penalized
            double minPassDistance = 1.5;
            double minPassPenalty = passDistance < minPassDistance ? (minPassDistance - passDistance) * 4.0 : 0.0;
            double totalDistancePenalty = distancePenalty + minPassPenalty;
            // Carrier skill bonus: good passers get a boost, bad passers are penalized
            double carrierSkillBonus = (carrierPassing - 10.0) * 0.5;
            double openSafBuf = (progression > 0) ? (openness * 0.4 + safety) : (openness * 0.3 + safety * 0.5);
            double score = quality * 0.5 + progression * 0.8 + openSafBuf - pressure
                    - lateralBackwardPenalty * 0.8 - laneBonus * 0.8 - sidelinePenalty * 0.8 - totalDistancePenalty * 0.8
                    + carrierSkillBonus;
            // Pass exchange limit: penalize passes that would exceed 3 exchanges
            // between the same two players (prevents ping-pong passing)
            if (isPassExchangeLimitReached(carrier.getId(), receiver.getId())) {
                score -= 25.0;
            }
            // Consecutive offside penalty: receivers who keep getting caught offside
            // should be avoided — the team needs to drop them deeper or find alternatives.
            if (receiver.getConsecutiveOffsideCount() >= 3) {
                score -= 200.0;
            } else if (receiver.getConsecutiveOffsideCount() >= 2) {
                score -= 80.0;
            } else if (receiver.getConsecutiveOffsideCount() >= 1) {
                score -= 20.0;
            }
            // Pre-check offside risk: if receiver is ahead of almost all defenders,
            // penalize the pass to avoid wasting possession on offside.
            // Check from ANY row, not just the final third — attackers can be offside anywhere.
            if (progression > 0) {
                int defendersAhead = 0;
                for (Player opp : ctx.opponents()) {
                    if ("GK".equals(opp.getRole())) continue;
                    boolean ahead = home
                            ? opp.getPosition().getRow() >= receiver.getPosition().getRow() - 0.3
                            : opp.getPosition().getRow() <= receiver.getPosition().getRow() + 0.3;
                    if (ahead) defendersAhead++;
                }
                if (defendersAhead == 0) {
                    score -= 30.0; // receiver is in offside position — don't pass there
                } else if (defendersAhead == 1) {
                    score -= 10.0; // marginal — risky
                }
            }
            if (score > 10) goodReceivers++;
            if (score > bestScore) {
                bestScore = score;
                bestReceiver = receiver;
            }
        }
        if (bestReceiver == null) return null;
        // Team option bonus: more open teammates = passing is more viable as a strategy.
        // This prevents CARRY from dominating when there are multiple good pass options.
        double teamBonus = Math.min(goodReceivers * 1.2, 5.0);
        return new DecisionOption(DecisionType.PASS, bestReceiver, bestScore + teamBonus, "pass scored");
    }

    /**
     * Check if the passing lane from carrier to receiver is clear of opponents.
     * A lane is clear if no opponent is within 0.8 cells of the line between them.
     */
    private boolean isPassingLaneClear(Player carrier, Player receiver, List<Player> opponents) {
        Position a = carrier.getPosition();
        Position b = receiver.getPosition();
        for (Player opp : opponents) {
            double dist = pointToLineDistance(opp.getPosition(), a, b);
            if (dist < 0.8) return false;
        }
        return true;
    }

    private DecisionOption scoreThru(DecisionContext ctx) {
        Player carrier = ctx.player();
        if (ctx.isGoalkeeper()) return null;
        Player runner = findThruRunner(carrier, ctx.opponents());
        if (runner == null) return null;
        int nonGkDefendersAhead = 0;
        boolean home = "HOME".equals(carrier.getTeam());
        Position rPos = runner.getPosition();
        for (Player opp : ctx.opponents()) {
            if ("GK".equals(opp.getRole())) continue;
            boolean ahead = home
                    ? opp.getPosition().getRow() >= rPos.getRow()
                    : opp.getPosition().getRow() <= rPos.getRow();
            if (ahead) nonGkDefendersAhead++;
        }
        double spaceBehind = spaceBehindDefense(runner, ctx.opponents());
        double receiverRun = runner.getSkills().pace() / 2.0;
        double progression = forwardProgression(carrier, runner) * 1.5;
        double interceptionRisk = interceptionRisk(carrier, runner, ctx.opponents()) * 0.3;
        double pressure = ctx.pressure() * 0.15;
        double runnerCol = runner.getPosition().getColumn();
        boolean isWingRunner = runnerCol <= 2 || runnerCol >= 5;
        double wingBonus = isWingRunner ? 5.0 : 0.0;
        double paceBonus = runner.getSkills().pace() >= 14 ? 3.0 : 0.0;
        // Offside risk: progressive penalty as defenders get fewer ahead of runner.
        // 0 defenders ahead = certain offside, 1 = very risky (level = onside but fragile)
        double offsidePenalty;
        if (nonGkDefendersAhead == 0) {
            offsidePenalty = -spaceBehind * 2.5; // certain offside — very heavy penalty
        } else if (nonGkDefendersAhead == 1) {
            offsidePenalty = -10.0; // marginal — high risk of offside
        } else {
            offsidePenalty = 0.0;
        }
        if (runner.getConsecutiveOffsideCount() >= 3) {
            offsidePenalty -= 120.0;
        } else if (runner.getConsecutiveOffsideCount() >= 2) {
            offsidePenalty -= 60.0;
        } else if (runner.getConsecutiveOffsideCount() >= 1) {
            offsidePenalty -= 20.0;
        }
        // Reduce spaceBehind influence — it was making THRU too attractive
        double score = spaceBehind * 0.35 + receiverRun * 0.2 + progression * 0.3
                - interceptionRisk * 0.4 - pressure + offsidePenalty + wingBonus + paceBonus;
        return new DecisionOption(DecisionType.THRU, runner, score, "thru scored");
    }

    private DecisionOption scoreCarry(DecisionContext ctx) {
        Player carrier = ctx.player();
        double availableSpace = availableForwardSpace(carrier);
        double spaceAround = openSpaceAround(carrier, ctx.opponents());
        double dribbleAdvantage = (carrier.getSkills().technique() + carrier.getSkills().striker()) / 2.0 * 0.3;
        double progression = ctx.fieldPosition() / 7.0 * 20;
        double pressure = ctx.pressure() * 0.3;
        // Congestion penalty: when opponents are close, carry is dangerous.
        // spaceAround < 5 means an opponent within ~1 cell — heavy penalty.
        double congestionMultiplier = spaceAround < 5 ? 0.3 : (spaceAround < 15 ? 0.7 : 1.0);
        // Consecutive carry penalty: after 2+ carries, strongly discourage more carries.
        // This prevents players from holding the ball 3+ times in a row.
        int consecutiveCarries = ctx.player().getConsecutiveCarries();
        double consecutivePenalty;
        if (consecutiveCarries >= 3) {
            consecutivePenalty = 30.0;
        } else if (consecutiveCarries >= 2) {
            consecutivePenalty = 20.0;
        } else if (consecutiveCarries >= 1) {
            consecutivePenalty = 8.0;
        } else {
            consecutivePenalty = 0.0;
        }
        double score = (availableSpace * 0.8 + spaceAround * 0.4 + dribbleAdvantage + progression - pressure)
                * congestionMultiplier - consecutivePenalty;
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
        double goalProximity = Math.max(0, (1.0 - distanceToGoal / 4.0)) * 8;
        double shootingSpace = openSpaceAround(carrier, ctx.opponents()) * 0.2;
        double strikerQuality = carrier.getSkills().striker() * 0.3;
        double pressure = ctx.pressure() * 0.05;
        boolean isAttacker = carrier.isAttacker() || "WNG".equals(carrier.roleLine());
        boolean home = "HOME".equals(carrier.getTeam());
        double row = carrier.getPosition().getRow();
        boolean inFinalThreeRows = home ? (row >= 4) : (row <= 4);
        boolean inFinalTwoRows = home ? (row >= 5) : (row <= 3);
        double attackerBonus = (isAttacker && inFinalThreeRows) ? 1.5 : 0.0;
        double boxBonus = inFinalTwoRows ? 1.0 : 0.0;
        double pressureToShoot = ctx.pressure() > 35.0 ? (ctx.pressure() - 35.0) * 0.08 : 0.0;
        double freshReceivePenalty;
        if (carrier.getConsecutiveCarries() == 0) {
            freshReceivePenalty = 8.0; // just received — settle/pass first, but allow shot if open
        } else if (carrier.getConsecutiveCarries() >= 2) {
            freshReceivePenalty = 10.0; // carried 2+ times — should pass, not shoot
        } else {
            freshReceivePenalty = 4.0; // carried once — still prefer pass/carry over shot
        }
        double score = goalProximity + shootingSpace + strikerQuality - pressure
                + attackerBonus + boxBonus + pressureToShoot - freshReceivePenalty;
        if (distanceToGoal > 4.0) {
            score -= 15.0;
        }
        if (carrier.getSkills().striker() < 8 && distanceToGoal > 3.0) {
            score -= 20.0;
        }
        if (carrier.getSkills().striker() < 5 && distanceToGoal > 2.0) {
            score -= 25.0;
        }
        double angleToGoal = Math.abs(angleToGoal(carrier, goal));
        if (angleToGoal > 60.0) {
            score -= 10.0;
        }
        double defendersNearGoal = countDefendersNearGoal(carrier, ctx.opponents());
        double defenderPenalty = defendersNearGoal * 5.0;
        score -= defenderPenalty;
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
        double boxPresence = boxAttackers * 5.0;
        double crossingQuality = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 2.0 * 0.35;
        double progression = forwardProgression(carrier, goalPosition(ctx)) * 0.4;
        double safety = 8.0 - Math.min(8.0, ctx.pressure() * 0.3);
        double score = boxPresence * 0.3 + crossingQuality + progression * 0.2 + safety - ctx.pressure() * 0.4;
        return new DecisionOption(DecisionType.CENTER, score, "center scored");
    }

    // --- Helpers ---

    private int receiverCountForPM(double pm) {
        if (pm >= 16) return 8;
        if (pm >= 11) return 6;
        if (pm >= 6) return 5;
        return 3;
    }

    // --- Threat/Perception delegated to ThreatAssessmentService & PlayerPerceptionService ---

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

    /**
     * Count non-GK opponents positioned goal-side of or level with the receiver (FIFA: level = onside).
     */
    private int countNonGkDefendersAhead(Player receiver, List<Player> opponents) {
        boolean home = "HOME".equals(receiver.getTeam());
        Position rPos = receiver.getPosition();
        int count = 0;
        for (Player p : opponents) {
            if ("GK".equals(p.getRole())) continue;
            boolean goalSide = home
                    ? p.getPosition().getRow() >= rPos.getRow()
                    : p.getPosition().getRow() <= rPos.getRow();
            if (goalSide) count++;
        }
        return count;
    }

    private double forwardProgression(Player carrier, Player target) {
        boolean home = "HOME".equals(carrier.getTeam());
        double progress = home
                ? (target.getPosition().getRow() - carrier.getPosition().getRow())
                : (carrier.getPosition().getRow() - target.getPosition().getRow());
        return Math.max(0, progress) * 6;
    }

    private double lateralBackwardPenalty(Player carrier, Player receiver) {
        boolean home = "HOME".equals(carrier.getTeam());
        double rowDelta = home
                ? (receiver.getPosition().getRow() - carrier.getPosition().getRow())
                : (carrier.getPosition().getRow() - receiver.getPosition().getRow());
        if (rowDelta > 0.5) return 0; // forward pass — no penalty
        if (rowDelta > -0.5) return 1.5; // lateral — mild penalty (common in build-up play)
        return 4.0; // backward — modest penalty, still allows safe backward passes
    }

    private double forwardProgression(Player carrier, Position goal) {
        boolean home = "HOME".equals(carrier.getTeam());
        double progress = home
                ? (goal.getRow() - carrier.getPosition().getRow())
                : (carrier.getPosition().getRow() - goal.getRow());
        return Math.max(0, progress) * 4;
    }

    private Player findThruRunner(Player carrier, List<Player> opponents) {
        boolean home = "HOME".equals(carrier.getTeam());
        double carrierRow = carrier.getPosition().getRow();
        Player bestRunner = null;
        double bestScore = -1;
        for (Player p : state.getPlayers()) {
            if (!p.getTeam().equals(carrier.getTeam())) continue;
            if (p == carrier || "GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured() || state.isBlockedAfterDuel(p)) continue;
            boolean ahead = home
                    ? p.getPosition().getRow() > carrierRow
                    : p.getPosition().getRow() < carrierRow;
            if (!ahead) continue;
            if (home && p.getPosition().getRow() <= 1) continue;
            if (!home && p.getPosition().getRow() >= 7) continue;
            // Score runner by space behind defence + how far ahead they are
            double space = spaceBehindDefense(p, opponents);
            double prog = forwardProgression(carrier, p);
            // Only bonus deep positioning if runner has defenders ahead (onside)
            double deepBonus = isDeepAttacker(p, home) ? 10 : 0;
            double offsidePenalty = (space <= 1.0) ? -50.0 : 0.0; // space<=1 means no defenders ahead → offside
            double score = space * 0.5 + prog * 0.3 + deepBonus + offsidePenalty;
            if (score > bestScore) {
                bestScore = score;
                bestRunner = p;
            }
        }
        return bestRunner;
    }

    private boolean isDeepAttacker(Player p, boolean home) {
        double row = p.getPosition().getRow();
        return home ? row >= 5 : row <= 3;
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
        if (minDist == Double.MAX_VALUE) return 0; // offside — no defenders ahead = bad
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
        return Math.max(0, toGoal) * 2;
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

    /**
     * Count defenders between the carrier and the goal.
     * With 4-4-2, a striker in the box should have 2-4 defenders between them and the goal.
     * Counts non-GK opponents within 2.0 cells of the carrier AND closer to the goal.
     */
    private int countDefendersNearGoal(Player shooter, List<Player> opponents) {
        Position goal = ActionEngine.goalPositionFor(shooter.getTeam());
        boolean home = "HOME".equals(shooter.getTeam());
        int count = 0;
        double carrierDistToGoal = SimUtils.distance(shooter.getPosition(), goal);
        for (Player opp : opponents) {
            if ("GK".equals(opp.getRole())) continue;
            double distToShooter = SimUtils.distance(opp.getPosition(), shooter.getPosition());
            double distToGoal = SimUtils.distance(opp.getPosition(), goal);
            // Defender is between shooter and goal if they're close to shooter AND closer to goal
            if (distToShooter < 2.0 && distToGoal < carrierDistToGoal) {
                count++;
            }
        }
        return count;
    }

    // Helper method to calculate angle to goal for shot quality assessment
    private double angleToGoal(Player shooter, Position goalPos) {
        double dx = goalPos.getColumn() - shooter.getPosition().getColumn();
        double dy = goalPos.getRow() - shooter.getPosition().getRow();
        if (Math.abs(dx) < 0.1) {
            return 0.0;
        }
        double angle = Math.abs(Math.atan2(dx, dy) * 180.0 / Math.PI);
        return angle;
    }

    /**
     * Calculate pressure on the ball carrier from nearby opponents.
     * Unlike ThreatAssessmentService.calculatePersonalPressure() which returns 0 for the
     * carrier's team, this counts actual defenders within pressing range of the carrier.
     * With 4-4-2, a striker in the box should have 2-4 defenders within 1.5 cells.
     */
    private double calculateCarrierPressure(Player carrier) {
        double pressure = 0;
        double pressingRange = 1.5;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            double dist = SimUtils.distance(carrier.getPosition(), p.getPosition());
            if (dist < pressingRange) {
                pressure += (pressingRange - dist) / pressingRange;
            }
        }
        return SimUtils.clamp(pressure, 0, 1);
    }
}
