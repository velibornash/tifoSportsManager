package org.tifo.footballmanager.newLogic.sim.probe;

import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.PlayerSkills;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.tactics.FormationSlotCatalog;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticalPerspectiveTransformer;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticsSlotDTO;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Do the players stand where a manager would expect them to stand, relative to the ball?
 *
 * <h2>Why this diagnostic exists (owner, 2026-10-18)</h2>
 *
 * <p>The stated architecture: <i>"POZICIJA IGRAČA u odnosu na POZICIJU LOPTE iz tactical editora je GLAVNI
 * IZVOR ISTINE za kretanje igrača, jer tako dajemo manageru da direktno utiče na kretnje igrača.
 * Overrides su samo blagi tweak."</i> That is the principle the whole game rests on, and it has never
 * been measured — every check so far has asked whether the engine <em>computes</em> a sensible target,
 * never whether the resulting shape is one a manager would recognise.
 *
 * <p>So this asks the blunt question: take the real shipped tactics, play football, and look at where
 * the twenty-two men actually end up. Anything a manager would look at and say "no" is the finding.
 *
 * <h2>What counts as "no"</h2>
 *
 * <ol>
 *   <li><b>The back four not being the deepest four.</b> If a midfielder is goal-side of a centre-back,
 *       the shape is inside out.</li>
 *   <li><b>Centre-backs standing on top of each other.</b> Measured at 13 m apart; the real figure is
 *       20-25 m.</li>
 *   <li><b>Nobody goal-side of the ball when we defend.</b> A ball between the defenders and their own
 *       keeper is the oldest way to concede.</li>
 *   <li><b>Defenders leaving their own box open.</b> The 2026-10-18 measurement said the nearest
 *       centre-back is 40 m from the ball while it is in his own box. This measures where he is
 *       <em>supposed</em> to be, which is the other half of that question.</li>
 *   <li><b>Two players on one cell.</b> The old null-fallback bug sent nine players to (1.5, 3.5); this
 *       is the guard that would catch it happening again through any other route.</li>
 *   <li><b>Attacking players left behind the ball.</b> Nothing good comes of it.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   mvn -q -o -DskipTests exec:java \
 *     -Dexec.mainClass=org.tifo.footballmanager.newLogic.sim.probe.TacticalShapeDiag \
 *     -Dexec.args="[ticksPerMatch] [matches] [baseSeed]"
 * </pre>
 */
public final class TacticalShapeDiag {

    private TacticalShapeDiag() {
    }

    private static int ticks;

    /** Role -> ball's third -> how often we saw it, and how far from our own goal the role stood.
     *  Indexed [third] = { samples, sumOfDistanceFromOwnGoal, sumOfDistanceToBall }. */
    private static final Map<String, double[]> seen = new TreeMap<>();

    private static double[] slot(Map<String, double[]> m, String role, int third) {
        return m.computeIfAbsent(role, k -> new double[3 * 3]);
    }

    private static int ticksSampled;
    private static int anyTwoTeamMatesOnOneCell;
    private static int cbNotTheDeepestOutfield;
    private static int defendersBehindTheBallWhenDefending;
    private static int attackersBehindTheBallWhenAttacking;
    private static int nobodyBetweenBallAndOurGoal;
    private static int ballInOurBoxWithNoDefenderInOurBox;
    private static int boxSamples, boxWithSomeone;

    /** Are the two centre-backs distinct? Counted as "how many cells apart", which is the real
     *  football question — two defenders 13 m apart are one defender standing twice. */
    private static double sumCentreBackSeparation;
    private static int centreBackPairs;
    private static int centreBacksStacked;

    public static void main(String[] args) {
        int ticksPerMatch = args.length > 0 ? Integer.parseInt(args[0]) : 400;
        int matches = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        int baseSeed = args.length > 2 ? Integer.parseInt(args[2]) : 42;

        var loaded = RealTacticsHarness.loadWorldDefault();
        System.out.printf("=== TACTICAL SHAPE · %d matches x %d ticks · seed %d · skill %d · "
                        + "tactic '%s' (%s) from %s · %d rules · club '%s' ===%n%n",
                matches, ticksPerMatch, baseSeed, (int) RealTacticsHarness.SKILL,
                loaded.formation(), loaded.style(), loaded.source(), loaded.ruleCount(), loaded.teamName());
        if (!loaded.source().startsWith("the WORLD DEFAULT")) {
            System.out.println("!! NOT a designated world default - these numbers do not describe the "
                    + "tactic the owner chose. Press Admin > Save Default Tactics.\n");
        }

        for (int m = 0; m < matches; m++) {
            SimulationRandom.seed(baseSeed + m);
            MatchState state = new MatchState();
            RealTacticsHarness.addTeam(state, "HOME", loaded.formation());
            RealTacticsHarness.addTeam(state, "AWAY", loaded.formation());
            MatchOrchestrator o = RealTacticsHarness.match(state, loaded);
            o.getRestartManager().handleKickoff(state, "HOME");
            o.setTickObserver(TacticalShapeDiag::onTick);
            o.simulate(ticksPerMatch);
        }

        report();
    }

    private static int thirdOf(double ballRow, boolean home) {
        double t = home ? ballRow : 8.0 - ballRow;   // 1.0 = deep in our own half, 8.0 = their goal
        return t <= 3.0 ? 0 : (t <= 6.0 ? 1 : 2);    // 0 our third, 1 middle, 2 their third
    }

    private static void onTick(MatchState state) {
        ticks++;
        Position ball = state.getBall().getPosition();
        String possessionTeam = state.getCarrierTeam();

        for (String team : new String[]{"HOME", "AWAY"}) {
            boolean home = "HOME".equals(team);
            int third = thirdOf(ball.getRow(), home);
            List<Player> ours = new ArrayList<>();
            for (Player p : state.getPlayers()) {
                if (p.getTeam().equals(team) && !p.isSentOff()) ours.add(p);
            }

            // --- 1. Is the back line actually the deepest? -------------------------------------
            Player deepestOutfield = null;
            Player deepestOverall = null;
            for (Player p : ours) {
                double d = distanceFromOwnGoal(p, team);
                if (deepestOutfield == null || d < distanceFromOwnGoal(deepestOutfield, team)) {
                    deepestOutfield = p;
                }
                if (p.isGoalkeeper()) continue;
                if (deepestOverall == null || d < distanceFromOwnGoal(deepestOverall, team)) {
                    deepestOverall = p;
                }
            }
            // A full-back counts. The first version of this test recognised only DC/DCL/DCR and reported
            // 72.8% "the deepest outfield man was not a centre-back" — in a 4-4-2 that is DL and DR,
            // who are defenders and who very often ARE the deepest man on the pitch. The number was
            // measuring my role list, not the shape.
            if (deepestOverall != null && !deepestOverall.isDefender()) {
                cbNotTheDeepestOutfield++;
            }

            // --- 2. Are the two centre-backs actually two men? --------------------------------
            List<Player> cbs = new ArrayList<>();
            for (Player p : ours) if (isCentreBack(p.getRole())) cbs.add(p);
            if (cbs.size() == 2) {
                double sep = Math.hypot(cbs.get(0).getPosition().getRow() - cbs.get(1).getPosition().getRow(),
                        cbs.get(0).getPosition().getColumn() - cbs.get(1).getPosition().getColumn());
                sumCentreBackSeparation += sep;
                centreBackPairs++;
                if (sep < 1.0) centreBacksStacked++;
            }

            // --- 3. Two team-mates on one cell. -----------------------------------------------
            boolean clash = false;
            for (int i = 0; i < ours.size() && !clash; i++) {
                for (int j = i + 1; j < ours.size(); j++) {
                    Player a = ours.get(i), b = ours.get(j);
                    if (a.isGoalkeeper() || b.isGoalkeeper()) continue;
                    if (Math.abs(a.getPosition().getRow() - b.getPosition().getRow()) < 0.2
                            && Math.abs(a.getPosition().getColumn() - b.getPosition().getColumn()) < 0.2) {
                        clash = true;
                        break;
                    }
                }
            }
            if (clash) anyTwoTeamMatesOnOneCell++;

            // --- 4. Nobody between the ball and our own goal. ---------------------------------
            if (possessionTeam != null && !possessionTeam.equals(team)) {
                ticksSampled++;
                boolean someoneBetween = false;
                for (Player p : ours) {
                    if (p.isGoalkeeper() || p.isSentOff()) continue;
                    double d = home ? p.getPosition().getRow() : 8.0 - p.getPosition().getRow();
                    double b = home ? ball.getRow() : 8.0 - ball.getRow();
                    if (d < b) { someoneBetween = true; break; }
                }
                if (!someoneBetween) nobodyBetweenBallAndOurGoal++;

                int defendersBehindBall = 0;
                for (Player p : ours) {
                    if (p.isGoalkeeper() || p.isSentOff()) continue;
                    if (!isCentreBack(p.getRole())) continue;
                    if (distanceFromOwnGoal(p, team) < ballDistanceFromOwnGoal(ball, team)) {
                        defendersBehindBall++;
                    }
                }
                if (defendersBehindBall == 0) defendersBehindTheBallWhenDefending++;

                // --- 5. Ball in our box — is ANY defender in it? ------------------------------
                boolean inOurBox = home ? ball.getRow() >= 6.0 : ball.getRow() <= 3.0;
                if (inOurBox) {
                    boxSamples++;
                    boolean someone = false;
                    for (Player p : ours) {
                        if (p.isGoalkeeper() || p.isSentOff()) continue;
                        double d = home ? p.getPosition().getRow() : 8.0 - p.getPosition().getRow();
                        if (d <= 2.5) { someone = true; break; }
                    }
                    if (someone) boxWithSomeone++;
                    else ballInOurBoxWithNoDefenderInOurBox++;
                }
            }

            // --- 6. Attacking players left behind the ball. -----------------------------------
            if (possessionTeam != null && possessionTeam.equals(team)) {
                int behind = 0;
                for (Player p : ours) {
                    if (p.isGoalkeeper() || p.isSentOff()) continue;
                    if (distanceFromOwnGoal(p, team) < ballDistanceFromOwnGoal(ball, team) - 0.2) {
                        behind++;
                    }
                }
                attackersBehindTheBallWhenAttacking += behind;
            }

            // --- 7. Record the shape per role and per third of the pitch. ----------------------
            for (Player p : ours) {
                if (p.isSentOff()) continue;
                double[] s = slot(seen, p.getRole(), third);
                int base = third * 3;
                s[base] += 1;                              // samples
                s[base + 1] += distanceFromOwnGoal(p, team);       // true distance, not row
                s[base + 2] += SimUtils.distance(p.getPosition(), ball);
            }
        }
    }

    /** True Euclidean distance from a player's own goal centre, not his row. */
    private static double distanceFromOwnGoal(Player p, String team) {
        return SimUtils.distance(p.getPosition(), ownGoal(team));
    }

    private static double ballDistanceFromOwnGoal(Position ball, String team) {
        return SimUtils.distance(ball, ownGoal(team));
    }

    private static Position ownGoal(String team) {
        return "HOME".equals(team) ? new Position(1.0, 4.0) : new Position(8.0, 4.0);
    }

    private static boolean isCentreBack(String role) {
        return "DC".equals(role) || "DCL".equals(role) || "DCR".equals(role)
                || "CB".equals(role) || "CBL".equals(role) || "CBR".equals(role);
    }

    private static void report() {
        System.out.println("WHERE THE SHAPE IS BROKEN");
        System.out.printf("   ticks sampled                                       %6d%n", ticks);
        System.out.printf("   the deepest outfield man was NOT a centre-back      %6d / %-6d %5.1f%%%n",
                cbNotTheDeepestOutfield, ticks * 2, pct(cbNotTheDeepestOutfield, ticks * 2));
        System.out.printf("   two outfield team-mates within 0.2 of each other    %6d / %-6d %5.1f%%%n",
                anyTwoTeamMatesOnOneCell, ticks * 2, pct(anyTwoTeamMatesOnOneCell, ticks * 2));
        System.out.printf("   centre-backs under 1 cell apart (one man, not two)  %6d / %-6d %5.1f%%%n",
                centreBacksStacked, Math.max(1, centreBackPairs), pct(centreBacksStacked, centreBackPairs));
        System.out.printf("   mean centre-back separation                         %8.2f cells (%.0f m)%n",
                centreBackPairs == 0 ? 0 : sumCentreBackSeparation / centreBackPairs,
                centreBackPairs == 0 ? 0 : sumCentreBackSeparation / centreBackPairs * 14.0);
        System.out.println();
        System.out.println("WHEN WE DO NOT HAVE THE BALL");
        System.out.printf("   NOBODY goal-side of the ball                        %6d / %-6d %5.1f%%%n",
                nobodyBetweenBallAndOurGoal, ticksSampled, pct(nobodyBetweenBallAndOurGoal, ticksSampled));
        System.out.printf("   NO centre-back goal-side of the ball                %6d / %-6d %5.1f%%%n",
                defendersBehindTheBallWhenDefending, ticksSampled, pct(defendersBehindTheBallWhenDefending, ticksSampled));
        System.out.printf("   ball in our box, nobody inside it                    %6d / %-6d %5.1f%%%n",
                ballInOurBoxWithNoDefenderInOurBox, boxSamples, pct(ballInOurBoxWithNoDefenderInOurBox, boxSamples));
        System.out.printf("   ball in our box, somebody inside it                  %6d / %-6d %5.1f%%%n",
                boxWithSomeone, boxSamples, pct(boxWithSomeone, boxSamples));
        System.out.println();
        System.out.println("WHERE EACH ROLE STANDS, BY WHERE THE BALL IS");
        System.out.println("   distance from OUR OWN goal, in cells (1.0 = on our goal line, 8.0 = theirs)");
        System.out.println("   and in brackets how far the role stood from the ball itself");
        System.out.printf("   %-8s %22s %22s %22s%n", "role", "ball in OUR third", "ball in middle", "ball in THEIR third");
        for (Map.Entry<String, double[]> e : seen.entrySet()) {
            String role = e.getKey();
            double[] s = e.getValue();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                int base = i * 3;
                if (s[base] == 0) { sb.append(String.format("%22s", "-")); continue; }
                sb.append(String.format("%12.2f (%5.2f)", s[base + 1] / s[base], s[base + 2] / s[base]));
            }
            System.out.printf("   %-8s %s%n", role, sb);
        }
        System.out.println();
        System.out.println("   ATTACKERS LEFT BEHIND THE BALL WHEN WE HAVE IT");
        System.out.printf("      total outfielders goal-side of our own carrier    %6d over %d ticks%n",
                attackersBehindTheBallWhenAttacking, ticks * 2);
    }

    private static double pct(int n, int d) {
        return d == 0 ? 0.0 : n * 100.0 / d;
    }
}