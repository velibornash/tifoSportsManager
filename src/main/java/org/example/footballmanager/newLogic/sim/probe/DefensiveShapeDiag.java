package org.example.footballmanager.newLogic.sim.probe;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.engine.ThreatOverrideEngine;
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
import java.util.TreeMap;
import java.util.Map;

/**
 * The other half of the decision engine: how the DEFENCE decides (owner, 2026-10-18).
 *
 * <h2>Why this exists separately</h2>
 *
 * <p>Owner: <i>"treba utvrditi zašto odbrana koja je pristojna dozvoljava da se prečesto šuteri nađu u
 * dobrim pozicijama — kako reaguje odbrana, da li prilaze igraču sa loptom, da li prilaze opasnom
 * protivniku, da li dobijaju duele."</i>
 *
 * <p>{@link DecisionTraceDiag} looks at the carrier. This looks at the eleven men who are trying to
 * take the ball off him, and it asks three separate questions, because they can all fail
 * independently and the fixes are different:
 *
 * <ol>
 *   <li><b>Do they close the man with the ball?</b> Not "is a press rule enabled" — what is the
 *       distance, how many come, and are they goal-side.</li>
 *   <li><b>Do they close the dangerous opponent without the ball?</b> A defence that only ever chases
 *       the ball leaves a free man in the box, which is the killer ball the attacking half spends
 *       half its time looking for.</li>
 *   <li><b>Do they win the duels they get to?</b> Closing down is worthless if the tackle loses.</li>
 * </ol>
 *
 * <h2>How it measures</h2>
 *
 * <p>Through {@link MatchOrchestrator.TickObserver}, which fires every tick after the Movement Engine
 * has run. That point is chosen deliberately: {@link MatchOrchestrator.DecisionObserver} only fires
 * when a carrier is on the ball and deciding, so it cannot see the ticks where a defence is walking up
 * to the ball, holding a line, or arriving late — and those are most of them. Measuring before
 * movement would measure intentions rather than outcomes.
 *
 * <p>It reads {@code isThreatOverrideActive} rather than re-deriving which of the three threat
 * rules fired, because the flag is what the engine actually did, and a diagnostic that guesses at its
 * own subject cannot refute it.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   mvn -q -o -DskipTests exec:java \
 *     -Dexec.mainClass=org.example.footballmanager.newLogic.sim.probe.DefensiveShapeDiag \
 *     -Dexec.args="[ticksPerMatch] [matches] [baseSeed]"
 * </pre>
 */
public final class DefensiveShapeDiag {

    private static final double SKILL = 14.0;

    /** Mirrors the selector: below this gap the carrier's judgement, not the score, decides. */
    private static final double CHOICE_BAND =
            org.example.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine.CHOICE_BAND;

    private static final double UNAVAILABLE = -10_000.0;

    private DefensiveShapeDiag() {
    }

    // --------------------------------------------------------------- counters

    /** Ticks on which somebody had the ball. The denominator for every claim about defending. */
    private static int ticksWithCarrier;

    /** Nearest defender to the carrier, in bands. Bands are the ranges that mean something: 0.5 is
     *  a tackle, 1.5 is the press radius, 2.5 is far. */
    private static final Map<String, Integer> nearestToCarrier = new TreeMap<>();
    private static final Map<String, Integer> presses = new TreeMap<>();

    /** Was the nearest defender goal-side of the carrier? Closing down means getting between the man
     *  and the goal he is attacking, not trailing him. */
    private static int goalSide, behindHim, abreast;

    /** TYPE A re-routes the closest eligible presser and everyone else holds shape. So the useful
     *  question is not "did a press fire" but "was the carrier even inside the press radius". */
    private static int insideRangeA, outsideRangeA;
    private static int overrideActiveTickCount, overridesSeen;

    // --- the dangerous opponent without the ball ---------------------------------------------
    private static int dangerOpportunity;      // a dangerous man in our defensive third
    private static int dangerCovered;          // ...with a defender within 1.5 cells of him
    private static int dangerUncovered;
    private static int dangerInsideBox, dangerUncoveredInsideBox;
    private static int dangerIsolated, dangerNotIsolated;
    private static int dangerCoveredWhileNotIsolated;

    // The question that actually discriminates. "Is anybody within 21 m" is nearly always true on a
    // 64-cell pitch with 22 men on it — it measured 100% and therefore measured nothing. What
    // separates a defence that tracks runners from one that chases the ball is WHICH man it is
    // closer to when it cannot do both, so that is what gets counted.
    private static int bothThreatsPresent;     // carrier in the final third AND a danger man behind him
    private static int closerToCarrier, closerToDangerMan, equidistant;
    private static int dangerManMarkedInFinalThird, dangerManOpenInFinalThird;

    // --- duels ----------------------------------------------------------------------------------
    private static int duelsSeen, duelsWon, duelsLost;

    // --- the shape --------------------------------------------------------------------------------
    private static double sumDefenderDistanceFromOwnGoal, defendersSampled;
    private static double sumSpread;
    private static int spreadSampled;
    private static int lineSizes;
    private static double sumClosestManToBox;   // nearest centre-back to the ball, when it is in our box
    private static int boxSamples;

    // --- shots, which is the question being asked --------------------------------------------------
    private static int shotsTaken, shotsWithNobodyWithin1, shotsWithOneWithin1, shotsWithTwoPlusWithin1;
    private static int shotsWithNobodyWithin2, shotsWithOneWithin2, shotsWithTwoPlusWithin2;
    private static double sumShotDistanceToGoal;
    private static int shotsCounted;

    /** How clear the decision to shoot was. If the runner-up was within the choice band the shot was
     *  a coin flip between two ideas, not a judgement, and the counts above have to be read with
     *  that in mind. */
    private static double sumShotMargin;
    private static int shotWonByLessThanBand;
    private static final Map<String, Integer> runnerUpWhenShot = new TreeMap<>();
    private static int shotsWereTheTopScore;

    // ============================================================================================

    public static void main(String[] args) {
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 400;
        int matches = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        int baseSeed = args.length > 2 ? Integer.parseInt(args[2]) : 42;

        System.out.printf("=== DEFENSIVE SHAPE · %d matches x %d ticks (%d match-minutes each) · "
                + "seed %d · every player skill %d ===%n%n", matches, ticks, ticks / 40, baseSeed, (int) SKILL);

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
            o.setTickObserver(DefensiveShapeDiag::onTick);
            o.setDecisionObserver(DefensiveShapeDiag::onDecision);
            o.simulate(ticks);
        }

        report();
    }

    /** One side, every player identical — the same reason as the other probe's copy of this. */
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

    // --------------------------------------------------------------- the tick observer

    private static void onTick(MatchState state) {
        measureShape(state);
        Player carrier = state.getCarrier();
        if (carrier == null || carrier.isGoalkeeper()) return;
        ticksWithCarrier++;
        measurePress(state, carrier);
        measureDangerMan(state, carrier);
    }

    /**
     * How close is the nearest defender, and how many are there?
     *
     * <p>Counted from the achieved positions, not the targets. A defence whose targets are all on the
     * ball but whose players are still four cells away is a defence that has not arrived.
     */
    private static void measurePress(MatchState state, Player carrier) {
        List<Player> defenders = opponentsOutfield(state, carrier.getTeam());
        if (defenders.isEmpty()) return;

        double best = Double.MAX_VALUE;
        Player nearest = null;
        int withinRangeA = 0;
        int pressed = 0;
        for (Player d : defenders) {
            double dist = SimUtils.distance(d.getPosition(), carrier.getPosition());
            if (dist < best) { best = dist; nearest = d; }
            if (dist <= ThreatOverrideEngine.RANGE_A) withinRangeA++;
            if (d.isThreatOverrideActive()) pressed++;
        }

        bump(nearestToCarrier, band(best));
        bump(presses, presses(withinRangeA));
        if (best <= ThreatOverrideEngine.RANGE_A) insideRangeA++; else outsideRangeA++;

        overridesSeen += pressed;
        if (pressed > 0) overrideActiveTickCount++;

        // Goal-side means his row is between the carrier and the goal the carrier attacks.
        if (nearest != null) {
            boolean carrierHome = "HOME".equals(carrier.getTeam());
            double ahead = carrierHome
                    ? nearest.getPosition().getRow() - carrier.getPosition().getRow()
                    : carrier.getPosition().getRow() - nearest.getPosition().getRow();
            if (ahead > 0.2) goalSide++;
            else if (ahead < -0.2) behindHim++;
            else abreast++;
        }
    }

    /**
     * Is the most dangerous man WITHOUT the ball being watched?
     *
     * <p>This is the question a marker is asked. The engine's own TYPE B rule only fires on an
     * opponent it considers <em>isolated</em>, which it defines as having no team-mate within half a
     * cell — so the rule that is supposed to close down a free man in the box switches itself off
     * exactly when the box gets crowded. That definition is measured here rather than assumed: an
     * attacker is counted as isolated by the engine's own test, and the two numbers side by side say
     * whether the rule can fire when it matters.
     */
    private static void measureDangerMan(MatchState state, Player carrier) {
        for (Player defending : state.getPlayers()) {
            if (!"HOME".equals(defending.getTeam()) && !"AWAY".equals(defending.getTeam())) continue;
            if (defending.isGoalkeeper() || defending.isSentOff()) continue;
            String team = defending.getTeam();
            boolean home = "HOME".equals(team);

            Player danger = null;
            double bestDanger = 0.0;
            for (Player attacker : state.getPlayers()) {
                if (!attacker.getTeam().equals(team)) continue;
                if (attacker == carrier || attacker.isGoalkeeper()) continue;
                if (attacker.isSentOff() || attacker.isInjured()) continue;
                double row = attacker.getPosition().getRow();
                boolean inDefThird = home ? row <= 3.0 : row >= 6.0;
                if (!inDefThird) continue;
                PlayerSkills s = attacker.getSkills();
                double quality = (s.striker() + s.technique()) / 40.0;
                if (quality > bestDanger) { bestDanger = quality; danger = attacker; }
            }
            if (danger == null) continue;

            dangerOpportunity++;

            boolean inBox = home
                    ? danger.getPosition().getRow() <= 2.0
                    : danger.getPosition().getRow() >= 7.0;
            if (inBox) dangerInsideBox++;

            // Is anyone actually near him?
            double nearest = Double.MAX_VALUE;
            boolean isolatedByEnginesTest = true;
            for (Player mate : state.getPlayers()) {
                if (!mate.getTeam().equals(team)) continue;
                if (mate.isGoalkeeper() || mate.isSentOff()) continue;
                double dist = SimUtils.distance(mate.getPosition(), danger.getPosition());
                if (dist < nearest) nearest = dist;
                // The engine's TYPE B test: no team-MATE within half a cell. It excludes the
                // defender himself from the test, which is correct, but it uses the same half a
                // cell it uses for marking.
                if (mate != defending && dist <= 0.5) isolatedByEnginesTest = false;
            }
            if (isolatedByEnginesTest) dangerIsolated++; else dangerNotIsolated++;

            if (nearest <= ThreatOverrideEngine.RANGE_A) {
                dangerCovered++;
                if (!isolatedByEnginesTest) dangerCoveredWhileNotIsolated++;
            } else {
                dangerUncovered++;
                if (inBox) dangerUncoveredInsideBox++;
            }

            // Head to head. Only meaningful while the carrier is actually a threat in our own third,
            // because a defender twenty metres from a striker in our box while the ball is forty
            // metres away in midfield is not choosing between two jobs.
            double carrierRow = carrier.getPosition().getRow();
            boolean carrierInOurThird = home ? carrierRow >= 6.0 : carrierRow <= 3.0;
            if (carrierInOurThird) {
                bothThreatsPresent++;
                double nearestCarrier = Double.MAX_VALUE;
                for (Player mate : state.getPlayers()) {
                    if (!mate.getTeam().equals(team)) continue;
                    if (mate.isGoalkeeper() || mate.isSentOff()) continue;
                    nearestCarrier = Math.min(nearestCarrier,
                            SimUtils.distance(mate.getPosition(), carrier.getPosition()));
                }
                if (nearest < nearestCarrier - 0.2) closerToDangerMan++;
                else if (nearestCarrier < nearest - 0.2) closerToCarrier++;
                else equidistant++;
                if (nearest <= 1.0) dangerManMarkedInFinalThird++;
                else dangerManOpenInFinalThird++;
            }
        }
    }

    /**
     * Line height and compactness, measured <b>by role</b> and not by taking the four deepest men.
     *
     * <p>The first version of this took the four outfielders furthest from their own goal and called
     * them the back four. In a 4-4-2 that is two strikers and two midfielders — the two strikers sit
     * further from their own goal than either centre-back does — so it reported the line at 68 m from
     * goal, twelve metres past the halfway line, and concluded the engine had abandoned its penalty
     * area. It had done nothing of the kind. {@code DefensiveShape.derive} drops a player by only
     * {@code DROP_SHARE} (16%) of his distance from his own goal, so a centre-back at 32 m defends at
     * 29 m. The 68 m was this method, not the football.
     *
     * <p>Measuring a named defensive role is the only version of this that can be wrong in an
     * interesting way instead of a useless one.
     */
    private static void measureShape(MatchState state) {
        for (String team : new String[]{"HOME", "AWAY"}) {
            boolean home = "HOME".equals(team);
            List<Player> line = new ArrayList<>();
            for (Player p : state.getPlayers()) {
                if (!p.getTeam().equals(team) || p.isSentOff()) continue;
                if (!isCentreBack(p.getRole())) continue;
                line.add(p);
            }
            if (line.isEmpty()) continue;

            double total = 0.0;
            double minC = Double.MAX_VALUE, maxC = -Double.MAX_VALUE;
            for (Player p : line) {
                total += home ? p.getPosition().getRow() : 8.0 - p.getPosition().getRow();
                minC = Math.min(minC, p.getPosition().getColumn());
                maxC = Math.max(maxC, p.getPosition().getColumn());
            }
            sumDefenderDistanceFromOwnGoal += total / line.size();
            defendersSampled++;
            sumSpread += maxC - minC;
            spreadSampled++;
            lineSizes += line.size();

            // The question that matters, not the shape in the abstract: when the ball is actually in
            // this side's box, how far away is its own centre-backs? A back four can sit at a perfectly
            // sensible height all match and still be thirty metres from the danger at the moment it
            // matters, and that is the thing a "how high is the line" number hides.
            double ballRow = state.getBall().getPosition().getRow();
            boolean ballInOurBox = home ? ballRow >= 6.0 : ballRow <= 3.0;
            if (ballInOurBox) {
                double closest = Double.MAX_VALUE;
                for (Player p : line) {
                    closest = Math.min(closest,
                            SimUtils.distance(p.getPosition(), state.getBall().getPosition()));
                }
                sumClosestManToBox += closest;
                boxSamples++;
            }
        }
    }

    /** The roles that are actually centre-backs, whatever the club has called them in the data. */
    private static boolean isCentreBack(String role) {
        return "DC".equals(role) || "DCL".equals(role) || "DCR".equals(role)
                || "CB".equals(role) || "CBL".equals(role) || "CBR".equals(role);
    }

    // --------------------------------------------------------------- the decision observer

    /**
     * What the defence looked like at the moment a shot was actually taken.
     *
     * <p>This is the owner's question stated as a measurement: a chance in a good position is a
     * chance somebody did not prevent, so the number that answers it is not the distance of the
     * nearest defender in the abstract but how many of them were within striking range when the shot
     * went off.
     */
    private static void onDecision(MatchState state, DecisionResult result, Player carrier) {
        DecisionOption chosen = result.getChosen();
        if (chosen.getType() != ActionType.SHOT) return;
        shotsTaken++;

        // Was the shot the clear best, or a near miss?
        double runnerUp = Double.NEGATIVE_INFINITY;
        DecisionOption runnerUpOption = null;
        for (DecisionOption o : result.getOptions()) {
            if (o == chosen || o.getScore() <= UNAVAILABLE) continue;
            if (o.getScore() > runnerUp) { runnerUp = o.getScore(); runnerUpOption = o; }
        }
        boolean topScore = runnerUp == Double.NEGATIVE_INFINITY || chosen.getScore() >= runnerUp;
        if (topScore) shotsWereTheTopScore++;
        double margin = runnerUp == Double.NEGATIVE_INFINITY ? 999.0 : chosen.getScore() - runnerUp;
        sumShotMargin += margin;
        if (margin < CHOICE_BAND) shotWonByLessThanBand++;
        if (runnerUpOption != null) bump(runnerUpWhenShot, runnerUpOption.getType().name());

        List<Player> defenders = opponentsOutfield(state, carrier.getTeam());
        int within1 = 0, within2 = 0;
        for (Player d : defenders) {
            double dist = SimUtils.distance(d.getPosition(), carrier.getPosition());
            if (dist <= 1.0) within1++;
            if (dist <= 2.0) within2++;
        }
        if (within1 == 0) shotsWithNobodyWithin1++; else if (within1 == 1) shotsWithOneWithin1++;
        else shotsWithTwoPlusWithin1++;
        if (within2 == 0) shotsWithNobodyWithin2++; else if (within2 == 1) shotsWithOneWithin2++;
        else shotsWithTwoPlusWithin2++;

        boolean home = "HOME".equals(carrier.getTeam());
        double goalRow = home ? 8.0 : 1.0;
        sumShotDistanceToGoal += Math.abs(carrier.getPosition().getRow() - goalRow);
        shotsCounted++;
    }

    // --------------------------------------------------------------- helpers

    private static List<Player> opponentsOutfield(MatchState state, String team) {
        List<Player> out = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(team)) continue;
            if (p.isGoalkeeper() || p.isSentOff() || p.isInjured() || p.isLocked()) continue;
            out.add(p);
        }
        return out;
    }

    private static String band(double d) {
        if (d <= 0.5) return "0.0-0.5  a tackle is available";
        if (d <= 1.0) return "0.5-1.0  pressing";
        if (d <= 1.5) return "1.0-1.5  at the edge of the press";
        if (d <= 2.5) return "1.5-2.5  nobody is coming";
        if (d <= 4.0) return "2.5-4.0  shape only";
        return "4.0+     he is free";
    }

    private static String presses(int n) {
        if (n == 0) return "0 defenders pressing";
        if (n == 1) return "1 defender pressing";
        return n + " defenders pressing";
    }

    private static void bump(Map<String, Integer> m, String key) {
        m.merge(key, 1, Integer::sum);
    }

    // --------------------------------------------------------------- output

    private static void report() {
        int ticks = ticksWithCarrier;

        System.out.println("1. DO THEY CLOSE THE MAN WITH THE BALL?");
        System.out.println("   nearest outfield defender, by distance");
        int cumulative = 0;
        for (Map.Entry<String, Integer> e : nearestToCarrier.entrySet()) {
            cumulative += e.getValue();
            System.out.printf("      %-34s %6d / %-6d %5.1f%%%n",
                    e.getKey(), e.getValue(), ticks, pct(e.getValue(), ticks));
        }
        System.out.println("   defenders within the press radius ("
                + fmt(ThreatOverrideEngine.RANGE_A) + " cells)");
        for (Map.Entry<String, Integer> e : presses.entrySet()) {
            System.out.printf("      %-34s %6d / %-6d %5.1f%%%n",
                    e.getKey(), e.getValue(), ticks, pct(e.getValue(), ticks));
        }
        System.out.printf("   and the nearest one was:%n");
        System.out.printf("      goal-side (getting between him and the goal) %6d / %-6d %5.1f%%%n",
                goalSide, ticks, pct(goalSide, ticks));
        System.out.printf("      trailing him from behind                    %6d / %-6d %5.1f%%%n",
                behindHim, ticks, pct(behindHim, ticks));
        System.out.printf("      level with him                              %6d / %-6d %5.1f%%%n",
                abreast, ticks, pct(abreast, ticks));
        System.out.printf("      carrier INSIDE the press radius            %6d / %-6d %5.1f%%%n",
                insideRangeA, ticks, pct(insideRangeA, ticks));
        System.out.printf("      carrier OUTSIDE it (no press, by design)    %6d / %-6d %5.1f%%%n",
                outsideRangeA, ticks, pct(outsideRangeA, ticks));
        System.out.println();

        System.out.println("2. DO THEY CLOSE THE DANGEROUS MAN WITHOUT THE BALL?");
        System.out.printf("   ticks with a dangerous attacker in our defensive third   %6d%n", dangerOpportunity);
        System.out.printf("      ...and a defender within %s cells of him              %6d / %-6d %5.1f%%%n",
                fmt(ThreatOverrideEngine.RANGE_A), dangerCovered, dangerOpportunity, pct(dangerCovered, dangerOpportunity));
        System.out.printf("      ...and nobody near him                                 %6d / %-6d %5.1f%%%n",
                dangerUncovered, dangerOpportunity, pct(dangerUncovered, dangerOpportunity));
        System.out.printf("      of those, inside the six-yard box and unwatched        %6d / %-6d %5.1f%%%n",
                dangerUncoveredInsideBox, dangerInsideBox, pct(dangerUncoveredInsideBox, Math.max(1, dangerInsideBox)));
        System.out.println();
        System.out.println("   WHOSE MAN IS HE WATCHING? (the ball, or the danger?)");
        System.out.println("   'within range' is useless as a measure on a 64-cell pitch with 22 men — it read 100%");
        System.out.println("   and therefore told us nothing. This compares them instead.");
        System.out.printf("      ticks with the carrier in our final third AND a danger man behind  %6d%n",
                bothThreatsPresent);
        System.out.printf("      nearest defender was closer to the CARRIER               %6d / %-6d %5.1f%%%n",
                closerToCarrier, bothThreatsPresent, pct(closerToCarrier, bothThreatsPresent));
        System.out.printf("      nearest defender was closer to the DANGER MAN            %6d / %-6d %5.1f%%%n",
                closerToDangerMan, bothThreatsPresent, pct(closerToDangerMan, bothThreatsPresent));
        System.out.printf("      equidistant                                             %6d / %-6d %5.1f%%%n",
                equidistant, bothThreatsPresent, pct(equidistant, bothThreatsPresent));
        System.out.printf("      the danger man had someone within 1 cell (7 m)          %6d / %-6d %5.1f%%%n",
                dangerManMarkedInFinalThird, bothThreatsPresent, pct(dangerManMarkedInFinalThird, bothThreatsPresent));
        System.out.printf("      ...and was left more than 1 cell open                    %6d / %-6d %5.1f%%%n",
                dangerManOpenInFinalThird, bothThreatsPresent, pct(dangerManOpenInFinalThird, bothThreatsPresent));
        System.out.println();
        System.out.println("   IS THE ENGINE'S OWN 'ISOLATED' TEST SATISFIED?");
        System.out.println("   (TYPE B only closes a dangerous man down if it calls him isolated)");
        System.out.printf("      the attacker counts as ISOLATED                   %6d / %-6d %5.1f%%%n",
                dangerIsolated, dangerOpportunity, pct(dangerIsolated, dangerOpportunity));
        System.out.printf("      he does NOT — a team-mate is within half a cell     %6d / %-6d %5.1f%%%n",
                dangerNotIsolated, dangerOpportunity, pct(dangerNotIsolated, dangerOpportunity));
        System.out.printf("      and of those, he was covered anyway                %6d / %-6d %5.1f%%%n",
                dangerCoveredWhileNotIsolated, Math.max(1, dangerNotIsolated), pct(dangerCoveredWhileNotIsolated, dangerNotIsolated));
        System.out.println();

        System.out.println("3. THE SHAPE — measured by ROLE, not by taking the four deepest men");
        System.out.println("   (the first version did that and reported a 68 m line that was two strikers)");
        double lineDist = defendersSampled == 0 ? 0 : sumDefenderDistanceFromOwnGoal / defendersSampled;
        System.out.printf("   centre-backs per side (mean):                   %.2f%n",
                defendersSampled == 0 ? 0 : (double) lineSizes / defendersSampled);
        System.out.printf("   centre-backs, mean distance from their OWN goal: %.2f cells (%.0f m)%n",
                lineDist, lineDist * 14.0);
        System.out.printf("   centre-backs, mean width:                       %.2f cells (%.0f m)%n",
                spreadSampled == 0 ? 0 : sumSpread / spreadSampled,
                spreadSampled == 0 ? 0 : sumSpread / spreadSampled * 14.0);
        System.out.printf("   when the ball is IN THEIR BOX, nearest CB to it: %.2f cells (%.0f m)%n",
                boxSamples == 0 ? 0 : sumClosestManToBox / boxSamples,
                boxSamples == 0 ? 0 : sumClosestManToBox / boxSamples * 14.0);
        System.out.println();

        System.out.println("4. AT THE MOMENT A SHOT WENT OFF — the owner's question");
        System.out.printf("   shots taken                                                   %6d%n", shotsTaken);
        System.out.printf("   mean distance from goal                                        %.2f cells (%.0f m)%n",
                shotsCounted == 0 ? 0 : sumShotDistanceToGoal / shotsCounted,
                shotsCounted == 0 ? 0 : sumShotDistanceToGoal / shotsCounted * 14.0);
        System.out.println("   who was near the shooter");
        System.out.printf("      NOBODY within 1 cell (7 m)                                  %6d / %-6d %5.1f%%%n",
                shotsWithNobodyWithin1, shotsTaken, pct(shotsWithNobodyWithin1, shotsTaken));
        System.out.printf("      one within 1 cell                                           %6d / %-6d %5.1f%%%n",
                shotsWithOneWithin1, shotsTaken, pct(shotsWithOneWithin1, shotsTaken));
        System.out.printf("      two or more within 1 cell                                   %6d / %-6d %5.1f%%%n",
                shotsWithTwoPlusWithin1, shotsTaken, pct(shotsWithTwoPlusWithin1, shotsTaken));
        System.out.printf("      NOBODY within 2 cells (28 m)                                %6d / %-6d %5.1f%%%n",
                shotsWithNobodyWithin2, shotsTaken, pct(shotsWithNobodyWithin2, shotsTaken));
        System.out.printf("      one within 2 cells                                          %6d / %-6d %5.1f%%%n",
                shotsWithOneWithin2, shotsTaken, pct(shotsWithOneWithin2, shotsTaken));
        System.out.printf("      two or more within 2 cells                                  %6d / %-6d %5.1f%%%n",
                shotsWithTwoPlusWithin2, shotsTaken, pct(shotsWithTwoPlusWithin2, shotsTaken));
        System.out.println("   was shooting actually the best option?");
        System.out.printf("      it was the highest score available                        %6d / %-6d %5.1f%%%n",
                shotsWereTheTopScore, shotsTaken, pct(shotsWereTheTopScore, shotsTaken));
        System.out.printf("      mean margin over the runner-up                             %8.1f points%n",
                shotsTaken == 0 ? 0 : sumShotMargin / shotsTaken);
        System.out.printf("      won by less than the %.0f-point choice band                %6d / %-6d %5.1f%%%n",
                CHOICE_BAND, shotWonByLessThanBand, shotsTaken, pct(shotWonByLessThanBand, shotsTaken));
        System.out.println("      what it beat:");
        for (Map.Entry<String, Integer> e : runnerUpWhenShot.entrySet()) {
            System.out.printf("         %-10s %6d / %-6d %5.1f%%%n",
                    e.getKey(), e.getValue(), shotsTaken, pct(e.getValue(), shotsTaken));
        }
        System.out.println();
    }

    private static double pct(int n, int d) {
        return d == 0 ? 0.0 : n * 100.0 / d;
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }
}