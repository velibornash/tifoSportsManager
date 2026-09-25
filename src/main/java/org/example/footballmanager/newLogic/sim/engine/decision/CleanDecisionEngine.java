package org.example.footballmanager.newLogic.sim.engine.decision;

import org.example.footballmanager.newLogic.sim.engine.ActionEngine;
import org.example.footballmanager.newLogic.sim.engine.MovementEngine;
import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.util.SimUtils;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Clean Decision Engine - ONLY scores and selects actions.
 * 
 * CORE PRINCIPLE: This engine ONLY provides a decision (PASS/SHOT/DRIBBLE/CLEAR).
 * It does NOT override the decision with hard rules like "MUST SHOT".
 * It does NOT re-decide mid-action. It does NOT move players.
 * It only scores options and returns the highest-scoring one.
 * 
 * The execution engine is responsible for carrying out the decision.
 */
public class CleanDecisionEngine {

    /**
     * Beyond this (in cells, 1 cell = 14 m) a receiver is CLEARLY offside and is
     * never a pass target: 0.5 cells = 7 m, per the user's rule ("kad je vise od
     * 0.5 cella igrac u offside, sto je 7m, nema ni smisla da ide pass").
     */
    private static final double OFFSIDE_HARD_LIMIT = 0.5;

    /**
     * Chance that a carrier with the given playmaking skill (1-20) still plays a
     * pass to a team-mate standing inside the 7 m offside band. Skill 1 plays it
     * ~95% of the time, skill 20 ~10%: "veci skill ce retko gadati offside".
     */
    private static boolean carrierWillRiskOffside(Player carrier) {
        int pm = (int) Math.round(carrier.getSkills().playmaking());
        double pmClamped = Math.max(1, Math.min(20, pm));
        double chance = 0.95 - (pmClamped - 1) / 19.0 * 0.85;
        return SimulationRandom.nextDouble() < chance;
    }

    /**
     * Score that makes an action strictly worse than every real alternative, so
     * the selection can never choose it. Used as a genuine VETO (see the shot
     * frequency gate) rather than a penalty: a "penalised" option is still chosen
     * whenever it is the least-bad one, which is what produced 83 shots a match.
     */
    private static final double UNAVAILABLE = -10_000.0;

    /**
     * Share of final-third touches that become a shot attempt. Real sides take
     * ~25 shots per match, i.e. roughly one attempt every 90 seconds, not on
     * every touch. Tuned with `ProposalSeasonDiag 150 42`.
     */
    private static final double SHOT_FREQUENCY_GATE = 0.17;

    /**
     * Share of eligible moments that become a through ball. Real sides play
     * 5-10 per match; at 0.4 the engine played 106 a match. A good playmaker
     * reads the defence more often, so the gate scales with the skill.
     */
    private static final double THRU_FREQUENCY_GATE = 0.032;

    /**
     * Share of eligible moments that become a cross or a centre. Real sides
     * deliver 15-25 crosses and 25-35 centres a match, i.e. these are ROUTINE
     * in the final third, not a special event.
     */
    private static final double DELIVERY_FREQUENCY_GATE = 0.45;

    private static final double[] PLAYMAKING_ACCURACY_TABLE = {
        0.40, 0.42, 0.44, 0.46, 0.48, 0.50, 0.52, 0.54, 0.56, 0.58,  // 1-10
        0.60, 0.62, 0.64, 0.66, 0.68, 0.70, 0.72, 0.74, 0.76, 0.78,  // 11-20
        0.80  // 21 (capped)
    };

    public DecisionOption decide(MatchState state) {
        return decideWithOptions(state).getChosen();
    }

    public DecisionResult decideWithOptions(MatchState state) {
        Player carrier = state.getCarrier();
        if (carrier == null) {
            return new DecisionResult(
                    new DecisionOption(ActionType.PASS, null, 0.0, "no carrier"),
                    List.of());
        }

        // KICKOFF — never pass to the goalkeeper; force a short forward/lateral
        // pass to an open, non-GK teammate. Mirrors demo/service generateKickoffPass.
        // NOTE: kickoffPending is NOT cleared here — it stays true until the
        // pass is actually LAUNCHED (ActionExecutor.executePass), so the launch
        // path can detect it and make the kickoff pass exact + max-speed
        // (user rule 2026-09-23). It is also consumed by OffsideService during
        // the kickoff pass flight.
        if (state.isKickoffPending()) {
            DecisionOption kickoffPass = kickoffOption(state, carrier);
            if (kickoffPass != null && kickoffPass.getTarget() != null) {
                return new DecisionResult(kickoffPass, List.of(kickoffPass));
            }
        }

        Player receiver = findBestReceiver(state, carrier);

        // Gather all possible options
        DecisionOption passOption = scorePassOptions(state, carrier, receiver);
        DecisionOption carryOption = scoreCarryOptions(state, carrier);
        DecisionOption shotOption = scoreShotOptions(state, carrier);
        DecisionOption clearOption = scoreClearOptions(state, carrier);
        // Distinct attacking deliveries (previously every one of them was a
        // generic PASS, so the engine had no way to get a ball behind a defence
        // or into a box and the report could not show them).
        DecisionOption thruOption = scoreThruOptions(state, carrier);
        DecisionOption crossOption = scoreCrossOptions(state, carrier);
        DecisionOption centerOption = scoreCenterOptions(state, carrier);

        // Apply playmaking-based selection (not hard rules)
        double pmSkill = carrier.getSkills().playmaking();
        int accuracyIndex = SimUtils.clampInt((int) pmSkill, 1, 20);
        double baseAccuracy = PLAYMAKING_ACCURACY_TABLE[accuracyIndex - 1];

        // Use weighted random selection based on scores
        DecisionOption chosen = selectOptionWithPlaymaking(
                passOption, carryOption, shotOption, clearOption, baseAccuracy, receiver);

        // FINAL-2-ROW HARD RULE (mirror of demo/service): a carrier in the last
        // two rows of the attacking third must not keep dribbling — either shoot
        // or deliver. Prevents tap-ins from the 6-yard line. Mirrored about 4.5,
        // so the AWAY bound is 3.0 (see scoreShotOptions).
        boolean home = "HOME".equals(carrier.getTeam());
        double carrierRow = carrier.getPosition().getRow();
        boolean finalTwoRows = home ? carrierRow >= 6.0 : carrierRow <= 3.0;
        if (finalTwoRows && chosen.getType() == ActionType.DRIBBLE) {
            // By the line the carrier must shoot OR deliver — never keep dribbling.
            if (shotOption.getScore() >= 0) {
                chosen = shotOption;
            } else {
                DecisionOption delivery = bestDelivery(thruOption, crossOption, centerOption);
                if (delivery != null) {
                    chosen = delivery;
                }
            }
        }

        // SharEd action logger — write the FULL decision trace on every decide
        // (no stream): chosen action + score + all viable alternatives (carry/
        // shot/clear always evaluated, pass when a receiver exists) + the reason
        // string. Same "every engine that decides writes" contract as the demo
        // baseline; the orchestrator's DEC line remains the change-gated
        // viewer/event summary.
        if (state.getActionLogger() != null) {
            state.getActionLogger().log("DEC",
                    state.getActionLogger().formatDecision(carrier,
                            new DecisionResult(chosen,
                                    List.of(passOption, carryOption, shotOption, clearOption,
                                            thruOption, crossOption, centerOption))));
        }

        return new DecisionResult(chosen, List.of(passOption, carryOption, shotOption, clearOption,
                thruOption, crossOption, centerOption));
    }

    private DecisionOption scorePassOptions(MatchState state, Player carrier, Player receiver) {
        double score = 0.0;
        StringBuilder reason = new StringBuilder("PASS: ");

        if (receiver != null) {
            // Basic pass value
            score += 10.0;

            // Forward / lateral / backward weights - the ball must move toward goal
            boolean home = "HOME".equals(carrier.getTeam());
            double forwardSteps = home ? receiver.getPosition().getRow() - carrier.getPosition().getRow()
                                       : carrier.getPosition().getRow() - receiver.getPosition().getRow();
            if (forwardSteps > 0) {
                score += 50.0;
                reason.append("forward+50 ");
            } else if (forwardSteps < -0.2) {
                score -= 80.0; // backward pass is last resort
                reason.append("backward ");
            } else {
                score -= 10.0; // lateral
                reason.append("lateral ");
            }

            // Goal proximity for forward passes
            double prox = goalProximity(home, receiver.getPosition().getRow());
            if (forwardSteps > 0) {
                score += prox * 3.5;
                reason.append("prox +" + String.format("%.1f ", prox * 3.5));
            }

            // Distance factor (prefer short passes, discourage long deep balls)
            // 1 cell = 14 m → real short pass ≈ 0.8-1.5 cells (11-21 m)
            double dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
            if (dist >= 0.8 && dist <= 1.5) {
                score += 25.0;
                reason.append("short pass ");
            } else if (dist > 1.5 && dist <= 2.5) {
                score += 5.0;
                reason.append("medium pass ");
            } else if (dist > 2.5) {
                score -= 35.0;
                reason.append("long pass penalized ");
            }

            // Receiver openness (up to +30) and direct marking penalty
            double openness = calculateOpenness(state, receiver);
            score += Math.max(0, openness) * 30.0;
            reason.append(String.format("openness %.1f ", openness));
            if (openness < 0.17) {
                score -= 40.0;
                reason.append("marked ");
            }

            // Lane blocked by an opponent between passer and receiver -> deciding negative
            double lanePenalty = laneBlockPenalty(state, carrier, receiver);
            score -= lanePenalty * 80.0;
            if (lanePenalty > 0) reason.append("lane blocked ");

            // Playmaking boost
            score += carrier.getSkills().playmaking() * 0.15;
            // Passing skill boost
            score += carrier.getSkills().passing() * 0.10;

            reason.append(String.format("-> %s", receiver.getRole()));
        } else {
            score = -50.0; // No receiver
            reason.append("no receiver");
        }

        return new DecisionOption(ActionType.PASS, receiver, score, reason.toString());
    }

    private DecisionOption scoreCarryOptions(MatchState state, Player carrier) {
        // Carrying maps to ActionType.DRIBBLE (ActionType has no CARRY)
        double score = 12.0; // Base carry value
        StringBuilder reason = new StringBuilder("CARRY: ");

        // Can we move forward?
        double forward = getForwardDirection(state.getCarrierTeam());
        Position target = new Position(
                carrier.getPosition().getRow() + forward * 3.0,
                carrier.getPosition().getColumn()
        );

        // Check if path is clear
        if (!isPathBlocked(state, carrier, target)) {
            score += 12.0;
            reason.append("clear path ");
        }

        // Carrying forward progresses play - reward it
        boolean home = "HOME".equals(carrier.getTeam());
        double prox = goalProximity(home, carrier.getPosition().getRow());
        score += prox * 2.5;
        reason.append("prox +" + String.format("%.1f ", prox * 2.5));

        // Pace helps carrying
        score += carrier.getSkills().pace() * 0.15;
        reason.append("pace ");

        // Stamina affects carry
        score += (20.0 - carrier.getFatigue()) * 0.05;
        reason.append("stamina ");

        // Pressure hurts carrying (player can be dispossessed)
        double pressure = calculatePressure(state, carrier);
        score -= pressure * 30.0;
        reason.append(String.format("press -%.0f ", pressure * 30.0));

        reason.append(String.format("to (%.1f,%.1f)", target.getRow(), target.getColumn()));

        // RIGID CARRY GUARD (mirror of demo/service "carry boundary freeze" +
        // final-row hard rules): at the byline the carry target clamps back onto
        // the carrier's own position (ActionExecutor min row 1.5 / max row 7.5),
        // so a DRIBBLE here makes NO progress and would re-win forever — a silent
        // standing-spot loop. Ban it: the carrier must shoot or deliver instead.
        double row = carrier.getPosition().getRow();
        // Mirror the executor's carry clamp exactly (ActionExecutor: forward 3,
        // HOME [1.0, 7.5] / AWAY [1.5, 8.0]) and ban the carry when it cannot make
        // forward progress — near the byline the clamp lands on the playable cap
        // row, and the Movement Engine converges on the cap with floating-point
        // error (7.4999999…), so `row >= 7.5` never triggers. Abs(progress) < eps
        // means the dribble is a standing-spot loop: force SHOT or a delivery.
        double carryMinRow = home ? 1.0 : 1.5;
        double carryMaxRow = home ? 7.5 : 8.0;
        double targetRow = SimUtils.clamp(row + (home ? 3.0 : -3.0), carryMinRow, carryMaxRow);
        boolean bylineTrapped = Math.abs(targetRow - row) < 0.05;
        if (bylineTrapped) {
            score = -60.0;
            reason.append("byline - no forward space ");
        }

        return new DecisionOption(ActionType.DRIBBLE, null, score, reason.toString());
    }

    private DecisionOption scoreShotOptions(MatchState state, Player carrier) {
        double score = 0.0;
        StringBuilder reason = new StringBuilder();

        // Only shoot in shooting zone. The band must be an exact mirror about the
        // half-way line (4.5): HOME shoots from rows 6.0-8.0 (2 cells deep before
        // the AWAY goal at 8.0), so AWAY must shoot from rows 1.0-3.0 (2 cells
        // deep before the HOME goal at 1.0). The AWAY bound used to be 2.0, which
        // made the zone half as deep for AWAY and is why AWAY's chances were all
        // point-blank (the keeper's reach swallowed the whole goal mouth).
        String team = carrier.getTeam();
        double carrierRow = carrier.getPosition().getRow();
        boolean inShootingZone = "HOME".equals(team) ? carrierRow >= 6.0 : carrierRow <= 3.0;

        if (!inShootingZone) {
            return new DecisionOption(ActionType.SHOT, null, UNAVAILABLE,
                    "SHOT: not in zone");
        }

        // Frequency gate — only a fraction of final-third touches are shots
        // (real teams recycle, hold and probe instead of shooting every touch).
        //
        // The gate has to make the shot UNAVAILABLE, not merely "less attractive".
        // It used to return -20, which still beat every alternative whenever the
        // pass (-60..-90) and carry (-60) options were worse — so in the final
        // third the engine shot almost every touch, because a shot was always the
        // least-bad option. Measured: 83 shots per match. The gate now vetoes.
        if (SimulationRandom.nextDouble() > SHOT_FREQUENCY_GATE) {
            return new DecisionOption(ActionType.SHOT, null, UNAVAILABLE,
                    "SHOT: freq gate");
        }

        // Basic shot value
        score += 20.0;
        reason.append("in zone ");

        // Striker skill
        score += carrier.getSkills().striker() * 0.20;
        reason.append("striker ");

        // Technique for placement
        score += carrier.getSkills().technique() * 0.10;
        reason.append("technique ");

        // Distance to goal (closer = better)
        Position goal = ActionEngine.goalPositionFor(team);
        double distToGoal = SimUtils.distance(carrier.getPosition(), goal);
        if (distToGoal < 2.0) {
            score += 15.0;
            reason.append("close ");
        } else if (distToGoal < 3.5) {
            score += 8.0;
            reason.append("medium ");
        } else if (distToGoal < 5.0) {
            score += 3.0;
            reason.append("long ");
        } else {
            score -= 15.0;
            reason.append("too far ");
        }

        // Check if goal is open. A jammed lane is a real veto, not a penalty: a
        // shot through three bodies is never the best available option, and
        // "close" shots were being taken from inside a wall.
        double openness = calculateGoalOpenness(state, carrier, goal);
        if (openness < 0.1) {
            return new DecisionOption(ActionType.SHOT, null, UNAVAILABLE,
                    "SHOT: lane jammed (open " + String.format("%.2f", openness) + ")");
        }
        score += openness * 40.0;
        reason.append(String.format("goal open %.1f ", openness));

        // Pressure hurts shooting
        double pressure = calculatePressure(state, carrier);
        score -= pressure * 25.0;
        reason.append(String.format("press -%.0f ", pressure * 25.0));

        reason.append(String.format("-> %.1f dist", distToGoal));
        return new DecisionOption(ActionType.SHOT, null, score, "SHOT: " + reason);
    }

    /**
     * Distance from a player to the OPPONENT's goal line, in cells
     * (0 = standing on the goal line, ~3.5 = own half-way line).
     *
     * Mirrored exactly: HOME's opponent goal is row 8.0, AWAY's is row 1.0.
     * It used to be {@code home ? row : 8.0 - row}, which measured HOME from row
     * 0 (a constant +1.0-cell HOME bonus) instead of from its target goal. The
     * scale is deliberately preserved, because the caller multiplies it by 3.5
     * to reward forward passes.
     */
    private static double goalProximity(boolean home, double row) {
        return home ? 8.0 - row : row - 1.0;
    }

    /** First non-vetoed option among the deliveries (used by the final-row rule). */
    private static DecisionOption bestDelivery(DecisionOption... options) {
        DecisionOption best = null;
        for (DecisionOption o : options) {
            if (o == null || o.getScore() <= UNAVAILABLE) continue;
            if (best == null || o.getScore() > best.getScore()) best = o;
        }
        return best != null && best.getScore() > 0 ? best : null;
    }

    /**
     * THROUGH BALL — a driven pass played into the space BEHIND the defensive
     * line for a forward to run onto ("eventualno iza ledja odbrane za spica").
     *
     * Requires an onside team-mate beyond the second-to-last defender (the
     * offside margin must be <= 0, i.e. level with or behind the line) and
     * rewards the striker skill of that team-mate plus the carrier's playmaking.
     */
    private DecisionOption scoreThruOptions(MatchState state, Player carrier) {
        StringBuilder reason = new StringBuilder("THRU: ");
        String team = carrier.getTeam();
        double carrierRow = carrier.getPosition().getRow();
        boolean home = "HOME".equals(team);
        // Only worth playing from the attacking half.
        boolean inAttackingHalf = home ? carrierRow >= 3.0 : carrierRow <= 6.0;
        if (!inAttackingHalf) {
            return new DecisionOption(ActionType.THRU, null, UNAVAILABLE, "THRU: not in attacking half");
        }

        Player best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Player mate : state.getPlayers()) {
            if (!mate.getTeam().equals(team) || mate.equals(carrier)) continue;
            if (mate.isUnavailable()) continue;
            // Must be ON the line or behind it — a pass beyond it is offside.
            if (offsideMargin(state, carrier, mate) > 0) continue;
            // Prefer a forward.
            double score = mate.isAttacker() ? 30.0 : 0.0;
            score += mate.getSkills().pace() * 1.5;          // he has to run onto it
            score -= SimUtils.distance(carrier.getPosition(), mate.getPosition()) * 4.0;
            // Space behind him: the further the defensive line is up the pitch,
            // the more room he has to run into.
            double line = secondLastDefenderRow(state, team);
            double spaceBehind = home ? line - mate.getPosition().getRow()
                                      : mate.getPosition().getRow() - line;
            score += Math.max(0, Math.min(1.5, spaceBehind)) * 20.0;
            score -= calculateOpenness(state, mate) * 10.0;   // a marked striker is useless
            if (score > bestScore) {
                bestScore = score;
                best = mate;
            }
        }
        if (best == null) {
            return new DecisionOption(ActionType.THRU, null, UNAVAILABLE, "THRU: nobody behind the line");
        }

        // Playmaking decides whether the through ball is actually played. The
        // gate is small on purpose: a real side plays ~5-10 through balls a
        // match, not one every time a forward happens to be level with the line.
        double pm = carrier.getSkills().playmaking();
        if (SimulationRandom.nextDouble() > THRU_FREQUENCY_GATE) {
            return new DecisionOption(ActionType.THRU, null, UNAVAILABLE, "THRU: freq gate");
        }

        double score = 10.0 + bestScore * 0.5
                + carrier.getSkills().passing() * 0.20 + pm * 0.25
                - calculatePressure(state, carrier) * 20.0;
        reason.append(String.format("pace %.0f behind-line %.2f -> %s",
                best.getSkills().pace(), 0.0, best.getLabel()));
        return new DecisionOption(ActionType.THRU, best, score, reason.toString());
    }

    /**
     * CROSS — a lofted delivery from the FLANK into the box for a team-mate to
     * attack. The wide player ("po strani u prostor za bocnog igraca") is the
     * crosser; the target is the best-attacking team-mate inside the box.
     */
    private DecisionOption scoreCrossOptions(MatchState state, Player carrier) {
        return scoreBoxDelivery(state, carrier, true);
    }

    /**
     * CENTER — the same lofted delivery into the box, but from a less extreme
     * wide position, aimed at the middle of the area rather than the far side.
     */
    private DecisionOption scoreCenterOptions(MatchState state, Player carrier) {
        return scoreBoxDelivery(state, carrier, false);
    }

    /**
     * Shared scoring for CROSS (from the flank) and CENTER (from a wide but not
     * extreme position). Both need a ball in the final third and a team-mate in
     * the box; they differ in how far from the centre line the crosser must be.
     */
    private DecisionOption scoreBoxDelivery(MatchState state, Player carrier, boolean fromFlank) {
        ActionType type = fromFlank ? ActionType.CROSS : ActionType.CENTER;
        StringBuilder reason = new StringBuilder(type + ": ");
        String team = carrier.getTeam();
        double row = carrier.getPosition().getRow();
        boolean home = "HOME".equals(team);
        boolean inFinalThird = home ? row >= 5.0 : row <= 4.0;
        if (!inFinalThird) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": not in final third");
        }
        double wide = Math.abs(carrier.getPosition().getColumn() - 4.0);
        if (fromFlank && wide < 1.5) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": not wide enough");
        }
        if (!fromFlank && (wide < 0.5 || wide > 2.5)) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": not in a crossing position");
        }

        // Best attacker inside the box, and how many team-mates are in there.
        Player target = null;
        double bestAttack = -Double.MAX_VALUE;
        int inBox = 0;
        for (Player mate : state.getPlayers()) {
            if (!mate.getTeam().equals(team) || mate.equals(carrier)) continue;
            if (mate.isUnavailable()) continue;
            if (!isInBox(state, mate, team)) continue;
            inBox++;
            double attack = mate.getSkills().striker() * 1.2 + mate.getSkills().technique() * 0.8
                    + (mate.isGoalkeeper() ? -50 : 0);
            if (attack > bestAttack) {
                bestAttack = attack;
                target = mate;
            }
        }
        if (target == null || inBox < 2) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": nobody in the box");
        }

        double pm = carrier.getSkills().playmaking();
        if (SimulationRandom.nextDouble() > DELIVERY_FREQUENCY_GATE) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": freq gate");
        }

        double score = 8.0
                + carrier.getSkills().technique() * 0.30 + pm * 0.30
                + carrier.getSkills().passing() * 0.20
                + Math.min(3, inBox) * 4.0
                - calculatePressure(state, carrier) * 15.0
                - SimUtils.distance(carrier.getPosition(), target.getPosition()) * 1.5;
        reason.append(String.format("%d in box, %s (striker %.0f) width %.1f",
                inBox, target.getLabel(), target.getSkills().striker(), wide));
        return new DecisionOption(type, target, score, reason.toString());
    }

    /** Is this player inside the penalty area the given team is attacking? */
    private boolean isInBox(MatchState state, Player player, String attackingTeam) {
        boolean home = "HOME".equals(attackingTeam);
        double row = player.getPosition().getRow();
        boolean inRows = home ? row >= 6.5 : row <= 2.5;
        return inRows && Math.abs(player.getPosition().getColumn() - 3.5) <= 1.5;
    }

    /** Row of the second-to-last defender of the team ATTACKING (the offside line). */
    private double secondLastDefenderRow(MatchState state, String attackingTeam) {
        boolean home = "HOME".equals(attackingTeam);
        String defendingTeam = home ? "AWAY" : "HOME";
        java.util.List<Double> rows = new ArrayList<>();
        for (Player opp : state.getPlayers()) {
            if (!defendingTeam.equals(opp.getTeam())) continue;
            if (opp.isSentOff() || opp.isInjured()) continue;
            rows.add(opp.getPosition().getRow());
        }
        if (rows.size() < 2) return home ? 8.0 : 1.0;
        rows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
        return rows.get(1);
    }

    private DecisionOption scoreClearOptions(MatchState state, Player carrier) {
        double score = 0.0;
        StringBuilder reason = new StringBuilder();

        // Only clear when under pressure in defensive third. Mirrored about 4.5:
        // HOME defends rows 1.0-3.0 (2 cells from its own goal at 1.0), so AWAY
        // must defend rows 5.0-7.0. The bound used to be 5.0, i.e. a THREE-cell
        // band for AWAY, which let only AWAY clear from the middle third.
        String team = carrier.getTeam();
        double carrierRow = carrier.getPosition().getRow();
        boolean inDefensiveThird = "HOME".equals(team) ? carrierRow <= 3.0 : carrierRow >= 6.0;

        // Same lesson as the shot gate: this has to be a VETO. At -40 the
        // clearance still beat PASS=-60..-90 and DRIBBLE=-60, so it was picked
        // whenever it was the least-bad option — including deep in the OPPONENT's
        // half, where "clear away from my own goal" hoofed the ball at the
        // opponent's goal line. Measured: 41% of AWAY clearances and 32% of HOME
        // clearances were launched from the wrong half, which sent the ball out
        // for goal kicks 59 times a match and produced the whole territorial skew
        // (HOME 37 goal kicks vs AWAY 22, AWAY 9.2 corners vs HOME 0.3).
        if (!inDefensiveThird) {
            return new DecisionOption(ActionType.CLEAR, null, UNAVAILABLE,
                    "CLEAR: not in defensive third");
        }

        // Pressure from opponents
        double pressure = calculatePressure(state, carrier);
        if (pressure > 0.5) {
            score += 15.0;
            reason.append("under pressure ");
        }

        // Defending skill helps clearance
        score += carrier.getSkills().defender() * 0.15;
        reason.append("defending ");

        // Clearance value
        score += 10.0;
        reason.append("clearance ");

        reason.append(String.format("pressure %.1f", pressure));
        return new DecisionOption(ActionType.CLEAR, null, score, "CLEAR: " + reason);
    }

    private Player findBestReceiver(MatchState state, Player carrier) {
        // Best teammate = most open + clear lane + forward progress. A blocked
        // lane is the deciding negative: threading a ball through the block is
        // how passes get intercepted (demo/service lane weight ±80).
        Player best = null;
        double bestScore = -Double.MAX_VALUE;
        boolean home = "HOME".equals(carrier.getTeam());

        for (Player teammate : state.getPlayers()) {
            if (!teammate.getTeam().equals(carrier.getTeam()) || teammate.equals(carrier)) continue;

            // OFFSIDE (user rule 2026-09-25): "kad je vise od 0.5 cella igrac u
            // offside, sto je 7m, nema ni smisla da ide pass, ali unutar 7m moze
            // da krene pass... veci skill ce retko gadati offside".
            //  - beyond 0.5 cells (7 m) he is CLEARLY offside: never a target.
            //  - inside 0.5 cells the pass may be played, and whether the carrier
            //    actually does it depends on his PLAYMAKING skill (1-20): a
            //    poor passer sees the flag and plays it anyway, a good one
            //    almost never does.
            // The rules layer (OffsideService) kills such a pass at reception, so
            // selecting purely-offside targets was self-sabotage: measured with
            // ProposalPassFailDiag, 17.5 offsides per match and 20% of all
            // failed passes.
            double offsideMargin = offsideMargin(state, carrier, teammate);
            if (offsideMargin > OFFSIDE_HARD_LIMIT) {
                continue;   // clearly offside (>7 m) — not a passing option at all
            }
            boolean marginalOffside = offsideMargin > 0;

            double openness = calculateOpenness(state, teammate);
            double dist = SimUtils.distance(carrier.getPosition(), teammate.getPosition());
            double forward = home ? teammate.getPosition().getRow() - carrier.getPosition().getRow()
                                  : carrier.getPosition().getRow() - teammate.getPosition().getRow();
            double prox = goalProximity(home, teammate.getPosition().getRow());
            // Lane check: opponents between carrier and receiver block the pass.
            double lanePenalty = laneBlockPenalty(state, carrier, teammate) * 80.0;
            // How much space the receiver has (0-1) -> up to +30
            double openScore = Math.max(0, openness) * 30.0;
            // Receiver under direct pressure (opponent within ~0.5 cell) is a bad target
            double pressure = calculateOpenness(state, teammate);
            if (pressure < 0.17) openScore -= 40.0; // ~2.3m of space or less = marked
            // Forward progress modest (forward +, lateral 0, backward big minus)
            double direction = forward > 0 ? 50.0 : forward < -0.2 ? -80.0 : -10.0;
            // Long balls are risky – bias toward short combinations
            // 1 cell = 14 m → ideal short pass 0.8-1.5 cells
            double distancePenalty = 0.0;
            if (dist > 2.5) {
                direction -= 35.0;
                distancePenalty = (dist - 2.5) * 12.0;
            } else if (dist < 0.7) {
                distancePenalty = 8.0; // too close, not progressive
            }
            // Marginal offside: the pass is still an option, but only if the
            // carrier's playmaking lets him get away with it.
            if (marginalOffside && !carrierWillRiskOffside(carrier)) {
                continue;
            }
            double score = direction + openScore + Math.max(0, prox) * 2.0
                    - distancePenalty
                    - lanePenalty;
            if (score > bestScore) {
                bestScore = score;
                best = teammate;
            }
        }
        return best;
    }

    /**
     * True when the closest opponent to this receiver can get to the ball's
     * landing spot (approximated by the receiver's own position) before the
     * receiver can. A real passer checks exactly this before playing the pass.
     *
     * Without it the engine played passes straight into a spot a wide player had
     * already been standing in: the owner watched the away right attacker arrive
     * at our second row before the ball did and simply win it. No midfielder
     * plays that ball, and neither should the AI.
     */
    private boolean nearestOpponentBeatsHimToIt(MatchState state, Player receiver) {
        Player marker = closestMarkerTo(state, receiver);
        if (marker == null) return false;

        Position landing = receiver.getPosition();
        double receiverSpeed = speedOf(state, receiver);
        double markerSpeed = speedOf(state, marker);
        if (receiverSpeed <= 0) return true;

        double receiverTime = SimUtils.distance(receiver.getPosition(), landing) / receiverSpeed;
        double markerTime = SimUtils.distance(marker.getPosition(), landing) / markerSpeed;
        // A small edge to the receiver: he is already moving to the ball, the
        // marker may have to turn his whole body around.
        return markerTime < receiverTime * 0.85;
    }

    private Player closestMarkerTo(MatchState state, Player player) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(player.getTeam()) || p.isUnavailable()) continue;
            double d = SimUtils.distance(p.getPosition(), player.getPosition());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Movement speed of a player this tick, in cells per tick. */
    private double speedOf(MatchState state, Player p) {
        int pace = state.getRoundPaceSkill(p);
        if (pace <= 0) pace = (int) Math.round(p.getSkills().pace());
        return Math.max(0.05, MovementEngine.playerSpeedFor(pace));
    }

    /**
     * How far (in cells) the receiver is beyond the second-to-last defender, or a
     * large NEGATIVE value when he is clearly onside (own half, behind the ball,
     * or simply not level with the line). Mirrors OffsideService's margin so the
     * decision layer and the rules layer agree on who is offside.
     */
    private double offsideMargin(MatchState state, Player carrier, Player receiver) {
        boolean home = "HOME".equals(receiver.getTeam());
        double receiverRow = receiver.getPosition().getRow();
        boolean inOppHalf = home ? receiverRow > 4.5 : receiverRow < 4.5;
        boolean forwardOfBall = home
                ? receiverRow > carrier.getPosition().getRow()
                : receiverRow < carrier.getPosition().getRow();
        if (!inOppHalf || !forwardOfBall) {
            return -5.0;
        }
        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> rows = new ArrayList<>();
        for (Player opp : state.getPlayers()) {
            if (!defendingTeam.equals(opp.getTeam())) continue;
            if (opp.isSentOff() || opp.isInjured()) continue;
            rows.add(opp.getPosition().getRow());
        }
        if (rows.size() < 2) return home ? 10.0 : -10.0;
        rows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
        double lineRow = rows.get(1);
        return home ? receiverRow - lineRow : lineRow - receiverRow;
    }

    /** Number of opponents whose body lies inside the passing corridor (0..1 normalized). */
    private double laneBlockPenalty(MatchState state, Player passer, Player receiver) {
        if (receiver == null) return 1.0;
        Position a = passer.getPosition();
        Position b = receiver.getPosition();
        double corridor = 0.35; // cells around the pass line that block it (interception needs ~0.14)
        for (Player opponent : state.getPlayers()) {
            if (!opponent.getTeam().equals(passer.getTeam())) {
                double d = pointToLineDistance(opponent.getPosition(), a, b);
                if (d < corridor) {
                    return 1.0;
                }
            }
        }
        return 0.0;
    }

    /** Kickoff: pick the best non-GK, non-defensive-row teammate, never backward to GK. */
    private DecisionOption kickoffOption(MatchState state, Player carrier) {
        boolean home = "HOME".equals(carrier.getTeam());
        Player best = null;
        double bestScore = -Double.MAX_VALUE;

        for (Player candidate : state.getPlayers()) {
            if (!candidate.getTeam().equals(carrier.getTeam()) || candidate.equals(carrier)) continue;
            if (isGoalkeeper(candidate)) continue;
            double openness = calculateOpenness(state, candidate);
            double col = candidate.getPosition().getColumn();
            double sidelineDist = Math.min(col - 1, 6 - col);
            // Quadratic sideline penalty — keep kickoff passes away from the touchline
            double sidelinePenalty = sidelineDist < 1.5 ? Math.pow(1.5 - sidelineDist, 2) * -15.0 : 0;
            // Reward receivers near the centre column (3.5) — safest short passports
            double centerBonus = (4.0 - Math.abs(col - 3.5)) * 3.0;
            double lanePenalty = laneBlockPenalty(state, carrier, candidate) * 30.0;
            double score = openness * 1.2 + 30 + sidelinePenalty + centerBonus - lanePenalty;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            // Absolute fallback: any non-GK teammate (never the GK at kickoff).
            for (Player candidate : state.getPlayers()) {
                if (candidate.getTeam().equals(carrier.getTeam())
                        && !candidate.equals(carrier) && !isGoalkeeper(candidate)) {
                    best = candidate;
                    break;
                }
            }
        }
        if (best == null) return null;
        return new DecisionOption(ActionType.PASS, best, bestScore,
                "kickoff pass to " + best.getRole());
    }

    private boolean isGoalkeeper(Player p) {
        return "GK".equals(p.getRole());
    }

    private double calculateOpenness(MatchState state, Player player) {
        // How much space does this player have?
        double minDistToOpponent = 100.0;
        String team = player.getTeam();

        for (Player opponent : state.getPlayers()) {
            if (!opponent.getTeam().equals(team) && !opponent.isSentOff() && !opponent.isInjured()) {
                double dist = SimUtils.distance(player.getPosition(), opponent.getPosition());
                minDistToOpponent = Math.min(minDistToOpponent, dist);
            }
        }
        // Normalize to 0-1 (1 = lots of space, 0 = tightly marked)
        return Math.min(1.0, minDistToOpponent / 3.0);
    }

    private double calculateGoalOpenness(MatchState state, Player shooter, Position goal) {
        // Is there a clear line to goal?
        Position ballPos = shooter.getPosition();
        double minDist = 100.0;

        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(shooter.getTeam()) || opponent.isSentOff()) continue;
            double distToLine = pointToLineDistance(opponent.getPosition(), ballPos, goal);
            minDist = Math.min(minDist, distToLine);
        }
        // Normalize (1 = clear, 0 = blocked)
        return Math.min(1.0, minDist / 2.0);
    }

    private double pointToLineDistance(Position p, Position a, Position b) {
        // Distance from point p to line segment a-b
        double dx = b.getColumn() - a.getColumn();
        double dy = b.getRow() - a.getRow();
        double lengthSq = dx * dx + dy * dy;

        if (lengthSq < 1e-9) {
            return SimUtils.distance(p, a);
        }

        double t = ((p.getColumn() - a.getColumn()) * dx
                  + (p.getRow() - a.getRow()) * dy) / lengthSq;
        t = SimUtils.clamp(t, 0.0, 1.0);

        double projRow = a.getRow() + t * dy;
        double projCol = a.getColumn() + t * dx;
        return SimUtils.distance(p, new Position(projRow, projCol));
    }

    private boolean isPathBlocked(MatchState state, Player from, Position to) {
        // Simplified path check
        return false;
    }

    private double calculatePressure(MatchState state, Player player) {
        int opponentsNear = 0;
        String team = player.getTeam();
        Position pos = player.getPosition();

        for (Player opponent : state.getPlayers()) {
            if (!opponent.getTeam().equals(team) && !opponent.isSentOff() && !opponent.isInjured()) {
                if (SimUtils.distance(pos, opponent.getPosition()) < 2.0) {
                    opponentsNear++;
                }
            }
        }
        return Math.min(1.0, opponentsNear / 3.0);
    }

    private double getForwardDirection(String team) {
        return "HOME".equals(team) ? 1.0 : -1.0;
    }

    private DecisionOption selectOptionWithPlaymaking(
            DecisionOption pass, DecisionOption carry, DecisionOption shot, DecisionOption clear,
            double playmakingAccuracy, Player receiver) {

        // Collect all options
        DecisionOption[] options = {pass, carry, shot, clear};

        // Filter out negative scores (unless all are negative)
        DecisionOption[] viable = Arrays.stream(options)
                .filter(o -> o.getScore() >= 0)
                .toArray(DecisionOption[]::new);

        if (viable.length == 0) {
            // All options negative - pick least bad
            viable = options;
            Arrays.sort(viable, (a, b) -> Double.compare(b.getScore(), a.getScore()));
            return viable[0];
        }

        // Sort by score descending
        Arrays.sort(viable, (a, b) -> Double.compare(b.getScore(), a.getScore()));

        DecisionOption best = viable[0];
        DecisionOption second = viable.length > 1 ? viable[1] : null;

        // Playmaking determines if we pick the best or consider alternatives
        if (second != null && (best.getScore() - second.getScore()) < 5.0) {
            // Close decision - use playmaking to break tie
            if (SimulationRandom.nextDouble() < playmakingAccuracy) {
                return best; // Take the best option
            } else {
                return second; // Take the second option (shows creativity)
            }
        }

        return best;
    }
}