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
    // Own-half pass tracking: max 12 consecutive passes in own half
    private int consecutiveOwnHalfPasses = 0;
    private static final int MAX_OWN_HALF_PASSES = 12;
    private String lastCarrierTeam = null;
    private final java.util.Random random;

    public PlaymakingDecisionEngine(MatchState state, PlayerSelectionEngine selection,
                                    ThreatAssessmentService threatService,
                                    PlayerPerceptionService perceptionService,
                                    java.util.Random random) {
        this.state = state;
        this.selection = selection;
        this.threatService = threatService;
        this.perceptionService = perceptionService;
        this.random = random;
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
     * Also tracks own-half passes for the consecutive limit.
     */
    public void recordPassExchange(String passerId, String receiverId) {
        String key = passerId + "->" + receiverId;
        String reverseKey = receiverId + "->" + passerId;
        int count = Math.max(passExchangeCount.getOrDefault(key, 0),
                passExchangeCount.getOrDefault(reverseKey, 0));
        passExchangeCount.put(key, count + 1);

        // Track own-half passes
        Player passer = null;
        for (Player p : state.getPlayers()) {
            if (p.getId().equals(passerId)) { passer = p; break; }
        }
        if (passer != null) {
            boolean home = "HOME".equals(passer.getTeam());
            double row = passer.getPosition().getRow();
            boolean inOwnHalf = home ? (row < 4) : (row > 4);
            if (inOwnHalf) {
                consecutiveOwnHalfPasses++;
            } else {
                // Forward pass resets the own-half counter
                consecutiveOwnHalfPasses = 0;
            }
        }
    }

    /**
     * Reset pass exchange tracking (call when possession changes or new action starts).
     */
    public void resetPassExchanges() {
        passExchangeCount.clear();
        consecutiveOwnHalfPasses = 0;
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

        // HARD RULE: max 3 consecutive carries — remove CARRY from options entirely
        // so the selector cannot choose it. Prevents players dribbling endlessly.
        if (carrier.getConsecutiveCarries() >= 3) {
            visible = visible.stream()
                    .filter(o -> o.getType() != DecisionType.CARRY)
                    .collect(Collectors.toList());
            if (visible.isEmpty()) {
                // Safety: if all options filtered, force PASS
                visible = options.stream()
                        .filter(o -> o.getType() == DecisionType.PASS)
                        .peek(o -> o.setVisible(true))
                        .collect(Collectors.toList());
            }
        }

        // HARD RULE: max 3 ping-pong passes between same pair — remove PASS to
        // the exchange-limited receiver. This breaks the Away8↔Away9 infinite loop.
        List<DecisionOption> filteredByExchange = new ArrayList<>();
        for (DecisionOption opt : visible) {
            if (opt.getType() == DecisionType.PASS && opt.getTarget() != null
                    && isPassExchangeLimitReached(carrier.getId(), opt.getTarget().getId())) {
                continue; // skip this pass — ping-pong limit reached
            }
            filteredByExchange.add(opt);
        }
        if (!filteredByExchange.isEmpty()) {
            visible = filteredByExchange;
        }

        // HARD RULE: max 10 consecutive passes in own half — force forward action.
        // Prevents endless sideways/backward passing without progression.
        if (consecutiveOwnHalfPasses >= MAX_OWN_HALF_PASSES) {
            List<DecisionOption> forwardOptions = visible.stream()
                    .filter(o -> o.getType() == DecisionType.SHOT
                            || o.getType() == DecisionType.THRU
                            || o.getType() == DecisionType.CROSS
                            || o.getType() == DecisionType.CENTER
                            || (o.getType() == DecisionType.CARRY))
                    .collect(Collectors.toList());
            if (!forwardOptions.isEmpty()) {
                visible = forwardOptions;
            }
            // If no forward options exist, at least remove PASS to own-half targets
            // so the player is forced to try something different
            else {
                List<DecisionOption> nonBackward = new ArrayList<>();
                for (DecisionOption opt : visible) {
                    if (opt.getType() == DecisionType.PASS && opt.getTarget() != null) {
                        boolean home = ctx.isHome();
                        double targetRow = opt.getTarget().getPosition().getRow();
                        boolean targetInOwnHalf = home ? (targetRow < 4) : (targetRow > 4);
                        if (targetInOwnHalf) continue; // skip backward pass
                    }
                    nonBackward.add(opt);
                }
                if (!nonBackward.isEmpty()) {
                    visible = nonBackward;
                }
            }
        }

        // SIDELINE OVERRIDE: wide attacker with space along the line MUST carry/cross.
        // This is a football-intelligent decision — exploiting the flank.
        DecisionOption sidelineOverride = checkSidelineOverride(ctx, visible);
        if (sidelineOverride != null) {
            // Don't count as consecutive carry — it's a good tactical decision
            if (sidelineOverride.getType() == DecisionType.CARRY) {
                ctx.player().resetConsecutiveCarries();
            }
            lastSelectionReason = "sideline override: " + sidelineOverride.getReason();
            return sidelineOverride;
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

        // Override: if carrier is close to goal with an open net or only GK ahead,
        // force SHOT regardless of what was chosen.
        if (result.getType() != DecisionType.SHOT) {
            Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
            double distToGoal = SimUtils.distance(carrier.getPosition(), goal);
            int defendersBetween = countDefendersNearGoal(carrier, ctx.opponents());
            Player opponentGK = findOpponentGoalkeeper(carrier.getTeam());
            double gkDistToGoal = opponentGK != null
                    ? SimUtils.distance(opponentGK.getPosition(), goal) : 99;

            // Empty goal: GK is out of position (more than 2.0 cells from goal)
            boolean emptyGoal = gkDistToGoal > 2.0 && distToGoal <= 4.0;
            // Only GK ahead and close: 0 defenders between + within shooting range
            boolean onlyGKAhead = defendersBetween == 0 && distToGoal <= 3.0;

            if (emptyGoal || onlyGKAhead) {
                for (DecisionOption opt : visible) {
                    if (opt.getType() == DecisionType.SHOT) {
                        result = opt;
                        lastSelectionReason = "empty-goal override: GK dist="
                                + String.format("%.1f", gkDistToGoal)
                                + " defenders=" + defendersBetween;
                        break;
                    }
                }
            }
        }

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
                state.getMatchTicks(), new ArrayList<>());
    }

    private List<DecisionOption> generateOptions(DecisionContext ctx) {
        List<DecisionOption> options = new ArrayList<>();
        double pm = ctx.playmaking();

        if (ctx.isKickoff()) {
            // Kickoff is a hard football rule in the demo: the opening action is
            // a backward PASS to a teammate. Never expose CARRY (or any other
            // decision) as a fallback for kickoff.
            DecisionOption pass = generateKickoffPass(ctx);
            if (pass == null) {
                throw new IllegalStateException("Kickoff requires a teammate PASS target");
            }
            options.add(pass);
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
            pmCarryPenalty = 10.0;
            pmShotPenalty = 8.0;
        } else if (pm < 11) {
            // Medium PM: reduced CARRY preference
            pmClearBonus = 4.0;
            pmCarryPenalty = 5.0;
            pmShotPenalty = 2.0;
        }
        // High PM (11+): small carry penalty — prefers passing but can carry when open
        else {
            pmCarryPenalty = 2.0;
        }

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
        // CLEAR is not a legal attacking action. Do not leave a -999 option in
        // the selector, because it can still win when every other score is low.
        boolean inDefensiveThird = ctx.isHome()
                ? ctx.player().getPosition().getRow() <= 2.0
                : ctx.player().getPosition().getRow() >= 6.0;
        if (inDefensiveThird) options.add(clear);

        // THRU: 5% frequency gate to reduce offside (was ~30+ THRU/match, target 2-3)
        if (random.nextDouble() < 0.05) {
            DecisionOption thru = scoreThru(ctx);
            if (thru != null) options.add(thru);
        }

        // SHOT: generate option when canShoot is true, with frequency gate
        // 10% gate targets ~15-20 shots/match at skill=14
        boolean attackerInOpenScoringPosition = ctx.canShoot()
                && (ctx.player().isAttacker() || "WNG".equals(ctx.player().roleLine()))
                && countDefendersNearGoal(ctx.player(), ctx.opponents()) == 0
                && SimUtils.distance(ctx.player().getPosition(), goalPosition(ctx)) <= 3.5;
        if (ctx.canShoot() && (attackerInOpenScoringPosition || random.nextDouble() < 0.10)) {
            DecisionOption shot = scoreShot(ctx);
            if (attackerInOpenScoringPosition) shot.setScore(shot.getScore() + 20.0);
            shot.setScore(shot.getScore() - pmShotPenalty);
            options.add(shot);
        }

        // CROSS: from wing in final third only — generates 10-15 crosses/match
        // 35% frequency gate to get more crosses for corner production
        if (ctx.inFinalThird() && ctx.onWing() && random.nextDouble() < 0.35) {
            double carrierRow = ctx.player().getPosition().getRow();
            boolean home = ctx.isHome();
            boolean inTheBox = home ? (carrierRow >= 6) : (carrierRow <= 2);
            if (!inTheBox) {
                DecisionOption cross = scoreCross(ctx);
                if (cross != null) options.add(cross);
            }
        }
        // CENTER: from central positions in final third — generates 10-30 centers/match
        // 12% frequency gate to prevent excessive centers (was 227+/match without gate)
        if (ctx.inFinalThird() && !ctx.onWing() && random.nextDouble() < 0.12) {
            double carrierRow = ctx.player().getPosition().getRow();
            boolean home = ctx.isHome();
            boolean inTheBox = home ? (carrierRow >= 6) : (carrierRow <= 2);
            // CENTER is a final-third delivery, not a midfield pass.
            boolean centralDeliveryZone = home ? carrierRow >= 5.5 : carrierRow <= 2.5;
            if (!inTheBox && centralDeliveryZone) {
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
        Player bestBackwardReceiver = null; // fallback: any teammate in own half (not GK/defensive row)
        double bestBackwardScore = -1;

        for (Player candidate : ctx.teammates()) {
            if (isOwnGoalkeeperOrDefensiveRow(candidate, carrier.getTeam())) continue;
            boolean validRow = home ? (candidate.getPosition().getRow() < 4)
                    : (candidate.getPosition().getRow() > 4);
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
            // Also track the best teammate in own half (backward direction) as a fallback
            // This ensures kickoff never defaults to CARRY/clearance when formation is misaligned
            if (validRow && score > bestBackwardScore) {
                bestBackwardScore = score;
                bestBackwardReceiver = candidate;
            }
        }
        // Guarantee non-null: use best backward receiver as absolute fallback
        if (bestReceiver == null && bestBackwardReceiver != null) {
            return new DecisionOption(DecisionType.PASS, bestBackwardReceiver, bestBackwardScore,
                    "kickoff pass to backward receiver (fallback)");
        }
        if (bestReceiver == null) {
            // Emergency fallback: any non-GK teammate
            for (Player candidate : ctx.teammates()) {
                if (isOwnGoalkeeperOrDefensiveRow(candidate, carrier.getTeam())) continue;
                double candOpenness = receiverOpenness(candidate, ctx.opponents());
                double candCol = candidate.getPosition().getColumn();
                double candSidelineDist = Math.min(candCol - 1, 6 - candCol);
                double candSidelinePenalty = candSidelineDist < 1.5 ? Math.pow(1.5 - candSidelineDist, 2) * -15.0 : 0;
                double candCenterBonus = (4.0 - Math.abs(candCol - 3.5)) * 3.0;
                double candScore = candOpenness * 1.2 + 30 + candSidelinePenalty + candCenterBonus;
                if (candScore > bestScore) {
                    bestScore = candScore;
                    bestReceiver = candidate;
                }
            }
            if (bestReceiver == null) {
                // Absolute rule fallback: kickoff must still be a PASS.
                // If the formation is unusual, use the first available teammate.
                bestReceiver = ctx.teammates().stream()
                        .filter(p -> p != carrier)
                        .findFirst()
                        .orElse(null);
                if (bestReceiver == null) return null;
                bestScore = 0;
            }
        }
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
            if (isClearlyOffsideAtPass(carrier, receiver)) continue;
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
            double sidelinePenalty = sidelineDist < 0.5 ? (0.5 - sidelineDist) * -4.0 : 0;  // reduced: allow more wing passes
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

            // PM-based weighting: high PM → more progressive (higher progression weight, lower safety)
            // low PM → safer passes (lower progression weight, higher safety)
            double pm = ctx.playmaking();
            double progressionWeight = 0.8 + (pm / 20.0) * 1.7; // 0.8 at PM=1, 2.5 at PM=20
            double safetyWeight = 0.8 - (pm / 20.0) * 0.4;    // 0.8 at PM=1, 0.4 at PM=20
            double openSafBuf = (progression > 0)
                    ? (openness * 0.4 + safety * safetyWeight)
                    : (openness * 0.3 + safety * 0.6);

            double score = quality * 0.5 + progression * progressionWeight + openSafBuf - pressure
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
                    score -= 100.0; // receiver is in deep offside position — MUST avoid
                } else if (defendersAhead == 1) {
                    score -= 30.0; // marginal — risky
                }
            }
            if (score > 10) goodReceivers++;
            if (score > bestScore) {
                bestScore = score;
                bestReceiver = receiver;
            }
        }
        if (bestReceiver == null) {
            // Air pass fallback: when all ground lanes are blocked, try a long ball
            // over the defenders to the furthest forward teammate. Higher deviation
            // but at least it's a forward pass rather than backward safety pass.
            Player furthestForward = null;
            double bestForwardProgress = -1;
            for (Player p : selection.nearestTeamTo(carrier, 8)) {
                if (p == carrier) continue;
                if (isOwnGoalkeeperOrDefensiveRow(p, carrier.getTeam())) continue;
                if (isClearlyOffsideAtPass(carrier, p)) continue;
                double progress = forwardProgression(carrier, p);
                if (progress > bestForwardProgress) {
                    bestForwardProgress = progress;
                    furthestForward = p;
                }
            }
            if (furthestForward != null && bestForwardProgress > 0) {
                // Air pass: last resort when all ground lanes blocked — low score
                double airScore = 0.5 + bestForwardProgress * 0.15
                        - SimUtils.distance(carrier.getPosition(), furthestForward.getPosition()) * 2.5
                        + (carrier.getSkills().passing() - 10) * 0.15;
                return new DecisionOption(DecisionType.PASS, furthestForward, airScore,
                        "air pass forward over blocked lanes");
            }
            // Absolute safety: nearest non-GK teammate
            // In final 2 rows: do NOT pass backward — force null so carrier must SHOT/CARRY
            List<Player> allTeammates = selection.nearestTeamTo(carrier, 5);
            for (Player fallback : allTeammates) {
                if (fallback == carrier) continue;
                if (isOwnGoalkeeperOrDefensiveRow(fallback, carrier.getTeam())) continue;
                // In final rows, only allow lateral or forward passes
                if (inFinalRows) {
                    double fallbackRow = fallback.getPosition().getRow();
                    boolean validRow = home ? (fallbackRow >= carrierRow - 0.5) : (fallbackRow <= carrierRow + 0.5);
                    if (!validRow) continue;
                }
                return new DecisionOption(DecisionType.PASS, fallback, -2.0, "safety pass fallback");
            }
            // In final rows with no valid pass option: return null so decide() picks SHOT/CARRY
            if (inFinalRows) return null;
            return null;
        }
        // Team option bonus: more open teammates = passing is more viable as a strategy.
        // This prevents CARRY from dominating when there are multiple good pass options.
        double teamBonus = Math.min(goodReceivers * 1.2, 5.0);
        return new DecisionOption(DecisionType.PASS, bestReceiver, bestScore + teamBonus, "pass scored");
    }

    private boolean isClearlyOffsideAtPass(Player passer, Player receiver) {
        boolean home = "HOME".equals(passer.getTeam());
        double passerRow = passer.getPosition().getRow();
        double receiverRow = receiver.getPosition().getRow();
        boolean forward = home ? receiverRow > passerRow : receiverRow < passerRow;
        boolean opponentHalf = home ? receiverRow >= 4.0 : receiverRow <= 4.0;
        if (!forward || !opponentHalf) return false;

        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> opponentRows = new ArrayList<>();
        for (Player opponent : state.getPlayers()) {
            if (defendingTeam.equals(opponent.getTeam())
                    && !opponent.isSentOff() && !opponent.isInjured()) {
                opponentRows.add(opponent.getPosition().getRow());
            }
        }
        if (opponentRows.size() < 2) return true;
        opponentRows.sort(home ? java.util.Comparator.reverseOrder()
                : java.util.Comparator.naturalOrder());
        double secondLastOpponent = opponentRows.get(1);
        double margin = home ? receiverRow - secondLastOpponent
                : secondLastOpponent - receiverRow;
        // Only suppress a pass when the receiver is clearly, deeply offside.
        // Close calls must still be attempted so the referee/VAR path can decide.
        return margin > 1.0;
    }

    /**
     * Check if the passing lane from carrier to receiver is clear of opponents.
     * A lane is clear if no opponent is within 0.5 cells of the line between them.
     * 0.5 cells ≈ 7m — realistic passing lane width on a compressed pitch.
     */
    private boolean isPassingLaneClear(Player carrier, Player receiver, List<Player> opponents) {
        Position a = carrier.getPosition();
        Position b = receiver.getPosition();
        for (Player opp : opponents) {
            double dist = pointToLineDistance(opp.getPosition(), a, b);
            if (dist < 0.5) return false;
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
            offsidePenalty = -50.0; // certain offside — heavy penalty (was: -spaceBehind*4 which was 0 when space==0)
        } else if (nonGkDefendersAhead == 1) {
            offsidePenalty = -25.0; // marginal — high risk of offside
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
        // Reduce spaceBehind and progression weights — THRU was too attractive vs PASS
        double score = spaceBehind * 0.20 + receiverRun * 0.15 + progression * 0.20
                - interceptionRisk * 0.4 - pressure + offsidePenalty + wingBonus + paceBonus;
        return new DecisionOption(DecisionType.THRU, runner, score, "thru scored");
    }

    private DecisionOption scoreCarry(DecisionContext ctx) {
        Player carrier = ctx.player();
        
        // HARD BLOCK: carry only in opponent half
        if (!ctx.inOpponentHalf()) {
            return new DecisionOption(DecisionType.CARRY, -999, "carry blocked in own half");
        }
        
        double availableSpace = availableForwardSpace(carrier);
        double spaceAround = openSpaceAround(carrier, ctx.opponents());
        
        // HARD BLOCK: carry only if at least 1 cell free around or ahead
        if (availableSpace < 1.0 && spaceAround < 5.0) {
            return new DecisionOption(DecisionType.CARRY, -999, "carry blocked - no space");
        }
        
        double dribbleAdvantage = (carrier.getSkills().technique() + carrier.getSkills().striker()) / 2.0 * 0.25;
        double progression = ctx.fieldPosition() / 7.0 * 15;
        double pressure = ctx.pressure() * 0.4;
        // Congestion: open space = higher carry viability
        double congestionMultiplier = spaceAround < 5 ? 0.15 : (spaceAround < 15 ? 0.55 : 0.85);
        // Global carry weight: target 50-100/match
        double globalCarryWeight = 0.85;
        // Consecutive carry penalty: strongly discourage after 1+ carries
        int consecutiveCarries = ctx.player().getConsecutiveCarries();
        double consecutivePenalty;
        if (consecutiveCarries >= 3) {
            consecutivePenalty = 80.0;
        } else if (consecutiveCarries >= 2) {
            consecutivePenalty = 40.0;
        } else if (consecutiveCarries >= 1) {
            consecutivePenalty = 15.0;
        } else {
            consecutivePenalty = 0.0;
        }
        double score = (availableSpace * 0.55 + spaceAround * 0.35 + dribbleAdvantage + progression * 0.40 - pressure)
                * congestionMultiplier * globalCarryWeight - consecutivePenalty;
        return new DecisionOption(DecisionType.CARRY, score, "carry scored");
    }

    private DecisionOption scoreClear(DecisionContext ctx, double passThreshold) {
        // HARD BLOCK: clearance ONLY in own 3 closest rows to goal (defensive third).
        // HOME defends rows 0-2, AWAY defends rows 5-7. Middle/attacking: no clearance.
        boolean home = ctx.isHome();
        double row = ctx.player().getPosition().getRow();
        boolean inDefensiveThird = home ? (row <= 2.0) : (row >= 6.0);
        if (!inDefensiveThird) {
            return new DecisionOption(DecisionType.CLEAR, -999, "clear blocked outside defensive third");
        }
        double danger = ctx.danger();
        double pressure = ctx.pressure();
        double lackOfSafeOptions = Math.max(0, 40 - passThreshold) * 0.3;
        double score = danger * 0.6 + pressure * 0.4 + lackOfSafeOptions;
        return new DecisionOption(DecisionType.CLEAR, score, "clear scored");
    }

private DecisionOption scoreShot(DecisionContext ctx) {
        Player carrier = ctx.player();
        Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
        double distanceToGoal = SimUtils.distance(carrier.getPosition(), goal);
        double goalProximity = Math.max(0, (1.0 - distanceToGoal / 5.0)) * 12;
        double shootingSpace = openSpaceAround(carrier, ctx.opponents()) * 0.50;
        double strikerQuality = carrier.getSkills().striker() * 0.50;
        double pressure = ctx.pressure() * 0.05;
        boolean isAttacker = carrier.isAttacker() || "WNG".equals(carrier.roleLine());
        boolean home = "HOME".equals(carrier.getTeam());
        double row = carrier.getPosition().getRow();
        boolean inFinalThreeRows = home ? (row >= 4) : (row <= 4);
        boolean inFinalTwoRows = home ? (row >= 5) : (row <= 3);
        boolean inFinalOneRow = home ? (row >= 6) : (row <= 2);
        double attackerBonus = (isAttacker && inFinalThreeRows) ? 2.0 : 0.0;
        double boxBonus = inFinalTwoRows ? 4.0 : 0.0;
        double deepBoxBonus = inFinalOneRow ? 3.0 : 0.0;
        double pressureToShoot = ctx.pressure() > 30.0 ? (ctx.pressure() - 30.0) * 0.15 : 0.0;
        double freshReceivePenalty;
        if (inFinalOneRow) {
            freshReceivePenalty = 0.5;
        } else if (inFinalTwoRows) {
            freshReceivePenalty = 1.5;
        } else if (carrier.getConsecutiveCarries() == 0) {
            freshReceivePenalty = 4.0;
        } else if (carrier.getConsecutiveCarries() >= 2) {
            freshReceivePenalty = 5.0;
        } else {
            freshReceivePenalty = 3.0;
        }

        // Empty-goal incentive: no defenders between carrier and goal + GK far away
        // boosts shot score so attacker shoots instead of dribbling.
        double defendersNearGoal = countDefendersNearGoal(carrier, ctx.opponents());
        boolean emptyGoal = defendersNearGoal == 0;
        Player opponentGK = findOpponentGoalkeeper(carrier.getTeam());
        double gkDistToGoal = opponentGK != null
                ? SimUtils.distance(opponentGK.getPosition(), goal) : 99;
        double emptyGoalBonus = 0;
        if (emptyGoal && distanceToGoal <= 5.0) {
            // Base bonus scales with proximity
            emptyGoalBonus = 5.0 + (5.0 - distanceToGoal) * 3.0; // max 20.0
            // GK completely out of goal = massive bonus (must shoot)
            if (gkDistToGoal > 3.0) {
                emptyGoalBonus += 30.0; // near-guaranteed shot
            } else if (gkDistToGoal > 2.0) {
                emptyGoalBonus += 10.0;
            }
        }

        double score = goalProximity + shootingSpace + strikerQuality - pressure
                + attackerBonus + boxBonus + deepBoxBonus + pressureToShoot
                - freshReceivePenalty + emptyGoalBonus;

        // Empty goal overrides fresh-receive penalty — MUST shoot
        if (emptyGoal && distanceToGoal <= 4.0) {
            score += freshReceivePenalty; // cancel the penalty
        }

        // Shot cooldown: penalize if player shot recently
        // BUT: empty goal overrides cooldown — MUST shoot
        int ticksSinceShot = ctx.matchTick() - carrier.getLastShotTick();
        if (ticksSinceShot < 40 && !emptyGoal) {
            score -= 8.0;
        }

        // Distance penalties — but NOT for empty goal (must shoot regardless)
        if (!emptyGoal) {
            if (distanceToGoal > 3.5) {
                score -= 6.0;
            } else if (distanceToGoal > 2.5) {
                score -= 3.0;
            } else if (distanceToGoal > 1.5) {
                score -= 1.0;
            }
        }
        if (!isAttacker && distanceToGoal > 2.0) {
            score -= 10.0;
        }
        if (carrier.getSkills().striker() < 8 && distanceToGoal > 3.0) {
            score -= 10.0;
        }
        if (carrier.getSkills().striker() < 5 && distanceToGoal > 2.0) {
            score -= 15.0;
        }
        double angleToGoal = Math.abs(angleToGoal(carrier, goal));
        if (angleToGoal > 60.0) {
            score -= 10.0;
        }
        double defenderPenalty = defendersNearGoal * 5.0;
        score -= defenderPenalty;

        return new DecisionOption(DecisionType.SHOT, score, "shot scored");
    }

    private DecisionOption scoreCross(DecisionContext ctx) {
        Player carrier = ctx.player();
        int boxAttackers = countBoxAttackers(ctx);
        if (boxAttackers == 0) return null;
        double boxPresence = boxAttackers * 5.0;
        double crossingQuality = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 2.0 * 0.4;
        double progression = forwardProgression(carrier, goalPosition(ctx)) * 0.5;
        double safety = 10.0 - Math.min(10.0, ctx.pressure() * 0.3);
        double score = boxPresence * 0.3 + crossingQuality + progression * 0.2 + safety - ctx.pressure() * 0.4;
        return new DecisionOption(DecisionType.CROSS, carrier, score, "cross scored");
    }

    private DecisionOption scoreCenter(DecisionContext ctx) {
        Player carrier = ctx.player();
        int boxAttackers = countBoxAttackers(ctx);
        if (boxAttackers == 0) return null;
        double boxPresence = boxAttackers * 4.0;
        double crossingQuality = (carrier.getSkills().technique() + carrier.getSkills().passing()) / 2.0 * 0.3;
        double progression = forwardProgression(carrier, goalPosition(ctx)) * 0.3;
        double safety = 8.0 - Math.min(8.0, ctx.pressure() * 0.3);
        double score = boxPresence * 0.2 + crossingQuality + progression * 0.15 + safety - ctx.pressure() * 0.45;
        return new DecisionOption(DecisionType.CENTER, carrier, score, "center scored");
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
            if ("GK".equals(opp.getRole())) continue;  // GK doesn't count — only outfield defenders
            boolean ahead = home
                    ? opp.getPosition().getRow() > runnerPos.getRow()
                    : opp.getPosition().getRow() < runnerPos.getRow();
            if (!ahead) continue;
            double dist = SimUtils.distance(runnerPos, opp.getPosition());
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 0; // offside — no outfield defenders ahead = bad
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
     * Counts actual defenders within pressing range of the carrier.
     * Unlike ThreatAssessmentService which returns 0 for carrier's team,
     * this counts opponents. Each defender within 2.0 cells contributes
     * 1.0 (closer = more), so multiple defenders compound pressure.
     * Returns 0-5 (max 5 simultaneous defenders within pressing range).
     */
    private double calculateCarrierPressure(Player carrier) {
        double pressure = 0;
        double pressingRange = 2.0;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            double dist = SimUtils.distance(carrier.getPosition(), p.getPosition());
            if (dist < pressingRange) {
                pressure += (pressingRange - dist) / pressingRange;
            }
        }
        return SimUtils.clamp(pressure, 0, 1.0);
    }

    /**
     * Count opponents within a given range of the carrier (excludes GK).
     */
    private int countDefendersWithinRange(Player carrier, List<Player> opponents, double range) {
        int count = 0;
        for (Player p : opponents) {
            if (SimUtils.distance(carrier.getPosition(), p.getPosition()) < range) count++;
        }
        return count;
    }

    /**
     * Find the opponent goalkeeper for the given team.
     */
    private Player findOpponentGoalkeeper(String carrierTeam) {
        String opponentTeam = "HOME".equals(carrierTeam) ? "AWAY" : "HOME";
        for (Player p : state.getPlayers()) {
            if (opponentTeam.equals(p.getTeam()) && "GK".equals(p.getRole())) {
                return p;
            }
        }
        return null;
    }

    /**
     * Sideline override: wide attacker (column <= 1.5 or >= 5.5) in opponent's half
     * with open space along the line ahead must carry/cross — exploiting the flank.
     * Near corner line (within 1 cell of end line): CENTER or dribble toward goal.
     * This is a forced tactical decision, not scored.
     */
    private DecisionOption checkSidelineOverride(DecisionContext ctx, List<DecisionOption> options) {
        Player carrier = ctx.player();
        double col = carrier.getPosition().getColumn();
        double row = carrier.getPosition().getRow();
        boolean home = "HOME".equals(carrier.getTeam());
        boolean attacking = home ? row >= 4.0 : row <= 4.0;
        if (!attacking) return null;

        // Is the player wide? (on the extreme wings, col 1 or 6)
        boolean wide = col <= 1.5 || col >= 5.5;
        if (!wide) return null;

        // --- User Task 2: open-flank condition. The winger is on the attacking
        // half in col 1/6 with (a) no opponent AHEAD within 1.0 cell toward the
        // goal line, and (b) no opponent BESIDE within 0.5 cell toward the inner
        // field. In that case the player must exploit the flank: dribble STRAIGHT
        // up the line (same column) repeatedly until the last row, then either cut
        // inside (clear beside) or centre into the box.
        boolean clearAhead = !anyOpponentWithin(carrier, home, col, row + (home ? 1.0 : -1.0), 1.0);
        boolean clearBeside = !anyOpponentWithin(carrier, home, col + (col > 3.5 ? -1.0 : 1.0), row, 0.5);

        // Only enforce the straight-line flank run while there is genuinely clear
        // space ahead. Otherwise fall through to the general wide logic below.
        if (clearAhead) {
            // Reached the LAST row? (HOME attacker at row 7, AWAY attacker at row 1)
            boolean atLastRow = home ? row >= 6.5 : row <= 1.5;
            if (atLastRow) {
                // At the byline: cut inside along the byline (carry toward the box)
                // if clear beside, otherwise whip a centre into the box.
                if (clearBeside) {
                    return new DecisionOption(DecisionType.CARRY, 100.0,
                            "cut inside from byline (col=" + String.format("%.1f", col)
                                    + ", clear beside)");
                }
                return new DecisionOption(DecisionType.CENTER, 100.0,
                        "center into box from byline (col=" + String.format("%.1f", col) + ")");
            }

            // Still space ahead: carry STRAIGHT up the line (same column).
            DecisionOption straight = new DecisionOption(DecisionType.CARRY, 100.0,
                    "straight carry up the flank (col=" + String.format("%.1f", col)
                            + ", space ahead)");
            straight.setStraightLineCarry(true);
            return straight;
        }

        // If any defender within 1.5 cells — too much pressure for a sideline carry
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            if (SimUtils.distance(carrier.getPosition(), p.getPosition()) < 1.5) {
                return null;
            }
        }

        // Near corner line? (within 1 cell of end line)
        boolean nearCornerLine = home ? row >= 6.0 : row <= 2.0;

        // Check for teammates in the attacking box
        boolean hasTeammateInBox = false;
        for (Player p : state.getPlayers()) {
            if (!p.getTeam().equals(carrier.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (home ? p.getPosition().getRow() >= 6.0 : p.getPosition().getRow() <= 2.0) {
                hasTeammateInBox = true;
                break;
            }
        }

        // Near corner line: CENTER if teammates in box, otherwise dribble toward goal
        if (nearCornerLine) {
            if (hasTeammateInBox) {
                return new DecisionOption(DecisionType.CENTER, 100.0,
                        "center from corner zone (col=" + String.format("%.1f", col) + ")");
            }
            return new DecisionOption(DecisionType.CARRY, 100.0,
                    "dribble toward goal from corner (line=" + String.format("%.1f", col) + ")");
        }

        // Wide with space: carry along the line or cross
        if (hasTeammateInBox) {
            return new DecisionOption(DecisionType.CROSS, 100.0,
                    "cross from wide position (col=" + String.format("%.1f", col) + ")");
        }

        return new DecisionOption(DecisionType.CARRY, 100.0,
                "carry along sideline (col=" + String.format("%.1f", col) + ")");
    }

    /**
     * True if no OPPONENT of the carrier is within `radius` cells of the given
     * point (used to evaluate open flank space).
     */
    private boolean anyOpponentWithin(Player carrier, boolean home, double col, double row, double radius) {
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(carrier.getTeam())) continue;
            double d = Math.sqrt(Math.pow(row - p.getPosition().getRow(), 2)
                    + Math.pow(col - p.getPosition().getColumn(), 2));
            if (d < radius) return true;
        }
        return false;
    }
}
