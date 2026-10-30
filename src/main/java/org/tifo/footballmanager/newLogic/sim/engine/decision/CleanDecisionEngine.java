package org.tifo.footballmanager.newLogic.sim.engine.decision;

import org.tifo.footballmanager.newLogic.sim.engine.ActionEngine;
import org.tifo.footballmanager.newLogic.sim.engine.MovementEngine;
import org.tifo.footballmanager.newLogic.sim.model.*;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;

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
    public static final double OFFSIDE_HARD_LIMIT = 0.5; // public: MatchEngineVersion fingerprints it

    /**
     * Below this corner openness a shot is impossible.
     *
     * <p>0.06 rather than the 0.10 the centre-line test used. The radius below is 2.0 cells, so 0.06
     * is "nobody within 0.12 cells (1.7 m) of the way to that corner" — a body is on the shot. At 0.10
     * it was 2.8 m, which is the width of a player's stance and vetoed shots that had a clear
     * corridor. Measured over 40 matches the veto fired on 29.4% of all in-zone ticks; this is the
     * number that has to move, not the shot volume.
     */
    public static final double SHOT_MIN_OPENNESS = 0.12; // public: MatchEngineVersion fingerprints it

    /** Cells of clearance from the shot line that count as fully open. 2.0 cells is 28 m. */
    private static final double SHOT_OPENNESS_RADIUS = 2.0;

    /**
     * How far inside the post a "corner" aim sits, in cells. A tenth of a cell is 1.4 m, which is
     * roughly the clearance a finisher leaves — aim at the very edge of the frame and the
     * goalkeeper's save model, not the blocker, becomes the answer.
     */
    private static final double SHOT_CORNER_INSET = 0.1;

    /** What corner openness is worth in the shot's score. */
    private static final double SHOT_OPENNESS_WEIGHT = 22.0;

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

    /** Cache key for the frequency roll, named so the gate can be re-armed by hand. */
    private static final String CLEAR_GATE_KEY = "CLEAR_GATE";

    public static boolean carrierWillRiskOffsidePublic(Player carrier) {
        return carrierWillRiskOffside(carrier);
    }

    /** How far out is worth zero chance, in cells. Five cells is 70 m. */
    private static final double CHANCE_DISTANCE_RANGE = 5.0;

    // =========================================================================
    // ONE SCALE FOR EVERY OPTION
    // =========================================================================
    //
    // Each of the seven options is scored on its own arithmetic and nothing normalised
    // them, so the selector was comparing quantities that had nothing in common. Measured
    // across 197 real matches, 380,147 decisions:
    //
    //     PASS reached 109.6     THRU 39.4    SHOT 32.9    DRIBBLE 28.1
    //     CLEAR 27.8             CROSS 17.3   CENTER 16.4
    //
    // A cross had never once scored above 17.3 in any match. It could not lose to a good
    // pass, because a forward short pass to an open team-mate scores +50 for being forward
    // and +25 for being short before it has looked at anybody. The purpose-built options
    // were arithmetically dead.
    //
    // Every option is now worth 0..100 on one scale, where 100 is "the best football
    // available on this pitch right now", and they all speak the same vocabulary:
    //
    //     progress  how much closer to goal this leaves the team, signed
    //     threat    how dangerous the man who would receive it is
    //     space     how open the target is
    //     risk      how likely it is to turn the ball over
    //     pressure  how much trouble the carrier is in
    //
    // The bases differ because the actions genuinely differ in how much they are usually
    // worth — but a PASS is available on almost every tick, so its floor has to be low or
    // nothing else can ever win. The first attempt at this scale used a base of 35 and the
    // pass won everything: measured over 40 matches, dribbles collapsed from 22.5% of
    // decisions to 0.8% and a striker took only 44% of the shots in front of him. A base of
    // 8 says what it should: a pass is worth what it finds, not what it is.
    private static final double PASS_BASE = 8.0;
    private static final double SHOT_BASE = 5.0;
    private static final double DELIVERY_BASE = 12.0;
    private static final double DRIBBLE_BASE = 12.0;

    /**
     * What a chance is worth, per unit of chance quality.
     *
     * <p>A shot's {@code chance} is 0..1 over distance and corner openness — a tap-in is about 0.75
     * and a long-range attempt in traffic about 0.12. Multiplying by 95 puts a tap-in at ~71, a good
     * forward pass at ~50, and a hopeful one at ~11, which is the order a striker would put them in.
     *
     * <p>This is the weight that decides whether "when a player is in a good position he must shoot"
     * is true. At 45 a striker took 44% of the chances in front of him, because a merely good pass
     * outscored every shot; the pass floor came down at the same time and both halves of that
     * sentence have to move together.
     */
    private static final double SHOT_CHANCE_VALUE = 78.0;

    /**
     * Below this chance quality the shot does not exist — the position is not a shooting position.
     *
     * <p>Owner's ruling, 2026-10-23, after the shot frequency gate was removed ("SKLONI TO PRAVILO,
     * igrac treba da odluci u odnosu na to da li je pozicija za sut ili nije"): the judgement is the
     * POSITION, and it is the position and nothing else. A 0.30 dice roll was removed as ordered;
     * without any ceiling at all the measured flood came back — 65-81 shots per match over five
     * seeded matches, against the owner's own "30-40 udaraca je ok" and real football's ~25. A
     * re-added random roll barely dents it (67/match), because the flood is structural: an in-zone
     * carrier's shot outscores every alternative on nearly every touch, so the touch becomes a shot.
     *
     * <p>This is not a frequency gate in any form: no roll, no cache, no state. The same position
     * always gives the same answer — a yard from goal with the corners open is a shooting position
     * at any moment of any match, and a thirty-yard attempt in traffic never is.
     */
    public static final double SHOT_CHANCE_FLOOR = 0.35; // public: MatchEngineVersion fingerprints it
    // Legacy alias kept so older MatchEngineVersion snapshots (which still
    // reference the gate name) keep compiling. Value mirrors the floor.
    public static final double SHOT_FREQUENCY_GATE = SHOT_CHANCE_FLOOR;
    // F10: after this many ticks without forward progress, backward passes are penalised.
    private static final int F10_BACKWARD_PENALTY_TICKS = 100;

    /**
     * How close two options have to be before the carrier's judgement is consulted, in points on
     * the common scale.
     *
     * <p>Was 5.0 on a scale that reached 109, so it fired on 0.9% of decisions and the median
     * decision was settled by a 48-point margin. On a 0..100 scale a 5-point gap is a hair, so this
     * is 12 — the width of a real "he could see it either way".
     */
    public static final double CHOICE_BAND = 2.0; // public: MatchEngineVersion fingerprints it

    /**
     * How reliably a carrier picks the best option, at playmaking 1 and at playmaking 20.
     *
     * <p>Owner, 2026-10-18: "APSOLUTNO donosenje odluka MORA biti uvezano sa visinom playmaking
     * skila". Playmaking already reached this engine through two doors — the playmaking-weighted
     * tie-break and {@link #perceive}'s gate on whether a creative option is seen at all — and
     * through the first of them it fired once every hundred decisions. These are the two ends of
     * that door, and the whole spread between them is the skill.
     */
    private static final double ACCURACY_AT_SKILL_1 = 0.90;
    private static final double ACCURACY_AT_SKILL_20 = 0.98;

    /**
     * How much of the playmaking skill sees a receiver's danger, from skill 1 to skill 20.
     *
     * <p>A poor passer does not notice the striker in behind — not because the striker is not
     * there, but because he is not looking for him. This is the term that makes the same pass
     * option mean different things to a creative midfielder and a hoover, and it is applied to
     * {@link #receiverThreat} rather than added on top of it, because that is what a read is.
     */
    private static final double THREAD_MIN = 0.45;
    private static final double THREAD_MAX = 1.35;

    /**
     * Share of pressured touches in the defensive third that become a clearance.
     *
     * Without a gate, CLEAR always scores 13-28 (10 base + up to 15 under pressure
     * + defending skill), so it beat a negative pass and was chosen on essentially
     * every defensive-third touch: 127.8 clearances a match against a real ~18-20.
     * It grew to that after the goalProximity sign fix, which made the safe
     * backward pass properly unattractive and left CLEAR as the least-bad option.
     *
     * Rolled once per possession like the shot gate. Calibrated with
     * `ProposalSeasonDiag 200 42`: 0.16 -> 19.0 clearances (a real 18-20) but it
     * left a pressed defender with NO option at all in a large share of
     * defensive-third situations, and the probe measured the viable-option count
     * halving (1.76 -> 0.92) with the playmaking tie rate collapsing from 18% to
     * 0.3%. 0.34 -> 40.6 clearances with pass accuracy 80.7/81.3 and results
     * 86/84/30. Kept at 0.34: clearances stay well above real, but the engine
     * keeps a genuine long-ball fallback, which is what a real defender does when
     * he is stuck. REC 4 (weighted choice over all options) should let this gate
     * come back down, because a weighted selector uses CLEAR as the fallback
     * without it having to be a large share of touches.
     */
    public static final double CLEAR_FREQUENCY_GATE = 0.45; // public: MatchEngineVersion fingerprints it

    /**
     * Share of eligible moments that become a through ball. Real sides play
     * 5-10 per match; at 0.4 the engine played 106 a match. A good playmaker
     * reads the defence more often, so the gate scales with the skill.
     */
    public static final double THRU_FREQUENCY_GATE = 0.045; // public: MatchEngineVersion fingerprints it

    /**
     * Share of eligible moments that become a cross or a centre. Real sides
     * deliver 15-25 crosses and 25-35 centres a match, i.e. these are ROUTINE
     * in the final third, not a special event.
     */
    public static final double DELIVERY_FREQUENCY_GATE = 0.45; // public: MatchEngineVersion fingerprints it

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

        boolean home = "HOME".equals(carrier.getTeam());
        double carrierRow = carrier.getPosition().getRow();

        // DEFENSIVE-THIRD NO-DRIBBLE UNDER PRESSURE (owner rule 2026-09-25).
        //
        // The rule is real football and it is kept. What changed is its SHAPE. It used to veto the
        // dribble whenever the carrier was in his own third and any pass existed at all — measured,
        // that fired on 27.2% of every decision in a match, which is not a rule about defending, it
        // is a rule about never carrying the ball, and it was one of the reasons the carrier's own
        // scoring could not be trusted: it was being overridden on a quarter of all touches.
        //
        // "Do not dribble into trouble in front of your own goal" is what a manager actually says.
        // So it now asks the two questions that phrase asks — is he under pressure, and is there room
        // ahead — instead of asking where he happens to be standing. A ball-playing centre-back with
        // space in front of him still carries it out, which is the point of having one.
        boolean inOwnDefensiveThird = home ? carrierRow <= 3.5 : carrierRow >= 5.5;
        boolean pressured = calculatePressure(state, carrier) > 0.0;
        double forward = getForwardDirection(carrier.getTeam());
        Position carryLine = new Position(carrierRow + forward * 3.0, carrier.getPosition().getColumn());
        boolean roomAhead = !isPathBlocked(state, carrier, carryLine);
        // A defender approaching for a duel (within 0.5 cells) makes carrying
        // into him a bad idea even when there is open space ahead: the carrier
        // must move the ball (pass / clear / long pass) rather than run into
        // the tackle. Open space alone is still allowed (Q1, owner 2026-10-22).
        boolean defenderApproaching = false;
        for (Player opp : state.getPlayers()) {
            if (opp.getTeam().equals(carrier.getTeam())) continue;
            if (opp.isUnavailable() || opp.isLocked()) continue;
            if (SimUtils.distance(opp.getPosition(), carrier.getPosition()) <= 0.5) {
                defenderApproaching = true;
                break;
            }
        }
        boolean blockDribble = defenderApproaching || (pressured && !roomAhead);
        if (inOwnDefensiveThird && blockDribble && passOption.getScore() > UNAVAILABLE) {
            carryOption = new DecisionOption(ActionType.DRIBBLE, null, UNAVAILABLE,
                    "DRIBBLE: defender approaching or pressed with no room ahead in his own third, "
                            + "and a pass is available");
        }

        // PLAYMAKING VISIBILITY — what the carrier can even PERCEIVE.
        //
        // Until now every option was always on the table, so playmaking could not
        // influence HOW MANY choices a player had, only how a near-tie was broken
        // (the measured candidate count was a flat 2.05 from playmaking 1 to 20).
        // A creative pass is a read: a low-playmaking player does not see the run
        // behind the defence, the overlap on the flank or the centre hanging in
        // the box, so those options are not perceived at all. A high-playmaking
        // player sees most of them. This is the missing half of "skill affects the
        // number of options".
        //
        // The roll is cached per possession, like the shot gate: perception is a
        // read of the situation, not a fresh coin flip every tick.
        int pmIndex = SimUtils.clampInt((int) carrier.getSkills().playmaking(), 1, 20);
        // CENTER and CROSS are natural winger deliveries — they do not require high
        // playmaking to perceive (owner 2026-10-30: "Tu ne treba visok plej, to je
        // PRIRODNA OPCIJA"). Only the through ball (a creative run-behind read) is
        // gated by playmaking.
        thruOption = perceive(state, thruOption, "THRU_VISION",
                0.20 + pmIndex / 20.0 * 0.70, pmIndex);
        crossOption = perceive(state, crossOption, "CROSS_VISION", 1.0, pmIndex);
        centerOption = perceive(state, centerOption, "CENTER_VISION", 1.0, pmIndex);

        DecisionOption chosen = selectOptionWithPlaymaking(carrier,
                passOption, carryOption, shotOption, clearOption,
                thruOption, crossOption, centerOption);

        // EMERGENCY CLEAR (F7) — a carrier always has a legal action.
        //
        // Every option can be UNAVAILABLE at once: no receiver (PASS), a goalkeeper or the
        // byline (DRIBBLE), outside the zone (SHOT), the frequency gate or the wrong third
        // (CLEAR), nobody behind the line / not in the final third (THRU/CENTER). The
        // selector then returns the least bad of a menu of impossibilities, ActionExecutor
        // receives a null-target PASS and does nothing — and the carrier stands on the ball
        // for ever, re-deciding the same impossible situation every tick. Replay 5633:
        // 23 consecutive "PASS score=-20 [no receiver]" ticks from one frozen player.
        //
        // So when nothing at all is available the ball is hoofed upfield. It deliberately
        // bypasses both CLEAR gates (the defensive-third test and the frequency roll): this
        // is not "a defender under pressure chooses to clear", it is the last resort that
        // keeps the match moving. It costs a lost possession and gains a live ball.
        if (chosen == null || chosen.getScore() <= UNAVAILABLE) {
            chosen = new DecisionOption(ActionType.CLEAR, null, 0.0,
                    "CLEAR: emergency — no option was available at all");
        }

        // FINAL-2-ROW HARD RULE — REMOVED.
        //
        // "A carrier in the last two rows of the attacking third must not keep dribbling; shoot or
        // deliver." It was a substitute for the scoring, added when a striker could dribble down the
        // byline for ever because the carry scored better than anything else.
        //
        // It is dead code. Measured after the options were put on one scale: it overrode a
        // better-scoring dribble on ZERO decisions in 8,683. The byline guard inside
        // scoreCarryOptions — which vetoes a carry that would not actually move him — now does that
        // job on its own, and the rest of the rule was overriding a decision engine that had been
        // repaired.
        //
        // Kept here because the reason it stopped being needed is the thing to remember if anyone
        // proposes bringing it back: if the carry is worth more than the shot, the shot is
        // under-valued, and the fix is to the shot, not to the selector.

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

    /**
     * A pass, on the common scale.
     *
     * <p>Rewritten from geometry to <b>geometry plus the man at the end of it</b>. The old version
     * awarded +50 for being forward and +25 for being short — seventy-five points before it had
     * looked at a single player — and then nothing at all for who was receiving. Two consequences,
     * both measured over 40 matches: a cross could never score higher than 17.3 while a pass
     * reached 109.6, so the purpose-built options were dead; and the free striker inside the box was
     * passed over, because on 327 ticks where a striker stood level with a team-mate on the same row
     * the ball reached the striker 31 times.
     *
     * <p>Now a pass is its base, plus what it does for the team, plus how dangerous the receiver is
     * <em>as this carrier sees him</em>, plus how much room he has — and it pays for turning over.
     */
    private DecisionOption scorePassOptions(MatchState state, Player carrier, Player receiver) {
        StringBuilder reason = new StringBuilder("PASS: ");

        if (receiver == null) {
            // F7 (replay 5633, 2026-10-26): a pass to nobody is NOT an option, and at -20 it
            // used to be a LEGAL one. That score sat above UNAVAILABLE, so it passed the
            // "a pass is available" test that vetoes the dribble in the carrier's own third —
            // and the carrier then chose it every tick while ActionExecutor, receiving a
            // null target, did nothing at all. Measured in the replay: Kačar stood at
            // (2.2,2.9) from 0:21 to 0:55 — 23 ticks of "PASS score=-20 [no receiver]" and a
            // frozen player. An impossible pass must score exactly like every other impossible
            // option, so that the escape hatches (dribble, clearance) can fire.
            reason.append("no receiver");
            return new DecisionOption(ActionType.PASS, null, UNAVAILABLE, reason.toString());
        }

        // ME-2 backstop: the receiver pools already refuse an own-half target from a shooting
        // position. This makes the rule true at scoring time as well, so no future caller can
        // hand an own-half receiver in and have it scored like normal football.
        if (inShootingZone(carrier) && inOwnHalf(receiver)) {
            return new DecisionOption(ActionType.PASS, null, UNAVAILABLE,
                    "PASS: ME-2 — from a shooting position no pass back into his own half");
        }

        double score = PASS_BASE;
        reason.append("base ").append((int) PASS_BASE).append(' ');

        // Progress: how much closer to goal this leaves the team. Capped, and deliberately
        // modest — at the +25 the pass used to award, direction outweighed the man, so the ball
        // simply moved forward all the time: 79% of passes went forward and the carrier stood in
        // the box on 17.6% of all decisions, which is roughly thirty-five times a match. A team that
        // only ever passes forward is a team that camps in the opposition's third.
        double advance = progressOf(receiver) - progressOf(carrier);
        if (advance > 0) {
            double gain = Math.min(8.0, advance * 4.0);
            score += gain;
            reason.append(String.format("forward %+.0f ", gain));
        } else if (advance < -0.2) {
            // Short, safe backward pass: no penalty (owner's rule — "kratki pasovi
            // do 15 m do receivera koji je sam ... nema penal sto je unazad,
            // pogotovo na svojoj polovini"). 1 cell = 14 m, so 1.5 cells ≈ 21 m
            // covers the 15 m the owner names in both directions.
            double _dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
            boolean _short = _dist <= 1.5;
            boolean _ownHalf = "HOME".equals(carrier.getTeam())
                    ? carrier.getPosition().getRow() < 4.5
                    : carrier.getPosition().getRow() > 4.5;
            if (_short) {
                if (_ownHalf) {
                    reason.append("short safe backward (own half) ");
                } else {
                    score -= 5.0;
                    reason.append("short safe backward -5 ");
                }
            } else {
                score -= 20.0;
                reason.append("backward -20 ");
            }
        } else {
            score -= 3.0;
            reason.append("lateral -3 ");
        }

        // The receiver. The missing term, read through the carrier's playmaking.
        double threat = receiverThreat(state, receiver, carrier) * threadOf(carrier);
        score += threat * 30.0;
        reason.append(String.format("%s threat %.2f ", receiver.getLabel(), threat));

        // Room to receive it in.
        double space = spaceAround(state, receiver);
        score += space * 10.0;
        reason.append(String.format("space %.2f ", space));

        // Risk: the two ways this pass does not arrive.
        if (nearestOpponentBeatsHimToIt(state, receiver)) {
            score -= 40.0;
            reason.append("marker gets there first -40 ");
        }
        if (laneBlockPenalty(state, carrier, receiver) > 0) {
            return new DecisionOption(ActionType.PASS, receiver, UNAVAILABLE,
                    "PASS: lane blocked by defender");
        }

        // Distance. Modest now, and deliberately so — where the ball goes matters more than how far.
        double dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
        if (dist >= 0.8 && dist <= 1.5) {
            score += 6.0;
        } else if (dist > 2.5) {
            score -= 12.0;
            reason.append("-12 long ");
        }
        // Progressive distance penalty: long passes are riskier and less accurate.
        // A short safe pass to a nearby free player should beat a long wing pass.
        if (dist > 2.0) {
            double extra = (dist - 2.0) * 5.0;
            score -= extra;
            reason.append(String.format("-%.0f dist ", extra));
        }

        // OOB RISK (F-RS-3, replay 5633, 2026-10-26): if the pass target is near the touchline
        // (col < 1.5 or col > 6.5), the pass risks going out of bounds. Penalise heavily.
        double targetCol = receiver.getPosition().getColumn();
        if (targetCol < 1.5 || targetCol > 6.5) {
            score -= 20.0;
            reason.append("OOB risk -20 ");
        }

        // SAFE SHORT PASS BONUS (F-RS-3): a short, safe pass to a nearby teammate
        // (within 1.5 cells, no opponent within 1.0 cell) is the "safe pass to yourself"
        // — the ball stays in play and possession is maintained.
        if (dist <= 1.5 && dist >= 0.5) {
            double nearestOppD = Double.MAX_VALUE;
            for (Player opp : state.getPlayers()) {
                if (opp.getTeam().equals(carrier.getTeam())) continue;
                if (opp.isUnavailable()) continue;
                double d = SimUtils.distance(opp.getPosition(), receiver.getPosition());
                if (d < nearestOppD) nearestOppD = d;
            }
            if (nearestOppD > 1.0) {
                score += 15.0;
                reason.append("safe short +15 ");
            }
        }

        // DANGEROUS-ZONE SAFE PASS (owner 2026-10-30): in the own defensive third, a
        // safe pass to the nearest free teammate (within 2.0 cells, no opponent within
        // 1.5 cells) MUST win over a risky forward pass. This is the "siguran pas najblizem
        // slobodnom igrac" rule — defenders don't play risky passes when a safe option
        // exists right next to them.
        boolean home = "HOME".equals(carrier.getTeam());
        boolean inDangerousZone = home ? carrier.getPosition().getRow() <= 3.5
                : carrier.getPosition().getRow() >= 5.5;
        if (inDangerousZone && dist <= 2.5) {
            double nearestOppD = Double.MAX_VALUE;
            for (Player opp : state.getPlayers()) {
                if (opp.getTeam().equals(carrier.getTeam())) continue;
                if (opp.isUnavailable()) continue;
                double d = SimUtils.distance(opp.getPosition(), receiver.getPosition());
                if (d < nearestOppD) nearestOppD = d;
            }
            if (nearestOppD > 1.5) {
                // Progressive distance bonus: closer receiver gets MORE bonus.
                // 1.0 distance -> +45, 2.0 distance -> +15, so nearest free player wins.
                double zoneBonus = (2.5 - dist) * 30.0;
                score += zoneBonus;
                reason.append(String.format("safe zone +%.0f ", zoneBonus));
                System.out.println("DANGER-ZONE-BONUS: carrier=" + carrier.getLabel()
                        + " receiver=" + receiver.getLabel()
                        + " dist=" + String.format("%.2f", dist)
                        + " nearestOppD=" + String.format("%.2f", nearestOppD)
                        + " zoneBonus=" + String.format("%.0f", zoneBonus)
                        + " totalScore=" + String.format("%.2f", score));
            } else {
                System.out.println("NO-DANGER-ZONE-BONUS: carrier=" + carrier.getLabel()
                        + " receiver=" + receiver.getLabel()
                        + " dist=" + String.format("%.2f", dist)
                        + " nearestOppD=" + String.format("%.2f", nearestOppD)
                        + " (opponent too close)");
            }
        }

        // DANGEROUS-ZONE TOUCHLINE PENALTY: in the own defensive third, passes toward
        // the touchline (col < 2.0 or col > 6.0) are riskier (OOB risk) and should be
        // penalized. Central passes are safer.
        if (inDangerousZone) {
            double _targetCol = receiver.getPosition().getColumn();
            if (_targetCol < 2.0 || _targetCol > 6.0) {
                score -= 15.0;
                reason.append("touchline -15 ");
            }
        }

        // Execution: whether he can hit it, not whether he should.
        score += (carrier.getSkills().passing() - 10.0) * 0.8;
        score += (carrier.getSkills().playmaking() - 10.0) * 0.4;

        return new DecisionOption(ActionType.PASS, receiver, score, reason.toString());
    }

    /**
     * Every pass this carrier can actually play, each scored the way {@link #scorePassOptions}
     * scores the one he picks — so a debug view (the lab pitch's DECIDE box) can show the whole
     * candidate table, not just the winner.
     */
    public List<DecisionOption> passCandidateOptions(MatchState state, Player carrier) {
        List<DecisionOption> result = new ArrayList<>();
        for (Player teammate : state.getPlayers()) {
            if (!teammate.getTeam().equals(carrier.getTeam()) || teammate.equals(carrier)) continue;
            if (teammate.isUnavailable()) continue;
            if (inShootingZone(carrier) && inOwnHalf(teammate)) continue;
            double offsideMargin = offsideMargin(state, carrier, teammate);
            if (offsideMargin > OFFSIDE_HARD_LIMIT) continue;
            boolean marginalOffside = offsideMargin > 0;
            if (marginalOffside && !carrierWillRiskOffside(carrier)) continue;
            if (laneBlockPenalty(state, carrier, teammate) > 0) continue;   // blocked pass — not an option
            result.add(scorePassOptions(state, carrier, teammate));
        }
        return result;
    }
    /**
     * <p>A carry is worth the ground it wins and the room it has to win it in, less what the
     * defenders around him will take off him. The pressure term is the largest single number here,
     * which is correct: carrying into three men is how possession is lost.
     */
    private DecisionOption scoreCarryOptions(MatchState state, Player carrier) {
        // **Goalkeepers never dribble.** A keeper who carries the ball into the middle of the
        // pitch is not a football decision, it is a bug: he is the last man, and his job is to
        // distribute or clear. The dribble stays on the menu only for outfield players.
        if (carrier != null && carrier.isGoalkeeper()) {
            return new DecisionOption(ActionType.DRIBBLE, null, UNAVAILABLE,
                    "DRIBBLE: goalkeeper - only pass or clear");
        }
        StringBuilder reason = new StringBuilder("CARRY: ");
        boolean home = "HOME".equals(carrier.getTeam());
        double row = carrier.getPosition().getRow();
        double score = DRIBBLE_BASE;
        reason.append("base ").append((int) DRIBBLE_BASE).append(' ');

        // NO-DRIBBLE IN OWN 16M (F10, replay 5633, 2026-10-26).
        // The carrier must not dribble in the penalty area and the space around it.
        // HOME: rows 1.0–2.5 (bottom 2 cells, ≈28 m). AWAY: rows 5.5–8.0 (top 2 cells).
        // Dribble is always forbidden in these zones; only pass or clear is legal.
        boolean inOwn16m = home ? row <= 2.5 : row >= 5.5;
        if (inOwn16m) {
            return new DecisionOption(ActionType.DRIBBLE, null, UNAVAILABLE,
                    "DRIBBLE: in own 16 m — pass or clear only");
        }

        // Ground won. Three cells is the whole carry, so this is capped at what it can deliver.
        double gain = Math.min(32.0, 3.0 * 10.0);
        score += gain;
        reason.append(String.format("carry %+.0f ", gain));

        // Room to run into. isPathBlocked is the honest test — a lane with a defender in it is not
        // space, and the old version gave +12 for "clear path" against an unstated penalty.
        double forward = getForwardDirection(carrier.getTeam());
        Position target = new Position(row + forward * 3.0, carrier.getPosition().getColumn());
        if (isPathBlocked(state, carrier, target)) {
            score -= 20.0;
            reason.append("path blocked -20 ");
        } else {
            score += 14.0;
            reason.append("room ahead +14 ");
        }

        score += (carrier.getSkills().pace() - 10.0) * 0.8;
        score += (20.0 - carrier.getFatigue()) * 0.3;

        // The risk of losing it. Carrying is the default way to move the ball forward and it has
        // to stay competitive with passing, so the pressure term is what decides it — but it was
        // 35 against a pass that averaged 53, which made a carry average -1 and the engine stopped
        // carrying altogether (22.5% of decisions down to 0.8%).
        double pressure = calculatePressure(state, carrier);
        score -= pressure * 20.0;
        reason.append(String.format("press -%.0f ", pressure * 20.0));

        // RIGID CARRY GUARD. At the byline the carry target clamps back onto the carrier's own
        // position, so a DRIBBLE there makes NO progress and would re-win for ever — a standing-spot
        // loop. Judged on the CLAMPED target in the direction he attacks: the executor clamps HOME to
        // [1.0, 7.5] and AWAY to [1.5, 8.0], and the Movement Engine converges on the cap with
        // floating-point error, so a comparison against the raw row never fires.
        double carryMinRow = home ? 1.0 : 1.5;
        double carryMaxRow = home ? 7.5 : 8.0;
        double targetRow = SimUtils.clamp(row + (home ? 3.0 : -3.0), carryMinRow, carryMaxRow);
        // A carry that moves him less than a third of a cell is not a carry. The old threshold was
        // 0.05 — a tenth of what one tick of running covers — so it was looking for an exact tie
        // rather than for a stalled run, and it missed the case it was written for. Measured: 2.7% of
        // all dribbles moved the carrier less than 0.2 cells, and the reason strings showed carries
        // computed to row 10.9 on a pitch whose last row is 8.0.
        boolean bylineTrapped = (targetRow - row) * (home ? 1 : -1) < 0.3;
        if (bylineTrapped) {
            score = -60.0;
            reason.append("no forward space - the carry would not move him (row ")
                    .append(String.format("%.1f", row))
                    .append(" -> ").append(String.format("%.1f", targetRow)).append(") ");
        }

        reason.append(String.format("to (%.1f,%.1f)", targetRow,
                SimUtils.clamp(carrier.getPosition().getColumn(), 1.0, 7.0)));
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
        // One line judges both rules — may he shoot, and may he play backward — so the two can
        // never drift apart (ME-2).
        boolean inShootingZone = inShootingZone(carrier);
        Position goal = ActionEngine.goalPositionFor(team);
        double distToGoal = SimUtils.distance(carrier.getPosition(), goal);

        if (!inShootingZone) {
            return new DecisionOption(ActionType.SHOT, null, UNAVAILABLE,
                    "SHOT: not in zone");
        }

        // The frequency gate is GONE (ME-3, 2026-10-23 — owner: "SKLONI TO PRAVILO, igrač
        // treba da odluci u odnosu na to da li je pozicija za sut ili nije"). The shot
        // competes on its score like every other option; the position is the whole judgement —
        // the zone, this chance quality, and the corner-openness veto below.
        double chance = shotChanceQuality(state, carrier, goal);

        // ...and the position is allowed to say NO. Below the floor this is not a shooting
        // position, so the shot does not exist (owner's follow-up ruling 2026-10-23: the volume is
        // governed by the position, never by a roll). Deterministic — the same position always
        // answers the same, wherever and whenever it happens.
        if (chance < SHOT_CHANCE_FLOOR) {
            return new DecisionOption(ActionType.SHOT, null, UNAVAILABLE,
                    String.format("SHOT: not a shooting position (chance %.2f)", chance));
        }

        // Basic shot value, on the common scale.
        score += SHOT_BASE;
        reason.append("in zone ");
        reason.append(String.format("chance %.2f ", chance));

        // The chance itself is most of it, weighted so that a striker with the ball in the
        // box wins the comparison he should win.
        score += chance * SHOT_CHANCE_VALUE;

        // Striker skill, and technique for placing it.
        score += (carrier.getSkills().striker() - 10.0) * 1.0;
        score += (carrier.getSkills().technique() - 10.0) * 0.5;
        reason.append("striker ");

        // Is a shot actually available? Measured toward the CORNERS, and without the keeper.
        //
        // It used to measure the line from the shooter to the CENTRE of the goal and veto below
        // 0.1 — any body within 0.2 cells (2.8 m) of that single line. Three faults, all of which
        // cost shots that should have been taken:
        //
        // 1. It asked "is the middle of the goal blocked", which is true in every crowded box, and
        //    then threw the shot away. Measured over 40 matches: 528 in-zone ticks vetoed this way —
        //    29.4% of every tick a carrier spent standing in the box.
        // 2. It counted the goalkeeper. A keeper on his line sits ~0.29 cells (4.1 m) from the
        //    shooter->centre line against a 0.2-cell threshold, so he vetoed the shot in 31.6% of
        //    those cases by doing nothing but standing where a keeper stands. Whether he saves it
        //    is {@code GoalkeeperEngine}'s job; counting him here counted the same save twice.
        // 3. It tested a line no finisher aims at. {@code ExecutionQuality} already spreads on-target
        //    shots across the whole mouth precisely so a corner can beat him — and that model was
        //    unreachable, because this veto ran first.
        //
        // So: two corners, the keeper excluded, and a veto only when BOTH are blocked.
        double cornerOpen = bestShotCornerOpenness(state, carrier, goal);
        if (cornerOpen < SHOT_MIN_OPENNESS) {
            return new DecisionOption(ActionType.SHOT, null, UNAVAILABLE,
                    "SHOT: both corners blocked (open " + String.format("%.2f", cornerOpen) + ")");
        }

        // Pressure hurts shooting, on the same scale as everything else — EXCEPT in the
        // central columns of the box, where an open goal must dominate (owner: "prazan gol,
        // covek nije sutirao... kad ce da sutne ako ne tad?"). There the shot gets a bonus
        // instead of a penalty, so it beats pass/through-ball.
        double pressure = calculatePressure(state, carrier);
        boolean inBox = inAttackingBox(carrier);
        double col = carrier.getPosition().getColumn();
        boolean central = col >= 2.5 && col <= 5.5;
        if (inBox && central) {
            score += 30.0;
            reason.append("central-box +30 ");
        } else {
            score -= pressure * 20.0;
            reason.append(String.format("press -%.0f ", pressure * 20.0));
        }

        reason.append(String.format("-> %.1f dist", distToGoal));
        return new DecisionOption(ActionType.SHOT, null, score, "SHOT: " + reason);
    }

    /**
     * PROXIMITY to the opponent's goal, in cells: 0 on the half-way line, +3.5
     * standing on the opponent's goal line, negative in your own half.
     * Mirrored exactly about 4.5 (HOME's goal is row 8.0, AWAY's is row 1.0).
     *
     * It used to return the DISTANCE to the goal line
     * ({@code home ? 8.0 - row : row - 1.0}) while the callers ADD it as a bonus
     * ({@code prox * 3.5} on passes, {@code prox * 2.5} on carries, and the same
     * term inside {@code findBestReceiver}), so a bigger number meant "further
     * from goal" and the engine paid out for it. A pass to a player at row 2.2
     * scored +20.3 while a pass to a striker at row 5.8 scored +7.7 — the safe
     * pass backwards won by 12.6 points, which is more than twice the 5.0-point
     * band the whole playmaking random operates in. That is why defenders kept
     * recycling the ball instead of finding the forwards ("nema pas unapred ka
     * napadacima"), and why carrying the ball in your own half scored well
     * (see the CARRY prox term).
     *
     * The javadoc already said "0 = standing on the goal line" while the code
     * returned 0 for a player ON the goal line and 6 for one in his own half —
     * the code was a correct distance used backwards as a proximity.
     */
    private static double goalProximity(boolean home, double row) {
        return home ? row - 4.5 : 4.5 - row;
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
            // ME-2: a through ball is a delivery too — from a shooting position it may never end
            // in the passer's own half.
            if (inShootingZone(carrier) && inOwnHalf(mate)) continue;
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

        // Whether a through ball is available is decided by PERCEPTION, not by a constant.
        //
        // It used to be a flat 0.032 roll, which is a number about how often a team plays them and
        // has nothing to say about this carrier in this situation — and once the options were on a
        // common scale it became the only thing standing between the engine and the pass, so
        // through balls fell to 0.2% of the opportunities. A flat rate is the wrong shape for this:
        // whether a player finds the pass behind the defence is a matter of whether he sees it, and
        // {@code perceive} already models exactly that, keyed to playmaking.
        double pm = carrier.getSkills().playmaking();

        double score = DELIVERY_BASE;
        // What it creates: the room behind the line, and how good the runner is for it. Both are the
        // point of a through ball — a pass into space is worth exactly the runner's ability to use it.
        double spaceBehind = Math.max(0.0, Math.min(1.5, spaceBehindValue(state, carrier, best, team)));
        score += Math.min(30.0, spaceBehind * 20.0);
        PlayerSkills runner = best.getSkills();
        double runnerQuality = Math.max(0.0, Math.min(1.0,
                (runner.pace() + runner.striker() + runner.technique()) / 60.0));
        score += runnerQuality * 25.0;
        score += (pm - 10.0) * 0.8;
        score -= calculatePressure(state, carrier) * 25.0;
        if (laneBlockPenalty(state, carrier, best) > 0) score -= 30.0;
        reason.append(String.format("pace %.0f behind-line %.2f %s threat %.2f -> %s",
                runner.pace(), spaceBehind, best.getLabel(),
                receiverThreat(state, best, carrier) * threadOf(carrier), best.getLabel()));
        return new DecisionOption(ActionType.THRU, best, score, reason.toString());
    }

    /** How much room there is behind the last defender for a runner, in cells. */
    private double spaceBehindValue(MatchState state, Player carrier, Player runner, String team) {
        double line = secondLastDefenderRow(state, team);
        return "HOME".equals(team) ? line - runner.getPosition().getRow()
                : runner.getPosition().getRow() - line;
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
        // CROSS is removed per owner: "mozemo i ukinuti cross ostaviti center koji moze da se
        // izvede gde god nije protivnicki igrac u bloku". CENTER alone handles deliveries.
        if (fromFlank) {
            return new DecisionOption(ActionType.CROSS, null, UNAVAILABLE, "CROSS: removed — use CENTER");
        }
        StringBuilder reason = new StringBuilder(type + ": ");
        String team = carrier.getTeam();
        double row = carrier.getPosition().getRow();
        boolean home = "HOME".equals(team);
        double col = carrier.getPosition().getColumn();
        boolean inFinalThird = home ? row >= 5.0 : row <= 4.0;
        if (!inFinalThird) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": not in final third");
        }
        // CENTER is not for players already in the box (penalty area). A corner position
        // (row >= 6.5 but col outside 2.0-5.0) is NOT in the box — center is allowed there.
        if (!fromFlank && home && row >= 6.5 && col >= 2.0 && col <= 5.0) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": already in the box");
        }
        if (!fromFlank && !home && row <= 2.5 && col >= 2.0 && col <= 5.0) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": already in the box");
        }
        double wide = Math.abs(carrier.getPosition().getColumn() - 4.0);
        // CENTER has no "crossing position" restriction (owner: "center koji moze da se
        // izvede gde god nije protivnicki igrac u bloku"). It is allowed from any width.
        if (fromFlank && wide < 1.5) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": not wide enough");
        }

        // Best attacker inside the box, and how many team-mates are in there.
        Player target = null;
        double bestAttack = -1.0;
        int inBox = 0;
        for (Player mate : state.getPlayers()) {
            if (!mate.getTeam().equals(team) || mate.equals(carrier)) continue;
            if (mate.isUnavailable()) continue;
            if (!isInBox(state, mate, team)) continue;
            inBox++;

            // OFFSIDE — the same rule an ordinary pass uses (owner rule 2026-09-25).
            //
            //  - beyond 0.5 cells (7 m) he is CLEARLY offside: never a target.
            //  - inside 0.5 cells the delivery may be played, and whether the crosser actually
            //    does it depends on his PLAYMAKING skill ("veci skill ce retko gadati offside").
            //
            // A cross had NO offside test at all: the target was picked purely on threat, so the
            // engine aimed at the best attacker in the box even when he was a metre beyond the
            // second-to-last defender. And because the rules layer only ever asked about
            // ActionType.PASS, the flag was never raised either — he was a normal reception and
            // he could shoot and score from it.
            double offsideMargin = offsideMargin(state, carrier, mate);
            if (offsideMargin > OFFSIDE_HARD_LIMIT) {
                continue;   // clearly offside (>7 m) — not a delivery target at all
            }
            if (offsideMargin > 0 && !carrierWillRiskOffside(carrier)) {
                continue;   // marginal offside — only a crosser who plays the risk
            }

            double attack = receiverThreat(state, mate, carrier);
            if (attack > bestAttack) {
                bestAttack = attack;
                target = mate;
            }
        }
        if (target == null) {
            String msg = (inBox == 0) ? (type + ": nobody in the box")
                    : (type + ": all in the box are offside");
            return new DecisionOption(type, null, UNAVAILABLE, msg);
        }
        if (inBox < 2) {
            return new DecisionOption(type, null, UNAVAILABLE, type + ": nobody in the box");
        }

        double pm = carrier.getSkills().playmaking();
        // No frequency gate. Whether this delivery exists for this carrier is {@code perceive}'s
        // job — a flat 0.45 roll was a number about how often teams cross, not about whether he sees
        // the man, and on a common scale it was the option's second veto for no football reason.

        // A delivery is worth what it finds: how many bodies he is crossing to, and how good the
        // best of them is. Both used to be scaled to nothing — the old ceiling here was 36 points
        // against a pass that reached 109, so the option could not win.
        double score = DELIVERY_BASE;
        score += Math.min(3, inBox) * 8.0;
        score += bestAttack * threadOf(carrier) * 30.0;
        score += (carrier.getSkills().technique() - 10.0) * 0.8;
        score += (pm - 10.0) * 0.8;
        score -= calculatePressure(state, carrier) * 20.0;
        score -= SimUtils.distance(carrier.getPosition(), target.getPosition()) * 2.0;
        reason.append(String.format("%d in box, %s (threat %.2f) width %.1f",
                inBox, target.getLabel(), bestAttack, wide));
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
        // Only clear when under pressure in defensive third. Mirrored about 4.5:
        // HOME defends rows 1.0-3.0 (2 cells from its own goal at 1.0), so AWAY
        // must defend rows 5.0-7.0. The bound used to be 5.0, i.e. a THREE-cell
        // band for AWAY, which let only AWAY clear from the middle third.
        String team = carrier.getTeam();
        double carrierRow = carrier.getPosition().getRow();
        boolean inDefensiveThird = "HOME".equals(team) ? carrierRow <= 3.0 : carrierRow >= 6.0;
        double score = 0.0;
        StringBuilder reason = new StringBuilder();

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

        // Frequency gate — a defender under pressure usually plays out of trouble,
        // and hoofing it is the exception, not the default (see the constant).
        if (!state.possessionRoll(CLEAR_GATE_KEY,
                () -> SimulationRandom.nextDouble() <= CLEAR_FREQUENCY_GATE)) {
            return new DecisionOption(ActionType.CLEAR, null, UNAVAILABLE,
                    "CLEAR: freq gate");
        }

        // A clearance is a last resort, and it is worth less the longer the team holds the ball.
        //
        // It used to score 10 + up to 15 for being under pressure + up to 3 for defending skill —
        // a ceiling of 28 — and it was on the table on 7.4% of all decisions, which is far too
        // often for the action it is. Its value is deliberately below a good pass's, so it is chosen
        // when nothing else is available rather than when it is merely cheap.
        double pressure = calculatePressure(state, carrier);
        score = 18.0;
        reason.append("base 18 ");
        if (pressure > 0.5) {
            score += 14.0;
            reason.append("under pressure +14 ");
        }
        score += (carrier.getSkills().defender() - 10.0) * 0.8;
        // Hoofing it is only safe if there is somewhere to hoof it to.
        score -= Math.min(18.0, SimUtils.distance(carrier.getPosition(),
                new Position("HOME".equals(carrier.getTeam()) ? 7.0 : 2.0, carrier.getPosition().getColumn())) * 6.0);
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
            // ME-1 (owner 2026-10-23): never pass to a player who has left the pitch — sent off,
            // injured off, or already substituted. Every other pool in the engine (restarts,
            // loose balls, movement, offside) already skips isUnavailable(); this one didn't, and
            // the sent-off player — standing still in space, with nobody marking him — was the
            // most open man on the pitch, so he kept receiving the ball.
            if (teammate.isUnavailable()) continue;

            // ME-2 (owner 2026-10-23): from a shooting position a pass back into his own half is
            // not an option at all — removed, not penalised. ("iz pozicije za sut odigrava PAS NA
            // SVOJU POLOVINU, ovo smo bili zabranili!") From farther out nothing changes: the old
            // football stands, backward costs -20 and that is all.
            if (inShootingZone(carrier) && inOwnHalf(teammate)) continue;

            // FIRST PASS AFTER KICKOFF (owner 2026-10-30): the kickoff receiver's first pass
            // must be same line or backward — never forward, regardless of tactic. This is the
            // build-up rule: don't launch the ball forward from the first touch.
            if (state.isFirstPassAfterKickoff()
                    && progressOf(teammate) > progressOf(carrier)) {
                if (carrier.getLabel().contains("Kačar") || carrier.getLabel().contains("Kacar")) {
                    System.out.println("FILTERED-FIRSTPASS: " + carrier.getLabel() + " -> " + teammate.getLabel()
                            + " progressTeammate=" + String.format("%.2f", progressOf(teammate))
                            + " progressCarrier=" + String.format("%.2f", progressOf(carrier)));
                }
                continue;
            }

            // Debug: log all candidates for Kačar
            if (carrier.getLabel().contains("Kačar") || carrier.getLabel().contains("Kacar")) {
                System.out.println("CANDIDATE: " + carrier.getLabel() + " -> " + teammate.getLabel()
                        + " role=" + teammate.getRole()
                        + " pos=" + teammate.getPosition()
                        + " unavailable=" + teammate.isUnavailable()
                        + " firstPass=" + state.isFirstPassAfterKickoff());
            }

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

            // F-RS-1 (replay 5633, 2026-10-26): throw-in taker must NEVER pass to themselves or into empty space.
        // They may only pass to a free player within 2-2.5 cells, or throw into the box/center for a duel.
        if (state.isJustTookRestart()) {
            double distToTeammate = SimUtils.distance(carrier.getPosition(), teammate.getPosition());
            if (distToTeammate < 1.0) continue; // too close — essentially self or empty space (min 1 cell = 14 m)
            if (distToTeammate > 2.5) {
                // only allow passes into the box/center area for a duel
                double teammateRow = teammate.getPosition().getRow();
                double teammateCol = teammate.getPosition().getColumn();
                boolean inBox = (carrier.getTeam().equals("HOME")
                        ? teammateRow >= 5.0 && teammateRow <= 8.0 && teammateCol >= 2.0 && teammateCol <= 6.0
                        : teammateRow >= 1.0 && teammateRow <= 4.0 && teammateCol >= 2.0 && teammateCol <= 6.0);
                if (!inBox) continue; // not in box/center — don't allow long throw to nowhere
            }
        }

            // F10 (backward-pass penalty after no progression).
            // If we've had no forward progress for > F10_BACKWARD_PENALTY_TICKS, then
            // backward passes (advance < -0.2) are penalised an extra -20 so the engine
            // varies its targets rather than repeatedly playing the ball backward.
            int ticksSinceLastProgress = state.getMatchTicks() - state.getLastProgressTick();
            boolean noProgressMode = ticksSinceLastProgress > F10_BACKWARD_PENALTY_TICKS;

            // F9 (pass-repetition penalty): if the same passer→receiver pair was used on the
            // immediately preceding pass, penalise this candidate so the engine varies its
            // targets over time rather than aiming at the same two men repeatedly.
            double score = 0.0;
            double advance = progressOf(teammate) - progressOf(carrier);
            boolean samePair = carrier.getId().equals(state.getLastPassPasserId())
                    && teammate.getId().equals(state.getLastPassReceiverId());
            if (samePair) {
                score -= 20.0;   // repetition penalty — same passer and same receiver
            }
            if (advance > 0) {
                // record the tick when we last made forward progress
                state.setLastProgressTick(state.getMatchTicks());
                score += Math.min(8.0, advance * 4.0);
            } else if (advance < -0.2) {
                // backward pass — determine base penalty per original rules
                double _dist = SimUtils.distance(carrier.getPosition(), teammate.getPosition());
                boolean _short = _dist <= 1.5;
                boolean _ownHalf = "HOME".equals(carrier.getTeam())
                        ? carrier.getPosition().getRow() < 4.5
                        : carrier.getPosition().getRow() > 4.5;
                double basePenalty;
                if (_short) {
                    if (!_ownHalf) basePenalty = 5.0;   // reduced penalty outside own half
                    else basePenalty = 0.0;            // in own half: no penalty (owner's rule)
                } else {
                    basePenalty = 20.0;
                }
                if (noProgressMode) {
                    basePenalty += 20.0;   // extra penalty for backward pass after no progress
                }
                if (_short) {
                    if (!_ownHalf) score -= basePenalty;   // reduced penalty outside own half
                    else score -= basePenalty;              // in own half: no penalty but with F10 extra
                } else {
                    score -= basePenalty;
                }
            } else {
                score -= 3.0;
            }

            // STYLE BOOSTS (owner, 2026-10-30): the tactic style affects pass selection.
            // The carrier's team determines which style to use.
            String style = state.getTacticStyle(carrier.getTeam());
            if (style != null) {
                style = style.toUpperCase(java.util.Locale.ROOT);
                double distToTeammate = SimUtils.distance(carrier.getPosition(), teammate.getPosition());
                if ("ATTACKING".equals(style)) {
                    // +20% boost to teammates closer to opponent's goal
                    if (advance > 0) {
                        score *= 1.20;
                    }
                } else if ("DEFENSIVE".equals(style)) {
                    // +10% boost to backward passes
                    if (advance < -0.2) {
                        score *= 1.10;
                    }
                } else if ("POSSESSION".equals(style)) {
                    // +10% to nearby passer (within 1.5 cells) and +10% to free player
                    if (distToTeammate <= 1.5) {
                        score *= 1.10;
                    }
                    // free player: no opponent within 1.0 cell
                    double nearestOppD = Double.MAX_VALUE;
                    for (Player opp : state.getPlayers()) {
                        if (opp.getTeam().equals(carrier.getTeam())) continue;
                        if (opp.isUnavailable()) continue;
                        double d = SimUtils.distance(opp.getPosition(), teammate.getPosition());
                        if (d < nearestOppD) nearestOppD = d;
                    }
                    if (nearestOppD > 1.0) {
                        score *= 1.10;
                    }
                } else if ("WINGER".equals(style)) {
                    // +25% to players in extreme columns 1 and 6 if free
                    double col = teammate.getPosition().getColumn();
                    if (col <= 1.5 || col >= 5.5) {
                        double nearestOppD = Double.MAX_VALUE;
                        for (Player opp : state.getPlayers()) {
                            if (opp.getTeam().equals(carrier.getTeam())) continue;
                            if (opp.isUnavailable()) continue;
                            double d = SimUtils.distance(opp.getPosition(), teammate.getPosition());
                            if (d < nearestOppD) nearestOppD = d;
                        }
                        if (nearestOppD > 1.0) {
                            score *= 1.25;
                        }
                    }
                }
                // BALANCED: no change
            }

            // THE receiver term, weighted by how well this carrier reads a defence.
            score += receiverThreat(state, teammate, carrier) * threadOf(carrier) * 30.0;

            score += spaceAround(state, teammate) * 15.0;

            if (nearestOpponentBeatsHimToIt(state, teammate)) score -= 40.0;
            if (laneBlockPenalty(state, carrier, teammate) > 0) {
                continue;   // blocked pass — not an option (owner: "jasno u bloku")
            }

            double dist = SimUtils.distance(carrier.getPosition(), teammate.getPosition());
            if (dist > 2.5) score -= 12.0;
            else if (dist < 0.7) score -= 6.0;   // too close to be progressive

            // DANGEROUS-ZONE SAFE PASS (owner 2026-10-30): in the own defensive third, a
            // safe pass to the nearest free teammate (within 2.5 cells, no opponent within
            // 1.5 cells) gets a progressive distance bonus so the nearest free player wins.
            // This is applied in findBestReceiver so the receiver selection prefers safe
            // nearby passes over risky distant ones.
            boolean _home = "HOME".equals(carrier.getTeam());
            boolean _inDangerousZone = _home ? carrier.getPosition().getRow() <= 3.5
                    : carrier.getPosition().getRow() >= 5.5;
            if (_inDangerousZone && dist <= 2.5) {
                double _nearestOppD = Double.MAX_VALUE;
                for (Player opp : state.getPlayers()) {
                    if (opp.getTeam().equals(carrier.getTeam())) continue;
                    if (opp.isUnavailable()) continue;
                    double d = SimUtils.distance(opp.getPosition(), teammate.getPosition());
                    if (d < _nearestOppD) _nearestOppD = d;
                }
                if (_nearestOppD > 1.5) {
                    double zoneBonus = (2.5 - dist) * 30.0;
                    score += zoneBonus;
                }
            }

            // Marginal offside: still an option, but only if he plays the risk.
            if (marginalOffside && !carrierWillRiskOffside(carrier)) {
                continue;
            }
            // Blocked pass (defender on the lane) is not an option at all.
            if (laneBlockPenalty(state, carrier, teammate) > 0) {
                continue;
            }
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
    public static double offsideMargin(MatchState state, Player carrier, Player receiver) {
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
                double d = SimUtils.pointSegmentDistance(a, b, opponent.getPosition());
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
            if (candidate.isGoalkeeper()) continue;
            // ME-1: a player who is off the pitch (sent off, injured, subbed) is not a target.
            if (candidate.isUnavailable()) continue;
            // OWNER RULE (2026-10-30): the first pass after kickoff must be in the same line
            // or backward — never forward. A forward pass from the center spot risks offside
            // and is not how real football restarts work. Compare against the centre line
            // (progress 0), not against the carrier, so a taker who claims the ball slightly
            // behind centre does not filter out every same-line receiver.
            if (progressOf(candidate) > 0) continue; // skip receivers forward of the centre line
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
                        && !candidate.equals(carrier) && !candidate.isGoalkeeper()
                        && !candidate.isUnavailable()) {
                    best = candidate;
                    break;
                }
            }
        }
        if (best == null) return null;
        return new DecisionOption(ActionType.PASS, best, bestScore,
                "kickoff pass to " + best.getRole());
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

    /**
     * How good this chance is, 0..1, from where he is and where the goal is open to him.
     *
     * <p>Sixty per cent distance, forty per cent corner openness. Distance carries the larger share
     * because it is the one a striker feels: the same ball in the same corner is a good chance from
     * six yards and a hopeful one from thirty.
     *
     * <p>Used for two things that have to agree — whether the frequency gate applies at all, and how
     * much the shot is worth — so it is computed once here rather than twice with two answers.
     */
    // =========================================================================
    // THE SHARED VOCABULARY — every option is scored in these terms
    // =========================================================================

    /**
     * ME-2 (owner 2026-10-23) — "a shooting position" is the SHOT's own zone, so that one line
     * judges both rules: whether he may shoot, and whether a pass back into his own half is a
     * legal option at all. In or just in front of the box: HOME rows 6.0–8.0, mirrored for AWAY.
     */
    private static boolean inShootingZone(Player p) {
        double row = p.getPosition().getRow();
        double col = p.getPosition().getColumn();
        boolean home = "HOME".equals(p.getTeam());
        boolean inBand = home ? row >= 6.0 : row <= 3.0;
        // Corner ban: a shot from the byline and wide (right/left corner area)
        // is geometrically impossible — the angle to the goal mouth is too
        // narrow. The owner: "sut iz de facto kornera NE SME biti opcija".
        boolean inCorner = home
                ? (row > 7.0 && (col < 2.0 || col > 6.0))
                : (row < 2.0 && (col < 2.0 || col > 6.0));
        return inBand && !inCorner;
    }

    /**
     * ME-2: a receiver standing BEHIND the halfway line is in the passer's own half — the line
     * itself (4.5) is neutral, so exactly on it is still allowed. The owner's rule: from a
     * shooting position, "PAS NA SVOJU POLOVINU" must not exist.
     */
    private static boolean inOwnHalf(Player p) {
        double row = p.getPosition().getRow();
        return "HOME".equals(p.getTeam()) ? row < 4.5 : row > 4.5;
    }

    /** 0..1, how close a player is to the goal his side attacks. */
    private static double progressOf(Player p) {
        double row = p.getPosition().getRow();
        return "HOME".equals(p.getTeam()) ? row - 4.5 : 4.5 - row;
    }

    /** 0..1, how much room a receiver has, from the nearest marker. */
    private double spaceAround(MatchState state, Player p) {
        return Math.min(1.0, calculateOpenness(state, p) * 1.4);
    }

    /** 0..1, whether a player stands in the box his side attacks. */
    private static boolean inAttackingBox(Player p) {
        return "HOME".equals(p.getTeam())
                ? p.getPosition().getRow() >= 6.5
                : p.getPosition().getRow() <= 2.5;
    }

    /**
     * How dangerous this player is to give the ball to, 0..1.
     *
     * <p><b>This is the term the engine did not have.</b> {@code scorePassOptions} and
     * {@code findBestReceiver} both scored a pass on geometry alone — direction, distance, openness,
     * whether the lane was blocked — and never once looked at <em>who</em> was on the end of it. So a
     * square ball to a full-back standing on the same row as a free striker inside the box scored
     * exactly the same, and the free striker was not found. Measured over 40 matches: on 327 ticks
     * where a striker stood level with a team-mate on the same row, the pass reached the striker 31
     * times.
     *
     * <p>Three things make a man dangerous, in the order a striker would list them: can he finish;
     * is he in the box, where a pass is a shot being started; and is he goal-side of the ball, which
     * on a two-dimensional pitch is what "facing goal" means, because a man behind the ball cannot
     * run onto a pass played past him.
     */
    private double receiverThreat(MatchState state, Player mate, Player carrier) {
        if (mate == null || carrier == null) return 0.0;
        PlayerSkills s = mate.getSkills();
        double finishing = Math.max(0.0, Math.min(1.0, (s.striker() + s.technique()) / 40.0));
        double inBox = inAttackingBox(mate) ? 1.0 : 0.0;
        double goalSide = progressOf(mate) > progressOf(carrier) + 0.2 ? 1.0 : 0.0;
        return Math.max(0.0, Math.min(1.0, finishing * 0.45 + inBox * 0.30 + goalSide * 0.25));
    }

    /**
     * How much of a receiver's danger this carrier actually sees, 0.45 to 1.35 by playmaking.
     *
     * <p>A poor passer does not fail to spot the striker; he never looks. Applied as a multiplier on
     * {@link #receiverThreat} and not as a separate bonus, because it is not a second opinion — it
     * is how sharp the first one was.
     */
    private static double threadOf(Player carrier) {
        double pm = Math.max(1.0, Math.min(20.0, carrier.getSkills().playmaking()));
        return THREAD_MIN + (pm - 1.0) / 19.0 * (THREAD_MAX - THREAD_MIN);
    }

    /** How reliably this carrier takes the best option when two are close. 0.55 at skill 1. */
    private static double judgementOf(Player carrier) {
        double pm = Math.max(1.0, Math.min(20.0, carrier.getSkills().playmaking()));
        return ACCURACY_AT_SKILL_1
                + (pm - 1.0) / 19.0 * (ACCURACY_AT_SKILL_20 - ACCURACY_AT_SKILL_1);
    }

    private double shotChanceQuality(MatchState state, Player shooter, Position goal) {
        double distance = SimUtils.distance(shooter.getPosition(), goal);
        double proximity = Math.max(0.0, Math.min(1.0, 1.0 - distance / CHANCE_DISTANCE_RANGE));
        double openness = bestShotCornerOpenness(state, shooter, goal);
        return Math.max(0.0, Math.min(1.0, proximity * 0.6 + openness * 0.4));
    }

    /**
     * How open the better of the two top corners is, ignoring the goalkeeper.
     *
     * <p>This is the shot-BLOCKING test, and blocking is about bodies. A keeper is not a body in the
     * way — he is the last line of defence, and {@code GoalkeeperEngine} already models whether he
     * reaches it, how wide his reach is, and how a fast ball shrinks it. Counting him here counted
     * that save twice, and because he stands on the bisector he sat inside the old veto threshold
     * in 31.6% of the cases it fired.
     *
     * <p>Two corners rather than the centre, because the centre is where the keeper is and where the
     * defensive line stands. A shot is available when a finisher could put it where the bodies are
     * not, and "where" is a corner.
     *
     * @return openness 0..1 of whichever corner is clearer
     */
    private double bestShotCornerOpenness(MatchState state, Player shooter, Position goal) {
        Position from = shooter.getPosition();
        double left = opennessToward(state, shooter, from,
                new Position(goal.getRow(), GoalPhysical.MOUTH_LEFT + SHOT_CORNER_INSET));
        double right = opennessToward(state, shooter, from,
                new Position(goal.getRow(), GoalPhysical.MOUTH_RIGHT - SHOT_CORNER_INSET));
        return Math.max(left, right);
    }

    /** How clear the line from the shooter to one aim point is, counting outfield bodies only. */
    private double opennessToward(MatchState state, Player shooter, Position from, Position aim) {
        double minDist = Double.MAX_VALUE;
        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(shooter.getTeam()) || opponent.isSentOff()) continue;
            if (opponent.isGoalkeeper()) continue;
            minDist = Math.min(minDist, SimUtils.pointSegmentDistance(from, aim, opponent.getPosition()));
        }
        return Math.min(1.0, minDist / SHOT_OPENNESS_RADIUS);
    }

    /**
     * Is the lane from {@code from} to {@code to} contested?
     *
     * <p>Previously a literal {@code return false}, which made the caller's "+12 clear path"
     * carry bonus unconditional - a winger dribbling into a wall of defenders scored exactly the
     * same as one carrying into space. Now it measures the minimum distance from each opponent to
     * the carry lane segment and treats anything inside {@code #BLOCKED_LANE_CELLS} as blocked.
     * A cell is 14 m x 10 m, so 0.5 cells is roughly a 5-7 m corridor: tight enough to matter,
     * loose enough that a single nearby defender does not shut the lane entirely.
     */
    private boolean isPathBlocked(MatchState state, Player from, Position to) {
        if (from == null || to == null) return false;
        double laneWidth = Math.max(0.5, 0.02 + SimUtils.distance(from.getPosition(), to) * 0.12);
        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(from.getTeam()) || opponent.isUnavailable()) continue;
            if (SimUtils.pointSegmentDistance(from.getPosition(), to, opponent.getPosition()) < laneWidth) {
                return true;
            }
        }
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

    /**
     * Veto an option the carrier cannot perceive, keeping its reason for the log.
     * {@code chance} is the probability that a carrier of this playmaking reads it
     * at all, rolled ONCE per possession.
     */
    private DecisionOption perceive(MatchState state, DecisionOption option, String rollKey,
                                    double chance, int pmIndex) {
        if (option == null || option.getScore() <= UNAVAILABLE) return option;
        boolean seen = state.possessionRoll(rollKey,
                () -> SimulationRandom.nextDouble() <= chance);
        if (seen) return option;
        return new DecisionOption(option.getType(), option.getTarget(), UNAVAILABLE,
                option.getType() + ": not perceived (playmaking " + pmIndex + ")");
    }

    private DecisionOption selectOptionWithPlaymaking(Player carrier,
            DecisionOption pass, DecisionOption carry, DecisionOption shot, DecisionOption clear,
            DecisionOption thru, DecisionOption cross, DecisionOption center) {

        DecisionOption[] options = {pass, carry, shot, clear, thru, cross, center};

        DecisionOption[] viable = Arrays.stream(options)
                .filter(o -> o.getScore() >= 0)
                .toArray(DecisionOption[]::new);

        if (viable.length == 0) {
            // Nothing is even neutral. Take the least bad and let him get on with it — this is the
            // case that used to produce a striker standing in the box with a -113 lateral pass as
            // his only legal action.
            Arrays.sort(options, (a, b) -> Double.compare(b.getScore(), a.getScore()));
            return options[0];
        }

        Arrays.sort(viable, (a, b) -> Double.compare(b.getScore(), a.getScore()));
        DecisionOption best = viable[0];
        if (viable.length < 2) {
            return best;
        }
        DecisionOption second = viable[1];

        if (best.getScore() - second.getScore() < CHOICE_BAND
                && SimulationRandom.nextDouble() > judgementOf(carrier)) {
            return second;
        }
        return best;
    }
}