package org.example.footballmanager.newLogic.sim.probe;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.ActionType;
import org.example.footballmanager.newLogic.sim.model.DecisionOption;
import org.example.footballmanager.newLogic.sim.model.DecisionResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.tactics.FormationSlotCatalog;
import org.example.footballmanager.newLogic.sim.tactics.TacticalPerspectiveTransformer;
import org.example.footballmanager.newLogic.sim.tactics.TacticsSlotDTO;
import org.example.footballmanager.newLogic.sim.util.SimUtils;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Forensic read of <em>how the players decide</em> (owner, 2026-10-18).
 *
 * <h2>Why a second diagnostic</h2>
 *
 * <p>{@code ProposalSeasonDiag} answers "how many goals". This answers "why did he pass there", with
 * the whole board in front of you: the ball, the carrier, all twenty-two players, every option the
 * decision engine scored with its own reason string, and which one won.
 *
 * <p>It also runs <b>flat skill-14 squads</b> rather than {@code SimTeamFactory}'s role profile,
 * because the question is what the engine decides, and a keeper at 17 against a striker at 16 decides
 * a good deal of the match before kick-off.
 *
 * <h2>The football it asks about</h2>
 *
 * <ol>
 *   <li><b>1-on-1 with the keeper</b> — a striker alone inside the box shoots. Always.</li>
 *   <li><b>The shot gate's timing</b> — is the roll made when he first touches the ball, or when he
 *       arrives? A gate cached per possession is right for "one look, one decision" and wrong for
 *       "he dribbled forty metres and the roll was spent before he got there".</li>
 *   <li><b>Through ball</b> — a striker level with the last defender is the single most valuable pass
 *       on a pitch. Is it found?</li>
 *   <li><b>The killer ball</b> — a winger who has beaten his man and has a free man in the box.</li>
 *   <li><b>Where the pass goes</b> — a pass to a striker and a pass to a full-back on the same row
 *       score identically unless something separates them.</li>
 *   <li><b>By role</b> — a defender's decisions must differ from a striker's.</li>
 *   <li><b>The tie-break</b> — the mechanism playmaking is supposed to act through.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   mvn -q -o -DskipTests exec:java \
 *     -Dexec.mainClass=org.example.footballmanager.newLogic.sim.probe.DecisionTraceDiag \
 *     -Dexec.args="[ticksPerMatch] [matches] [baseSeed] [dumpEveryNth]"
 * </pre>
 *
 * <p>300 ticks of 40 matches is fifteen match-minutes of football from forty different kick-offs,
 * which is a far better sample than one long match and costs seconds rather than minutes.
 */
public final class DecisionTraceDiag {

    /** Every player carries this, on all eight skills. 1-20; 14 is a very good footballer. */
    private static final double SKILL = 14.0;

    private DecisionTraceDiag() {
    }

    // --------------------------------------------------------------- counters

    private static final Map<String, Integer> chosenByType = new TreeMap<>();
    private static final Map<String, Integer> chosenByRole = new TreeMap<>();
    private static final Map<String, Integer> availableByType = new TreeMap<>();
    private static final Map<String, Integer> passTargetRole = new TreeMap<>();

    private static int decisions;
    private static int goals, shots;
    private static int dumpEvery, sinceDump;

    // 1-on-1
    private static int oneOnOneStrict, oneOnOneStrictShot;
    private static int oneOnOneLoose, oneOnOneLooseShot;
    private static int ooGateBlocked, ooNotInZone, ooJammed;

    // The gate's timing — the whole point of section 2 above.
    private static int inZoneTicks, inZoneGateSpent;
    private static int possessionStartedOutsideZone, possessionStartedInZone;
    private static int spentAfterRunningIn, spentAlreadyBurned;
    private static int inZoneAndAvailable, inZoneAndAvailableAndShot;

    // Opportunities and their outcomes
    private static int thruOn, thruPlayed, thruGate, thruBlind;
    private static int wingerTicks, wingerFound, wingerIgnored;
    private static int wingerHigh, wingerHighWide, wingerHighMateInBox;
    private static int strikerBesideDefender, passWentToStriker;
    private static int passForward, passLateral, passBack;
    private static int tieBand, tieTookSecond, biggestGap, smallestGap = Integer.MAX_VALUE;

    // The two findings the raw dumps turned up, counted rather than argued.
    private static int dribbles, dribbledAwayFromGoal;   // ran towards his own goal
    private static int dribbleHeadedForOwnGoal;         // the carry target clamped to his own line
    private static int dribbleWentNowhere;              // moved him less than a fifth of a cell

    // Which hard rules are still doing work, and whether the outcome they forced was better.
    // A rule that fires rarely is not earning its keep; one that fires often and overrides a higher
    // score is overriding the decision engine it was written to protect.
    private static int naturalBestChosen, overriddenByARule;
    private static int defThirdDribbleVetoed, gateBlockedDribble, finalTwoRowForced;
    private static int forcedToShotWhenWorse, forcedToDeliveryWhenWorse;
    private static int nothingPositive;                 // every option vetoed or negative
    private static int nothingPositiveInZone;
    private static int laneJammedInZone;
    private static int gkHeldBall, gkBestWasPass, gkBestWasClear;

    // The scale question. Each option is scored on its own arithmetic, and nothing normalises them,
    // so the question is not "which is best" but "which arithmetic has the biggest numbers".
    private static int deliveriesOn, passWonOverDelivery, deliveryWonOverPass;
    private static int passOnButDeliveryBetter;          // a delivery outscored the chosen pass
    private static double maxPass, maxShot, maxThru, maxDelivery, maxDribble, maxClear;
    private static double sumPass, sumShot, sumThru, sumDelivery, sumDribble, sumClear;
    private static int nPass, nShot, nThru, nDelivery, nDribble, nClear;

    // The "lane jammed" veto, decomposed. calculateGoalOpenness measures how close the NEAREST body is
    // to the line from the shooter to the centre of the goal, and vetoes below 0.1 — i.e. anything
    // within 0.2 cells. A keeper standing on his line is inside that by construction, so the question
    // is whether the veto is ever about the outfielders at all.
    private static int laneVetoes, laneVetoKeeperWasClosest, laneVetoWouldPassWithoutKeeper;
    private static double laneOpennessWithKeeper, laneOpennessWithoutKeeper;
    private static double laneKeeperDistance;

    /** Per-possession memory: where the carrier was when the ball first arrived at his feet. */
    private static String trackedCarrier;
    private static boolean thisPossessionStartedInZone;

    public static void main(String[] args) {
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        int matches = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        long baseSeed = args.length > 2 ? Long.parseLong(args[2]) : 42L;
        dumpEvery = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        System.out.printf("=== DECISION TRACE · %d matches x %d ticks (%d match-minutes each) · seed %d · "
                        + "every player skill %d ===%n%n",
                matches, ticks, ticks / 40, baseSeed, (int) SKILL);

        var loaded = RealTacticsHarness.loadWorldDefault();
        System.out.printf("tactic '%s' (%s) from %s - %d rules - club '%s'%n",
                loaded.formation(), loaded.style(), loaded.source(), loaded.ruleCount(), loaded.teamName());
        if (!loaded.source().startsWith("the WORLD DEFAULT")) {
            System.out.println("!! NOT a designated world default - these numbers do not describe "
                    + "the tactic the owner chose.");
        }

        for (int m = 0; m < matches; m++) {
            SimulationRandom.seed(baseSeed + m);
            MatchState state = new MatchState();
            RealTacticsHarness.addTeam(state, "HOME", loaded.formation());
            RealTacticsHarness.addTeam(state, "AWAY", loaded.formation());
            MatchOrchestrator o = RealTacticsHarness.match(state, loaded);
            o.getRestartManager().handleKickoff(state, "HOME");
            sinceDump = 0;
            o.setDecisionObserver(DecisionTraceDiag::observe);
            o.simulate(ticks);
            goals += state.getHomeGoals() + state.getAwayGoals();
            shots += o.getStats().buildTeamStats("HOME").shots()
                    + o.getStats().buildTeamStats("AWAY").shots();
        }

        report(matches, ticks);
    }

    /**
     * One side, every player identical.
     *
     * <p>Built here rather than through {@code SimTeamFactory}, which hands out a <em>role profile</em>
     * — a keeper at 17, a striker's finishing at 16 — so two of its squads decide part of the match
     * before kick-off. Positions come from the real catalog and the real mirror, so the shape is the
     * one the game plays.
     */
    private static void flatTeam(MatchState state, String team) {
        PlayerSkills skills = new PlayerSkills(SKILL, SKILL, SKILL, SKILL, SKILL, SKILL, SKILL, SKILL);
        for (TacticsSlotDTO slot : new FormationSlotCatalog().getSlots("4-4-2")) {
            String role = slot.getSlotKey();
            String[] p = slot.getAnchorCellKey().split("_");
            Position homeView = new Position(Double.parseDouble(p[1]) + 1.5,
                    Double.parseDouble(p[2]) + 1.5);
            Position start = TacticalPerspectiveTransformer.toPhysical(homeView, team);
            state.getPlayers().add(new Player(
                    team + "-" + role, role, team, role, start, start, skills, 180));
        }
    }

    // --------------------------------------------------------------- observer

    private static void observe(MatchState state, DecisionResult result, Player carrier) {
        decisions++;
        DecisionOption chosen = result.getChosen();
        bump(chosenByType, chosen.getType().name());
        bump(chosenByRole, carrier.roleLine());
        for (DecisionOption o : result.getOptions()) {
            if (o.getScore() > UNAVAILABLE) bump(availableByType, o.getType().name());
        }

        // A new carrier is a new possession. Remember where he was when the ball arrived, because
        // that is the question the shot gate's timing turns on.
        if (!carrier.getId().equals(trackedCarrier)) {
            trackedCarrier = carrier.getId();
            thisPossessionStartedInZone = inZone(carrier);
            if (thisPossessionStartedInZone) possessionStartedInZone++;
            else possessionStartedOutsideZone++;
        }

        analyse(state, result, carrier, chosen);
        auditHardRules(state, result, carrier, chosen);

        // "Every Nth", so it samples the match rather than printing its first N decisions — the opening
        // two minutes are kickoff and nobody is anywhere near the interesting decisions.
        if (dumpEvery > 0 && ++sinceDump >= dumpEvery) {
            sinceDump = 0;
            dumpOne(state, result, carrier);
        }
    }

    private static void analyse(MatchState state, DecisionResult result, Player carrier,
                                DecisionOption chosen) {
        DecisionOption shot = option(result, ActionType.SHOT);
        boolean gateSpent = reasonIs(shot, "freq gate");

        // ---- 1. one-on-one -------------------------------------------------------------
        // Strict: inside the shooting zone, within two and a half cells of the line, nobody within
        // a stride and a half. Loose: the final third, nobody within a stride and a half, and the
        // nearest man to him is the keeper — which is the situation in words.
        if (clearOfAll(state, carrier, 1.2) && withinCellsOfGoal(carrier, 2.5) && inZone(carrier)) {
            oneOnOneStrict++;
            if (chosen.getType() == ActionType.SHOT) oneOnOneStrictShot++;
            else if (gateSpent) ooGateBlocked++;
            else if (reasonIs(shot, "not in zone")) ooNotInZone++;
            else if (reasonIs(shot, "lane jammed")) ooJammed++;
        }
        if (towardsGoal(carrier) > 1.0 && clearOfAll(state, carrier, 1.5) && nearestIsKeeper(state, carrier)) {
            oneOnOneLoose++;
            if (chosen.getType() == ActionType.SHOT) oneOnOneLooseShot++;
        }

        // ---- 2. the gate's timing ------------------------------------------------------
        if (inZone(carrier)) {
            inZoneTicks++;
            if (gateSpent) {
                inZoneGateSpent++;
                // This is the measurement. He is standing in the box with the ball and cannot shoot.
                // Did he start the possession here, or did he run in with the roll already spent?
                if (thisPossessionStartedInZone) spentAlreadyBurned++;
                else spentAfterRunningIn++;
            } else if (shot != null && shot.getScore() > UNAVAILABLE) {
                inZoneAndAvailable++;
                if (chosen.getType() == ActionType.SHOT) inZoneAndAvailableAndShot++;
                shots++;
            }
        }

        // ---- 3. the through ball -------------------------------------------------------
        DecisionOption thru = option(result, ActionType.THRU);
        if (thru != null && !reasonIs(thru, "nobody behind the line")
                && !reasonIs(thru, "not in attacking half")) {
            thruOn++;
            if (chosen.getType() == ActionType.THRU) thruPlayed++;
            else if (reasonIs(thru, "freq gate")) thruGate++;
            else if (reasonIs(thru, "not perceived")) thruBlind++;
        }

        // ---- 4. the killer ball --------------------------------------------------------
        // Decomposed rather than conjunctive. A conjunction that reads zero is a fact about the
        // question as much as the answer, and three of the four clauses can be false at once.
        Player boxMate = freeManInBox(state, carrier);
        if (boxMate == null) boxMate = anyManInBox(state, carrier);
        boolean high = towardsGoal(carrier) > 1.0;
        boolean wide = Math.abs(carrier.getPosition().getColumn() - 4.0) > 1.2;
        boolean mateInBox = anyManInBox(state, carrier) != null;
        if (high) wingerHigh++;
        if (high && wide) wingerHighWide++;
        if (mateInBox) wingerHighMateInBox++;
        if (high && wide && mateInBox) {
            wingerTicks++;
            boolean found = chosen.getType() == ActionType.CROSS || chosen.getType() == ActionType.CENTER
                    || chosen.getType() == ActionType.THRU
                    || (chosen.getType() == ActionType.PASS && chosen.getTarget() != null
                        && chosen.getTarget().getId().equals(boxMate.getId()));
            if (found) wingerFound++; else wingerIgnored++;
        }

        // ---- 5. striker standing next to his own full-back ------------------------------
        // Same row, so the pass score has to separate them on something other than geometry.
        Player strikerLevelWithMate = strikerBesideTeamMate(state, carrier);
        if (strikerLevelWithMate != null) {
            strikerBesideDefender++;
            if (chosen.getType() == ActionType.PASS && chosen.getTarget() != null
                    && chosen.getTarget().getId().equals(strikerLevelWithMate.getId())) {
                passWentToStriker++;
            }
        }

        // ---- 6. where the ball goes ----------------------------------------------------
        if (chosen.getType() == ActionType.PASS && chosen.getTarget() != null) {
            bump(passTargetRole, chosen.getTarget().roleLine());
            double d = forwardProgress(carrier, chosen.getTarget());
            if (d > 0.2) passForward++;
            else if (d < -0.2) passBack++;
            else passLateral++;
        }

        // ---- 7. how close is "close"? --------------------------------------------------
        List<DecisionOption> viable = new ArrayList<>();
        for (DecisionOption o : result.getOptions()) if (o.getScore() >= 0) viable.add(o);
        if (viable.size() >= 2) {
            viable.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            int gap = (int) Math.round(viable.get(0).getScore() - viable.get(1).getScore());
            if (gap <= 5) tieBand++;
            if (chosen.getType() == viable.get(1).getType()) tieTookSecond++;
            biggestGap = Math.max(biggestGap, gap);
            smallestGap = Math.min(smallestGap, gap);
        }
        if (viable.isEmpty()) {
            nothingPositive++;
            if (inZone(carrier)) nothingPositiveInZone++;
        }

        // ---- 8. a dribble that runs towards his own goal --------------------------------
        // The carry target is three cells forward, clamped to the playable band. Three cells of
        // "forward" from inside the last two cells of the pitch lands him on his OWN goal line,
        // and the byline guard only fires within 0.05 cells of it — so a striker two cells out can
        // be told to run away from goal and that is scored as his best available option.
        if (chosen.getType() == ActionType.DRIBBLE) {
            dribbles++;
            boolean home = "HOME".equals(carrier.getTeam());
            double from = carrier.getPosition().getRow();
            double to = home ? SimUtils.clamp(from + 3.0, 1.0, 7.5)
                    : SimUtils.clamp(from - 3.0, 1.5, 8.0);
            if (home ? to <= from + 0.05 : to >= from - 0.05) dribbledAwayFromGoal++;
            // He was heading for the line he defends.
            double ownLine = home ? 1.0 : 8.0;
            if (Math.abs(to - ownLine) < 0.01) dribbleHeadedForOwnGoal++;
            // A "dribble" that moves him less than a fifth of a cell is a standing spot. The engine
            // guards this at 0.05 cells, and a player covers ~0.5 in a tick, so the guard misses
            // the case it was written for by an order of magnitude.
            if (Math.abs(to - from) < 0.2) dribbleWentNowhere++;
        }

        // ---- 9. the keeper with the ball at his feet ------------------------------------
        if (carrier.isGoalkeeper()) {
            gkHeldBall++;
            if (chosen.getType() == ActionType.PASS) gkBestWasPass++;
            if (chosen.getType() == ActionType.CLEAR) gkBestWasClear++;
        }

        if (inZone(carrier) && reasonIs(shot, "lane jammed")) laneJammedInZone++;

        // ---- 8b. what the "lane jammed" veto is actually about -------------------------
        if (inZone(carrier) && reasonIs(shot, "lane jammed")) {
            laneVetoes++;
            Position goal = "HOME".equals(carrier.getTeam()) ? new Position(8.0, 3.5) : new Position(1.0, 3.5);
            Position from = carrier.getPosition();
            double withKeeper = Double.MAX_VALUE;
            double withoutKeeper = Double.MAX_VALUE;
            double keeperDistance = Double.MAX_VALUE;
            for (Player o : state.getPlayers()) {
                if (o.getTeam().equals(carrier.getTeam()) || o.isSentOff()) continue;
                double d = pointToLineDistance(o.getPosition(), from, goal);
                withKeeper = Math.min(withKeeper, d);
                if (o.isGoalkeeper()) keeperDistance = d;
                else withoutKeeper = Math.min(withoutKeeper, d);
            }
            laneOpennessWithKeeper += Math.min(1.0, withKeeper / 2.0);
            laneOpennessWithoutKeeper += Math.min(1.0, withoutKeeper / 2.0);
            laneKeeperDistance += keeperDistance;
            if (keeperDistance <= withKeeper + 1e-9) laneVetoKeeperWasClosest++;
            if (withoutKeeper / 2.0 >= 0.1) laneVetoWouldPassWithoutKeeper++;
        }

        // ---- 10. the scales -------------------------------------------------------------
        // Every option is scored on its own arithmetic and nothing normalises them. Recording the
        // observed range of each is the honest way to show what the comparison is actually doing.
        DecisionOption pass = option(result, ActionType.PASS);
        DecisionOption dribble = option(result, ActionType.DRIBBLE);
        DecisionOption clear = option(result, ActionType.CLEAR);
        DecisionOption bestDeliveryOption = bestOf(result, ActionType.THRU, ActionType.CROSS, ActionType.CENTER);
        if (pass != null && pass.getScore() > UNAVAILABLE) { maxPass = Math.max(maxPass, pass.getScore()); sumPass += pass.getScore(); nPass++; }
        if (shot != null && shot.getScore() > UNAVAILABLE) { maxShot = Math.max(maxShot, shot.getScore()); sumShot += shot.getScore(); nShot++; }
        if (thru != null && thru.getScore() > UNAVAILABLE) { maxThru = Math.max(maxThru, thru.getScore()); sumThru += thru.getScore(); nThru++; }
        if (bestDeliveryOption != null && bestDeliveryOption.getScore() > UNAVAILABLE) {
            maxDelivery = Math.max(maxDelivery, bestDeliveryOption.getScore());
            sumDelivery += bestDeliveryOption.getScore(); nDelivery++;
        }
        if (dribble != null && dribble.getScore() > UNAVAILABLE) { maxDribble = Math.max(maxDribble, dribble.getScore()); sumDribble += dribble.getScore(); nDribble++; }
        if (clear != null && clear.getScore() > UNAVAILABLE) { maxClear = Math.max(maxClear, clear.getScore()); sumClear += clear.getScore(); nClear++; }

        // A delivery was genuinely on the table and a plain pass was taken instead. If the plain
        // pass is also FORWARD, then the engine preferred the generic option over the specific one.
        if (bestDeliveryOption != null && bestDeliveryOption.getScore() > UNAVAILABLE && chosen.getType() == ActionType.PASS) {
            deliveriesOn++;
            if (pass != null && bestDeliveryOption.getScore() > pass.getScore()) passOnButDeliveryBetter++;
            if (forwardProgress(carrier, chosen.getTarget()) > 0.2) passWonOverDelivery++;
        } else if (bestDeliveryOption != null && bestDeliveryOption.getScore() > UNAVAILABLE
                && chosen.getType() != ActionType.PASS) {
            deliveryWonOverPass++;
        }
    }

    private static DecisionOption bestOf(DecisionResult r, ActionType... types) {
        DecisionOption best = null;
        for (ActionType t : types) {
            DecisionOption o = option(r, t);
            if (o != null && o.getScore() > UNAVAILABLE && (best == null || o.getScore() > best.getScore())) {
                best = o;
            }
        }
        return best;
    }

    /**
     * Did a hard rule decide this, and was the thing it forced better or worse than the best score?
     *
     * <p>The rules in {@code CleanDecisionEngine} were written as attempts to force the decision
     * engine to behave — the owner's own word for them is "pokusaji da naterma decision engine". Now
     * that the options share a scale, the question each one has to answer is whether it still earns
     * its place: a rule that fires rarely costs nothing and a rule that overrides a higher score is
     * taking a decision away from the engine.
     */
    private static void auditHardRules(MatchState state, DecisionResult result, Player carrier,
                                       DecisionOption chosen) {
        List<DecisionOption> viable = new ArrayList<>();
        for (DecisionOption o : result.getOptions()) if (o.getScore() >= 0) viable.add(o);
        if (viable.isEmpty()) return;
        viable.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        DecisionOption best = viable.get(0);
        if (chosen.getType() == best.getType()) {
            naturalBestChosen++;
        } else {
            overriddenByARule++;
        }

        DecisionOption dribble = option(result, ActionType.DRIBBLE);
        if (dribble != null) {
            String why = dribble.getReason() == null ? "" : dribble.getReason();
            if (why.contains("own defensive third")) defThirdDribbleVetoed++;
            if (why.contains("shot gate blocked")) gateBlockedDribble++;
        }

        boolean home = "HOME".equals(carrier.getTeam());
        double row = carrier.getPosition().getRow();
        boolean finalTwoRows = home ? row >= 6.0 : row <= 3.0;
        if (finalTwoRows && (chosen.getType() == ActionType.SHOT
                || chosen.getType() == ActionType.THRU || chosen.getType() == ActionType.CROSS
                || chosen.getType() == ActionType.CENTER)) {
            boolean dribbleWasBest = dribble != null && dribble.getScore() > 0
                    && dribble.getScore() > opts_best(result, chosen);
            if (dribbleWasBest) {
                finalTwoRowForced++;
                if (chosen.getType() == ActionType.SHOT) forcedToShotWhenWorse++;
                else forcedToDeliveryWhenWorse++;
            }
        }
    }

    /** The best score among the options other than the one that was actually taken. */
    private static double opts_best(DecisionResult r, DecisionOption chosen) {
        double m = Double.NEGATIVE_INFINITY;
        for (DecisionOption o : r.getOptions()) {
            if (o == chosen) continue;
            m = Math.max(m, o.getScore());
        }
        return m == Double.NEGATIVE_INFINITY ? Double.NEGATIVE_INFINITY : m;
    }

    // ------------------------------------------------------------------ output

    private static void report(int matches, int ticks) {
        System.out.printf("Sample: %d goals and %d shots in %d matches of %d ticks -> %.1f goals, %.1f "
                        + "shots per match-equivalent.%n%n",
                goals, shots, matches, ticks,
                goals / (double) matches * (3600.0 / ticks),
                shots / (double) matches * (3600.0 / ticks));

        table("WHAT THE CARRIER CHOSE", chosenByType);
        table("WHAT WAS EVEN AVAILABLE", availableByType);
        table("WHAT EACH ROLE DID", chosenByRole);
        table("WHO THE PASS WENT TO", passTargetRole);

        System.out.println("1-ON-1 WITH THE KEEPER");
        p("strict: in the box, nobody within a stride", oneOnOneStrict, decisions);
        p("   and he shot", oneOnOneStrictShot, oneOnOneStrict);
        p("   blocked by the shot frequency gate", ooGateBlocked, oneOnOneStrict);
        p("   not in the shooting zone", ooNotInZone, oneOnOneStrict);
        p("   lane jammed", ooJammed, oneOnOneStrict);
        p("loose: final third, keeper nearest, nobody within a stride-and-a-half", oneOnOneLoose, decisions);
        p("   and he shot", oneOnOneLooseShot, oneOnOneLoose);
        System.out.println();

        System.out.println("THE SHOT GATE'S TIMING");
        p("ticks with the carrier in the shooting zone", inZoneTicks, decisions);
        p("   shot unavailable: the frequency roll was already spent", inZoneGateSpent, inZoneTicks);
        p("      ...because he started the possession OUTSIDE the zone and ran in", spentAfterRunningIn, inZoneGateSpent);
        p("      ...because the roll was spent before we ever looked", spentAlreadyBurned, inZoneGateSpent);
        p("ticks in the zone with the shot genuinely available", inZoneAndAvailable, inZoneTicks);
        p("   and he took it", inZoneAndAvailableAndShot, inZoneAndAvailable);
        System.out.println();

        System.out.println("THE THROUGH BALL");
        p("ticks where a team-mate was behind the last defender", thruOn, decisions);
        p("   played", thruPlayed, thruOn);
        p("   killed by the 0.032 frequency gate", thruGate, thruOn);
        p("   never perceived by this carrier's playmaking", thruBlind, thruOn);
        System.out.println();

        System.out.println("THE KILLER BALL (the four clauses, then all four at once)");
        p("carrier in the final third", wingerHigh, decisions);
        p("   ...and wide of the centre", wingerHighWide, wingerHigh);
        p("   ...and any team-mate in the box at all", wingerHighMateInBox, decisions);
        p("all three at once", wingerTicks, decisions);
        p("   found him (cross / centre / thru / passed to him)", wingerFound, wingerTicks);
        p("   did something else", wingerIgnored, wingerTicks);
        System.out.println();

        System.out.println("PASS TARGETING");
        p("ticks where a striker stood level with a team-mate on the same row", strikerBesideDefender, decisions);
        p("   and the pass went to the striker", passWentToStriker, strikerBesideDefender);
        p("passes forward", passForward, passForward + passLateral + passBack);
        p("passes lateral", passLateral, passForward + passLateral + passBack);
        p("passes backward", passBack, passForward + passLateral + passBack);
        System.out.println();

        System.out.println("THE SELECTOR");
        p("ticks where best and second were inside the 5.0 tie band", tieBand, decisions);
        p("   and the second option was taken", tieTookSecond, tieBand);
        System.out.printf("  gap between first and second: smallest %s, largest %d%n",
                smallestGap == Integer.MAX_VALUE ? "n/a" : smallestGap, biggestGap);
        System.out.println();

        System.out.println("NOTHING SENSIBLE ON THE TABLE");
        p("ticks where every option was vetoed or scored negative", nothingPositive, decisions);
        p("   ...and the carrier was in the shooting zone", nothingPositiveInZone, nothingPositive);
        p("in the zone, shot vetoed as 'lane jammed'", laneJammedInZone, inZoneTicks);
        System.out.println("  the 'lane jammed' veto, decomposed (the keeper is on the line by definition)");
        p("   vetoes examined", laneVetoes, Math.max(1, laneVetoes));
        p("   the KEEPER was the closest body to the shot line", laneVetoKeeperWasClosest, Math.max(1, laneVetoes));
        p("   the shot would have cleared the bar without him", laneVetoWouldPassWithoutKeeper, Math.max(1, laneVetoes));
        System.out.printf("  mean openness WITH the keeper on the line : %.3f  (veto threshold is 0.1)%n",
                laneVetoes == 0 ? 0 : laneOpennessWithKeeper / laneVetoes);
        System.out.printf("  mean openness WITHOUT the keeper        : %.3f%n",
                laneVetoes == 0 ? 0 : laneOpennessWithoutKeeper / laneVetoes);
        System.out.printf("  mean keeper distance to the shot line   : %.3f cells (%.1f m)%n",
                laneVetoes == 0 ? 0 : laneKeeperDistance / laneVetoes,
                laneVetoes == 0 ? 0 : laneKeeperDistance / laneVetoes * 14.0);
        System.out.println();

        System.out.println("DRIBBLING");
        p("dribbles chosen", dribbles, decisions);
        p("   ...that end at or behind where he started", dribbledAwayFromGoal, dribbles);
        p("   ...that end exactly on his OWN goal line", dribbleHeadedForOwnGoal, dribbles);
        p("   ...that move him less than a fifth of a cell", dribbleWentNowhere, dribbles);
        System.out.println();

        System.out.println("THE KEEPER");
        p("ticks the keeper had the ball at his feet", gkHeldBall, decisions);
        p("   his best option was a pass", gkBestWasPass, gkHeldBall);
        p("   his best option was a clearance", gkBestWasClear, gkHeldBall);
        System.out.println();

        System.out.println("THE SCALES — each option's arithmetic, nothing normalised between them");
        System.out.printf("  %-22s %6s %8s %8s%n", "option", "seen", "max", "mean");
        row("PASS", nPass, maxPass, sumPass);
        row("SHOT", nShot, maxShot, sumShot);
        row("THRU", nThru, maxThru, sumThru);
        row("CROSS/CENTER", nDelivery, maxDelivery, sumDelivery);
        row("DRIBBLE", nDribble, maxDribble, sumDribble);
        row("CLEAR", nClear, maxClear, sumClear);
        System.out.println();
        System.out.println("HARD RULES — do they still earn their place?");
        p("decisions where the highest-scoring option was taken", naturalBestChosen, decisions);
        p("decisions a hard rule overrode", overriddenByARule, decisions);
        p("   dribble vetoed in the own defensive third", defThirdDribbleVetoed, decisions);
        p("   dribble vetoed because the shot gate was spent", gateBlockedDribble, decisions);
        p("final-two-rows rule replaced a BETTER-scoring dribble", finalTwoRowForced, decisions);
        p("   forced into a shot that scored lower", forcedToShotWhenWorse, Math.max(1, finalTwoRowForced));
        p("   forced into a delivery that scored lower", forcedToDeliveryWhenWorse, Math.max(1, finalTwoRowForced));
        System.out.println();
        System.out.println("A DELIVERY WAS ON THE TABLE (thru / cross / centre all available)");
        p("   and a plain PASS was taken instead", deliveriesOn, decisions);
        p("      ...and that pass was forward, not sideways", passWonOverDelivery, deliveriesOn);
        p("      ...and the delivery had actually scored HIGHER", passOnButDeliveryBetter, deliveriesOn);
        p("   and a delivery was taken instead of a pass", deliveryWonOverPass, decisions);
    }

    private static void row(String name, int n, double max, double sum) {
        System.out.printf("  %-22s %6d %8.1f %8.1f%n", name, n, max, n == 0 ? 0 : sum / n);
    }

    private static void table(String title, Map<String, Integer> m) {
        System.out.println(title);
        m.forEach((k, v) -> System.out.printf("   %-8s %7d  %5.1f%%%n", k, v, 100.0 * v / Math.max(1, decisions)));
        System.out.println();
    }

    private static void p(String label, int n, int d) {
        System.out.printf("  %-62s %7d / %-7d %5.1f%%%n", label, n, d, d == 0 ? 0.0 : 100.0 * n / d);
    }

    /** One decision in full, with the board. This is the thing the owner asked to be able to read. */
    private static void dumpOne(MatchState state, DecisionResult result, Player carrier) {
        System.out.printf("--- %s  %s %s  ball (%.1f,%.1f)  carrier at (%.1f,%.1f)%n",
                clock(state.getMatchTicks()), carrier.getTeam(), carrier.getRole(),
                state.getBall().getPosition().getRow(), state.getBall().getPosition().getColumn(),
                carrier.getPosition().getRow(), carrier.getPosition().getColumn());
        for (DecisionOption o : result.getOptions()) {
            System.out.printf("   %-7s %s %7.1f  %s%n", o.getType().name(),
                    o == result.getChosen() ? "->" : "  ",
                    o.getScore() <= UNAVAILABLE ? 0 : o.getScore(), o.getReason());
        }
        pitch(state, carrier);
        System.out.println();
    }

    /** Rows 8 -> 1, HOME attacking up. */
    private static void pitch(MatchState state, Player carrier) {
        for (int r = 16; r >= 0; r -= 2) {
            StringBuilder sb = new StringBuilder("    ");
            for (int c = 0; c <= 28; c++) {
                Position probe = new Position(r * 0.5, 1.0 + c * 3.0 / 28.0);
                String g = ".";
                if (SimUtils.distance(state.getBall().getPosition(), probe) < 0.20) g = "o";
                for (Player p : state.getPlayers()) {
                    if (!p.isUnavailable() && SimUtils.distance(p.getPosition(), probe) < 0.24) {
                        g = p == carrier ? "C" : p.getTeam().equals(carrier.getTeam()) ? "H" : "A";
                        break;
                    }
                }
                sb.append(g);
            }
            System.out.printf("%s  %4.1f%n", sb, r * 0.5);
        }
        System.out.println("      HOME goal row 8.0, AWAY goal row 1.0 · C carrier · H own · A opponent");
    }

    // -------------------------------------------------------------------- utils

    private static final double UNAVAILABLE = -10_000.0;

    private static String clock(int ticks) {
        return String.format("%02d:%02d", ticks / 40, ticks % 40 * 60 / 40);
    }

    private static void bump(Map<String, Integer> m, String k) {
        m.merge(k, 1, Integer::sum);
    }

    private static DecisionOption option(DecisionResult r, ActionType t) {
        for (DecisionOption o : r.getOptions()) if (o.getType() == t) return o;
        return null;
    }

    private static boolean reasonIs(DecisionOption o, String fragment) {
        return o != null && o.getReason() != null && o.getReason().contains(fragment);
    }

    /**
     * Distance from a point to the segment a-b — the same arithmetic as
     * {@code CleanDecisionEngine.pointToLineDistance}, duplicated here on purpose so this
     * instrument measures the engine rather than agreeing with it by construction. If the two ever
     * disagree the number below stops describing the veto, which is the one thing this counter is for.
     */
    private static double pointToLineDistance(Position p, Position a, Position b) {
        double dx = b.getColumn() - a.getColumn();
        double dy = b.getRow() - a.getRow();
        double l2 = dx * dx + dy * dy;
        if (l2 < 1e-9) return SimUtils.distance(p, a);
        double t = ((p.getColumn() - a.getColumn()) * dx + (p.getRow() - a.getRow()) * dy) / l2;
        t = Math.max(0.0, Math.min(1.0, t));
        return SimUtils.distance(p, new Position(a.getRow() + t * dy, a.getColumn() + t * dx));
    }

    private static boolean inZone(Player p) {
        return "HOME".equals(p.getTeam()) ? p.getPosition().getRow() >= 6.0 : p.getPosition().getRow() <= 3.0;
    }

    /** Positive means the player is nearer the goal he is attacking than the halfway line. */
    private static double towardsGoal(Player carrier) {
        double row = carrier.getPosition().getRow();
        return "HOME".equals(carrier.getTeam()) ? row - 4.5 : 4.5 - row;
    }

    /** Signed forward progress from the carrier to another player. Positive = towards the goal he attacks. */
    private static double forwardProgress(Player carrier, Player other) {
        double from = carrier.getPosition().getRow();
        double to = other.getPosition().getRow();
        return "HOME".equals(carrier.getTeam()) ? to - from : from - to;
    }

    private static boolean withinCellsOfGoal(Player p, double cells) {
        double line = "HOME".equals(p.getTeam()) ? 8.0 : 1.0;
        return Math.abs(p.getPosition().getRow() - line) <= cells;
    }

    /** Nobody from the other side within {@code cells}. */
    private static boolean clearOfAll(MatchState state, Player carrier, double cells) {
        for (Player p : state.getPlayers()) {
            if (p == carrier || p.isUnavailable() || p.getTeam().equals(carrier.getTeam())) continue;
            if (SimUtils.distance(p.getPosition(), carrier.getPosition()) < cells) return false;
        }
        return true;
    }

    private static boolean nearestIsKeeper(MatchState state, Player carrier) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p == carrier || p.isUnavailable() || p.getTeam().equals(carrier.getTeam())) continue;
            double d = SimUtils.distance(p.getPosition(), carrier.getPosition());
            if (d < bestD) { bestD = d; best = p; }
        }
        return best != null && best.isGoalkeeper();
    }

    /** Any team-mate in the box the carrier attacks, marked or not. */
    private static Player anyManInBox(MatchState state, Player carrier) {
        boolean home = "HOME".equals(carrier.getTeam());
        for (Player p : state.getPlayers()) {
            if (p == carrier || p.isUnavailable() || !p.getTeam().equals(carrier.getTeam())) continue;
            double row = p.getPosition().getRow();
            if (home ? row >= 6.5 : row <= 2.5) return p;
        }
        return null;
    }

    /** A team-mate in the box, inside a stride of nobody. */
    private static Player freeManInBox(MatchState state, Player carrier) {
        boolean home = "HOME".equals(carrier.getTeam());
        Player best = null;
        for (Player p : state.getPlayers()) {
            if (p == carrier || p.isUnavailable() || !p.getTeam().equals(carrier.getTeam())) continue;
            double row = p.getPosition().getRow();
            if (home ? row < 6.5 : row > 2.5) continue;
            double nearest = Double.MAX_VALUE;
            for (Player o : state.getPlayers()) {
                if (o.getTeam().equals(carrier.getTeam()) || o.isUnavailable()) continue;
                nearest = Math.min(nearest, SimUtils.distance(o.getPosition(), p.getPosition()));
            }
            if (nearest > 1.0 && (best == null || p.isAttacker())) best = p;
        }
        return best;
    }

    /**
     * A team-mate in the final third standing within a fifth of a cell of somebody else on his own
     * side. The pair is not striker-and-defender specifically — what matters is that two options sit
     * at the same depth, and the pass score has to tell them apart on something other than geometry.
     */
    private static Player strikerBesideTeamMate(MatchState state, Player carrier) {
        for (Player p : state.getPlayers()) {
            if (p == carrier || p.isUnavailable() || !p.getTeam().equals(carrier.getTeam())) continue;
            if (!p.isAttacker() && !p.isDefender()) continue;
            if (Math.abs(p.getPosition().getRow() - carrier.getPosition().getRow()) > 2.0) continue;
            for (Player o : state.getPlayers()) {
                if (o == p || o == carrier || o.isUnavailable()) continue;
                if (!o.getTeam().equals(carrier.getTeam())) continue;
                if (o.isAttacker() == p.isAttacker()) continue;
                if (Math.abs(o.getPosition().getRow() - p.getPosition().getRow()) < 0.2) return p;
            }
        }
        return null;
    }
}