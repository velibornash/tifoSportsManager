package org.example.footballmanager.newLogic.sim.probe;

import org.example.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.example.footballmanager.newLogic.sim.model.ActionType;
import org.example.footballmanager.newLogic.sim.model.Ball;
import org.example.footballmanager.newLogic.sim.model.DecisionOption;
import org.example.footballmanager.newLogic.sim.model.DecisionResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.PitchEnvironment;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DECISION ENGINE DIAGNOSTIC — a scenario bench, not a match.
 *
 * <p>Builds a frozen 22-player situation (all skills equal), hands the ball to one
 * carrier, and asks the decision engine what it would do — printing the pitch, every
 * option with its score and reason, and the chosen action. Then it answers the four
 * questions the engine has to pass:
 *
 * <ol>
 *   <li><b>Sense</b> — was the chosen option the best available one? (receiver choice,
 *       action choice, shot-from-danger)</li>
 *   <li><b>Playmaking reach</b> — does the playmaking skill change how many options are
 *       on the table, or only how the tie is broken?</li>
 *   <li><b>Random</b> — is there a small random that can tip a close call, and is a
 *       high-skill carrier more resistant to it than a low-skill one?</li>
 *   <li><b>Audit</b> — the full receiver table for the chosen pass, so a better pass can
 *       be spotted by eye.</li>
 * </ol>
 *
 * <p>Scenarios are deterministic (fixed positions, fixed seed) so a finding can be
 * reproduced; the playmaking sweep deliberately re-runs the SAME situation many times
 * to measure the random.
 *
 * <p>Run: {@code mvn exec:java -Dexec.mainClass=
 * org.example.footballmanager.newLogic.sim.probe.DecisionEngineProbe}
 */
public final class DecisionEngineProbe {

    /** All eight skills — the "solid but not elite" team the owner asked for. */
    public static final double SKILL = 14.0;

    public static PlayerSkills skills(double v) {
        return new PlayerSkills(v, v, v, v, v, v, v, v);
    }

    public static PlayerSkills skillsWithPlaymaking(double pm) {
        return new PlayerSkills(SKILL, SKILL, SKILL, SKILL, pm, SKILL, SKILL, SKILL);
    }

    public static PlayerSkills skillsWithPlaymakingAndPassing(double pm, double pas) {
        return new PlayerSkills(SKILL, SKILL, SKILL, SKILL, pm, pas, SKILL, SKILL);
    }

    // ---------------------------------------------------------------- scenarios

    /** One frozen tactical situation: 22 named players and where the ball is. */
    public record Scenario(String name, String description, String carrierId, List<Player> players) {}

    private static Player p(String id, String team, String role, double row, double col) {
        return new Player(id, id + "(" + role + ")", team, role,
                new Position(row, col), new Position(row, col), skills(SKILL), 180);
    }

    private static Player pm(String id, String team, String role, double row, double col, double pm) {
        return new Player(id, id + "(" + role + ")", team, role,
                new Position(row, col), new Position(row, col), skillsWithPlaymaking(pm), 180);
    }

    /** 4-3-3 shape, HOME attacking the AWAY goal (row 8.0). */
    public static Scenario openBuildUp() {
        List<Player> ps = new ArrayList<>();
        ps.add(p("H_GK", "HOME", "GK", 1.5, 4.0));
        ps.add(p("H_DL", "HOME", "DL", 2.5, 1.5));
        ps.add(p("H_DCL", "HOME", "DCL", 2.0, 2.8));
        ps.add(p("H_DCR", "HOME", "DCR", 2.0, 5.2));
        ps.add(p("H_DR", "HOME", "DR", 2.5, 6.5));
        ps.add(p("H_CML", "HOME", "CML", 4.2, 2.6));
        ps.add(p("H_CMR", "HOME", "CMR", 4.4, 5.0));
        ps.add(p("H_STL", "HOME", "STL", 5.6, 3.0));
        ps.add(p("H_STR", "HOME", "STR", 5.8, 5.4));
        ps.add(p("H_AMR", "HOME", "MR", 5.0, 6.4));
        ps.add(p("A_GK", "AWAY", "GK", 7.5, 4.0));
        ps.add(p("A_DL", "AWAY", "DL", 6.5, 1.5));
        ps.add(p("A_DCL", "AWAY", "DCL", 7.0, 2.8));
        ps.add(p("A_DCR", "AWAY", "DCR", 7.0, 5.2));
        ps.add(p("A_DR", "AWAY", "DR", 6.5, 6.5));
        ps.add(p("A_CML", "AWAY", "CML", 4.6, 3.0));
        ps.add(p("A_CMR", "AWAY", "CMR", 4.4, 5.4));
        ps.add(p("A_STL", "AWAY", "STL", 3.2, 3.2));
        ps.add(p("A_STR", "AWAY", "STR", 2.8, 5.0));
        ps.add(p("A_ML", "AWAY", "ML", 4.6, 1.6));
        return new Scenario("OPEN_BUILD_UP",
                "CMR in space at halfway, 3 forward options, no immediate pressure",
                "H_CMR", ps);
    }

    /** Carrier pinned in his own third with the ball at his feet. */
    public static Scenario pressedInOwnThird() {
        List<Player> ps = new ArrayList<>();
        ps.add(p("H_GK", "HOME", "GK", 1.5, 4.0));
        ps.add(p("H_DL", "HOME", "DL", 2.6, 1.5));
        ps.add(p("H_DR", "HOME", "DR", 2.6, 6.5));
        ps.add(p("H_DCL", "HOME", "DCL", 2.2, 3.2));
        ps.add(p("H_DCR", "HOME", "DCR", 2.2, 5.0));
        ps.add(p("H_CML", "HOME", "CML", 4.2, 2.6));
        ps.add(p("H_CMR", "HOME", "CMR", 4.4, 5.0));
        ps.add(p("H_STL", "HOME", "STL", 5.6, 3.0));
        ps.add(p("H_STR", "HOME", "STR", 5.8, 5.4));
        ps.add(p("H_AMR", "HOME", "MR", 5.0, 6.4));
        ps.add(p("A_GK", "AWAY", "GK", 7.5, 4.0));
        ps.add(p("A_DL", "AWAY", "DL", 6.5, 1.5));
        ps.add(p("A_DCL", "AWAY", "DCL", 7.0, 2.8));
        ps.add(p("A_DCR", "AWAY", "DCR", 7.0, 5.2));
        ps.add(p("A_DR", "AWAY", "DR", 6.5, 6.5));
        // a midfielder right on top of the carrier
        ps.add(p("A_PRESS", "AWAY", "CML", 3.1, 4.6));
        ps.add(p("A_STL", "AWAY", "STL", 3.2, 3.2));
        ps.add(p("A_STR", "AWAY", "STR", 2.8, 5.0));
        ps.add(p("A_CMR", "AWAY", "CMR", 4.4, 5.4));
        return new Scenario("PRESSED_IN_OWN_THIRD",
                "DCR with the ball 2 rows from his own goal, an away midfielder pressing",
                "H_DCR", ps);
    }

    /** Ball in the box, goal in front, defenders around. */
    public static Scenario inTheBox() {
        List<Player> ps = new ArrayList<>();
        ps.add(p("H_GK", "HOME", "GK", 1.5, 4.0));
        ps.add(p("H_DL", "HOME", "DL", 2.5, 1.5));
        ps.add(p("H_DCL", "HOME", "DCL", 2.0, 2.8));
        ps.add(p("H_DCR", "HOME", "DCR", 2.0, 5.2));
        ps.add(p("H_DR", "HOME", "DR", 2.5, 6.5));
        ps.add(p("H_CML", "HOME", "CML", 4.2, 2.6));
        ps.add(p("H_CMR", "HOME", "CMR", 4.4, 5.0));
        ps.add(p("H_STL", "HOME", "STL", 6.9, 3.0));
        ps.add(p("H_STR", "HOME", "STR", 7.1, 5.2));
        ps.add(p("H_AMR", "HOME", "MR", 6.6, 6.3));
        ps.add(p("A_GK", "AWAY", "GK", 7.6, 4.0));
        ps.add(p("A_DL", "AWAY", "DL", 6.6, 1.6));
        ps.add(p("A_DCL", "AWAY", "DCL", 7.2, 2.9));
        ps.add(p("A_DCR", "AWAY", "DCR", 7.2, 4.9));
        ps.add(p("A_DR", "AWAY", "DR", 6.6, 6.4));
        ps.add(p("A_CML", "AWAY", "CML", 5.4, 3.4));
        ps.add(p("A_CMR", "AWAY", "CMR", 5.2, 5.6));
        ps.add(p("A_STL", "AWAY", "STL", 4.2, 2.8));
        ps.add(p("A_STR", "AWAY", "STR", 4.4, 5.8));
        ps.add(p("A_ML", "AWAY", "ML", 5.6, 1.8));
        return new Scenario("IN_THE_BOX",
                "STR on the edge of the 6-yard box, keeper on his line, 4 defenders back",
                "H_STR", ps);
    }

    /** Two clearly different receivers — the pass-target quality test. */
    public static Scenario twoReceivers() {
        List<Player> ps = new ArrayList<>();
        ps.add(p("H_GK", "HOME", "GK", 1.5, 4.0));
        ps.add(p("H_DL", "HOME", "DL", 2.5, 1.5));
        ps.add(p("H_DCL", "HOME", "DCL", 2.0, 2.8));
        ps.add(p("H_DCR", "HOME", "DCR", 2.0, 5.2));
        ps.add(p("H_DR", "HOME", "DR", 2.5, 6.5));
        ps.add(p("H_CML", "HOME", "CML", 4.2, 2.6));
        ps.add(p("H_CMR", "HOME", "CMR", 4.6, 4.6));
        ps.add(p("H_STL", "HOME", "STL", 5.6, 3.0));
        ps.add(p("H_STR", "HOME", "STR", 5.8, 5.4));
        ps.add(p("H_AMR", "HOME", "MR", 5.0, 6.4));
        ps.add(p("A_GK", "AWAY", "GK", 7.5, 4.0));
        ps.add(p("A_DL", "AWAY", "DL", 6.5, 1.5));
        ps.add(p("A_DCL", "AWAY", "DCL", 7.0, 2.8));
        ps.add(p("A_DCR", "AWAY", "DCR", 7.0, 5.2));
        ps.add(p("A_DR", "AWAY", "DR", 6.5, 6.5));
        // A_WIDE is completely free; A_MID is tightly marked by H_DCR
        ps.add(p("A_WIDE", "AWAY", "MR", 4.4, 6.6));
        ps.add(p("A_MID", "AWAY", "CML", 4.6, 2.8));
        ps.add(p("A_STL", "AWAY", "STL", 3.2, 3.2));
        ps.add(p("A_STR", "AWAY", "STR", 2.8, 5.0));
        return new Scenario("TWO_RECEIVERS",
                "H_CMR with A_WIDE free and A_MID marked — pass target quality",
                "H_CMR", ps);
    }

    /** A tie: two options with almost the same score, to exercise the random. */
    public static Scenario nearTie() {
        List<Player> ps = openBuildUp().players();
        return new Scenario("NEAR_TIE",
                "same shape, one small nudge so the top two options are close",
                "H_CMR", ps);
    }

    // ---------------------------------------------------------------- harness

    /** Build a state with the ball at the carrier and nothing else in progress. */
    public static MatchState state(Scenario sc) {
        MatchState state = new MatchState();
        Player carrier = null;
        for (Player p : sc.players()) {
            state.getPlayers().add(p);
            if (p.getId().equals(sc.carrierId())) carrier = p;
        }
        if (carrier == null) throw new IllegalArgumentException("no carrier " + sc.carrierId());
        state.setCarrier(carrier);
        state.setLastTouchTeam(carrier.getTeam());
        Ball ball = state.getBall();
        ball.setPosition(carrier.getPosition());
        ball.stop();
        state.clearPassContext();
        return state;
    }

    private static String pos(Position p) {
        return String.format("(%.1f,%.1f)", p.getRow(), p.getColumn());
    }

    // ---------------------------------------------------------------- pitch map

    private static void printPitch(Scenario sc) {
        // 17 rows x 30 cols ASCII: row 1.0 (HOME goal) at the top, row 8.0 at the
        // bottom. Columns 1.0..7.0 across.
        String[][] grid = new String[34][30];
        for (int r = 0; r < 34; r++) {
            for (int c = 0; c < 30; c++) {
                boolean line = r % 4 == 0;
                grid[r][c] = (c % 5 == 0 ? "|" : line ? "-" : ".");
            }
        }
        for (Player p : sc.players()) {
            int r = (int) Math.round((p.getPosition().getRow() - 1.0) * 4.0);
            int c = (int) Math.round((p.getPosition().getColumn() - 1.0) * 4.0);
            r = Math.max(0, Math.min(33, r));
            c = Math.max(0, Math.min(29, c));
            boolean carrier = p.getId().equals(sc.carrierId());
            grid[r][c] = carrier ? "O" : ("HOME".equals(p.getTeam()) ? "H" : "A");
        }
        System.out.println("    HOME goal (row 1.0)                                        AWAY goal (row 8.0)");
        for (int r = 0; r < 34; r++) {
            StringBuilder sb = new StringBuilder("  ");
            for (int c = 0; c < 30; c++) sb.append(grid[r][c]);
            System.out.println(sb);
        }
        System.out.println("  O = carrier (ball)   H = home   A = away    (col 1.0 left .. 7.0 right)");
        Player carrier = sc.players().stream().filter(p -> p.getId().equals(sc.carrierId())).findFirst().orElseThrow();
        System.out.println("  ball at " + pos(carrier.getPosition())
                + "   attacking direction: " + ("HOME".equals(carrier.getTeam()) ? "toward row 8.0" : "toward row 1.0"));
    }

    // ---------------------------------------------------------------- 1. the pitch + options

    public static void dumpScenario(Scenario sc) {
        System.out.println();
        System.out.println("=".repeat(96));
        System.out.println("SCENARIO  " + sc.name() + "   —   " + sc.description());
        System.out.println("=".repeat(96));
        printPitch(sc);

        MatchState state = state(sc);
        Player carrier = state.getCarrier();
        CleanDecisionEngine engine = new CleanDecisionEngine();
        SimulationRandom.seed(7L);
        DecisionResult res = engine.decideWithOptions(state);
        DecisionOption chosen = res.getChosen();

        System.out.println();
        System.out.println("  CARRIER  " + carrier.getLabel() + "  team=" + carrier.getTeam()
                + "  pm=" + (int) carrier.getSkills().playmaking()
                + "  pas=" + (int) carrier.getSkills().passing()
                + "  at " + pos(carrier.getPosition()));
        System.out.println();
        System.out.println("  OPTIONS  (score / reason)");
        for (DecisionOption o : res.getOptions()) {
            String mark = o == chosen ? "  <== CHOSEN" : "";
            String tgt = o.getTarget() != null ? " -> " + o.getTarget().getId() : "";
            System.out.printf("    %-8s %9.1f%s  %s%s%n",
                    o.getType(), o.getScore(), tgt, o.getReason(), mark);
        }
        System.out.println();
        System.out.println("  CHOSEN: " + chosen.getType()
                + (chosen.getTarget() != null ? " -> " + chosen.getTarget().getId() : "")
                + "  (" + chosen.getReason() + ")");
    }

    // ---------------------------------------------------------------- 2. playmaking sweep

    /**
     * A randomised situation: the same 22-player shape, but the ball is handed to a
     * random home player and a random away defender is dragged onto him, so the
     * margin between the best and second-best option varies. This is the only
     * honest way to measure the playmaking curve — a single hand-placed position
     * either always ties or never ties.
     */
    public static MatchState randomSituation(long seed, double pm) {
        java.util.Random rnd = new java.util.Random(seed);
        MatchState s = state(openBuildUp());
        List<Player> home = new ArrayList<>();
        for (Player p : s.getPlayers()) if (p.getTeam().equals("HOME")) home.add(p);
        Player carrier = home.get(rnd.nextInt(home.size()));
        // put him somewhere plausible (not his own goal / not off the pitch)
        carrier.setPosition(new Position(1.5 + rnd.nextDouble() * 5.5, 1.2 + rnd.nextDouble() * 4.6));

        // one away player presses him, sometimes two
        List<Player> away = new ArrayList<>();
        for (Player p : s.getPlayers()) if (p.getTeam().equals("AWAY")) away.add(p);
        int pressers = 1 + rnd.nextInt(3);
        for (int i = 0; i < pressers; i++) {
            Player o = away.get(rnd.nextInt(away.size()));
            o.setPosition(new Position(
                    Math.max(1.2, Math.min(7.8, carrier.getPosition().getRow() + (rnd.nextDouble() - 0.5) * 1.4)),
                    Math.max(1.2, Math.min(6.8, carrier.getPosition().getColumn() + (rnd.nextDouble() - 0.5) * 1.4))));
        }
        // rebuild the carrier with the swept playmaking
        int i = s.getPlayers().indexOf(carrier);
        Player c2 = new Player(carrier.getId(), carrier.getLabel(), carrier.getTeam(), carrier.getRole(),
                carrier.getPosition(), carrier.getAlternativePosition(),
                skillsWithPlaymaking(pm), 180);
        s.getPlayers().set(i, c2);
        s.setCarrier(c2);
        s.getBall().setPosition(c2.getPosition());
        s.getBall().stop();
        s.clearPassContext();
        s.setLastTouchTeam(c2.getTeam());
        return s;
    }

    /**
     * Measures, per playmaking skill, over many randomised situations:
     *   candidates — options that are not hard-vetoed (i.e. genuinely on the table)
     *   viable     — options with a non-negative score (what the selector may pick)
     *   tieable%   — situations whose top-2 margin is under 5, i.e. where the
     *                playmaking random is even reachable
     *   best%      — how often the top-scoring option is the one actually played
     */
    public static void playmakingSweep(int situations) {
        System.out.println();
        System.out.println("=".repeat(96));
        System.out.println("PLAYMAKING SWEEP — " + situations
                + " randomised situations per skill, identical positions per skill, all skills 14");
        System.out.println("=".repeat(96));
        System.out.printf("  %-4s %-11s %-8s %-10s %-9s %-9s %-30s%n",
                "PM", "candidates", "viable", "tieable%", "best%", "distinct", "most common pick");
        for (double pm : new double[]{1, 3, 5, 8, 11, 14, 17, 20}) {
            double candSum = 0, viableSum = 0;
            int tieable = 0, bestPicked = 0, n = 0;
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (long seed = 0; seed < situations; seed++) {
                MatchState s = randomSituation(seed, pm);
                SimulationRandom.seed(seed * 31 + 7);
                DecisionResult r = new CleanDecisionEngine().decideWithOptions(s);
                List<DecisionOption> all = r.getOptions();
                int cand = 0, viable = 0;
                for (DecisionOption o : all) {
                    if (o.getScore() > -9999) cand++;
                    if (o.getScore() >= 0) viable++;
                }
                candSum += cand; viableSum += viable;
                List<DecisionOption> v = all.stream().filter(o -> o.getScore() >= 0)
                        .sorted((a, b) -> Double.compare(b.getScore(), a.getScore())).toList();
                if (v.size() >= 2 && (v.get(0).getScore() - v.get(1).getScore()) < 5.0) tieable++;
                DecisionOption chosen = r.getChosen();
                DecisionOption best = v.isEmpty() ? chosen : v.get(0);
                boolean isBest = same(chosen, best);
                if (isBest) bestPicked++;
                counts.merge(chosen.getType() + (chosen.getTarget() != null ? ":" + chosen.getTarget().getId() : ""),
                        1, Integer::sum);
                n++;
            }
            String top = counts.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("-");
            System.out.printf("  %-4.0f %-11.2f %-8.2f %-10.1f %-9.1f %-9d %-30s%n",
                    pm, candSum / n, viableSum / n, 100.0 * tieable / n, 100.0 * bestPicked / n,
                    counts.size(), top + " " + String.format("(%.0f%%)", 100.0 * counts.getOrDefault(top, 0) / n));
        }
    }

    private static boolean same(DecisionOption a, DecisionOption b) {
        if (a.getType() != b.getType()) return false;
        if (a.getTarget() == null || b.getTarget() == null) return a.getTarget() == b.getTarget();
        return a.getTarget().getId().equals(b.getTarget().getId());
    }

    // ---------------------------------------------------------------- 3. the random

    /**
     * Restricted to the situations where the top two options are within 5 points —
     * the only place the playmaking random is allowed to speak. Reports how often the
     * pick deviates from the best option, per skill: a low-skill carrier should be
     * noticeably more random, a high-skill one noticeably more deterministic.
     */
    public static void randomResistance(int situations) {
        System.out.println();
        System.out.println("=".repeat(96));
        System.out.println("RANDOM RESISTANCE — only the close calls (top-2 margin < 5), "
                + situations + " situations per skill");
        System.out.println("=".repeat(96));
        System.out.printf("  %-4s %-12s %-14s %-14s%n", "PM", "tieable n", "deviate%", "expected (table)");
        for (double pm : new double[]{1, 5, 10, 14, 18, 20}) {
            int tieable = 0, deviate = 0;
            for (long seed = 0; seed < situations; seed++) {
                MatchState s = randomSituation(seed, pm);
                SimulationRandom.seed(seed * 31 + 7);
                DecisionResult r = new CleanDecisionEngine().decideWithOptions(s);
                List<DecisionOption> v = r.getOptions().stream().filter(o -> o.getScore() >= 0)
                        .sorted((a, b) -> Double.compare(b.getScore(), a.getScore())).toList();
                if (v.size() < 2) continue;
                if ((v.get(0).getScore() - v.get(1).getScore()) >= 5.0) continue;
                tieable++;
                if (!same(r.getChosen(), v.get(0))) deviate++;
            }
            double expected = 1.0 - playmakingAccuracy(pm);
            System.out.printf("  %-4.0f %-12d %-14.1f %-14.1f%n", pm, tieable,
                    tieable == 0 ? 0 : 100.0 * deviate / tieable, 100 * expected);
        }
    }

    /** The engine's own accuracy table, re-derived from the documented values. */
    private static double playmakingAccuracy(double pm) {
        int idx = (int) Math.max(1, Math.min(20, Math.round(pm)));
        return 0.40 + (idx - 1) * 0.02;
    }

    /**
     * Which action types actually get chosen, and how often. Also: how often an
     * option is SCORED as a real candidate but can never be selected, because
     * the selector is only handed PASS / DRIBBLE / SHOT / CLEAR.
     */
    public static void choiceDistribution(int situations) {
        System.out.println();
        System.out.println("=".repeat(96));
        System.out.println("CHOICE DISTRIBUTION — " + situations + " randomised situations, PM 14");
        System.out.println("=".repeat(96));
        Map<String, Integer> chosen = new LinkedHashMap<>();
        Map<String, Integer> scored = new LinkedHashMap<>();
        for (long seed = 0; seed < situations; seed++) {
            MatchState s = randomSituation(seed, 14);
            SimulationRandom.seed(seed * 31 + 7);
            DecisionResult r = new CleanDecisionEngine().decideWithOptions(s);
            chosen.merge(r.getChosen().getType().name(), 1, Integer::sum);
            for (DecisionOption o : r.getOptions()) {
                if (o.getScore() > -9999) scored.merge(o.getType().name(), 1, Integer::sum);
            }
        }
        System.out.println("  scored as a real candidate (not hard-vetoed)   chosen");
        for (String k : new String[]{"PASS", "DRIBBLE", "SHOT", "CLEAR", "THRU", "CROSS", "CENTER"}) {
            int sc = scored.getOrDefault(k, 0);
            int ch = chosen.getOrDefault(k, 0);
            System.out.printf("    %-8s %6d  (%5.1f%%)                        %5d  (%5.1f%%)%n",
                    k, sc, 100.0 * sc / situations, ch, 100.0 * ch / situations);
        }
    }

    // ---------------------------------------------------------------- 3b. gate audit

    /**
     * The frequency gates (SHOT / THRU / DELIVERY) roll a random and, when they
     * veto, the option becomes UNAVAILABLE — which leaves the selector with "pick
     * the least bad". This counts how often a player who was IN a shooting position
     * ends up dribbling because the shot was gated away.
     */
    public static void gateAudit(int situations) {
        System.out.println();
        System.out.println("=".repeat(96));
        System.out.println("GATE AUDIT — what happens when the frequency gate vetoes a shot");
        System.out.println("=".repeat(96));
        int inBox = 0, gated = 0, dribbledAnyway = 0, shotWhenOpen = 0;
        for (long seed = 0; seed < situations; seed++) {
            MatchState s = randomSituation(seed, 14);
            SimulationRandom.seed(seed * 31 + 7);
            DecisionResult r = new CleanDecisionEngine().decideWithOptions(s);
            DecisionOption shot = r.getOptions().stream()
                    .filter(o -> o.getType() == ActionType.SHOT).findFirst().orElse(null);
            DecisionOption pass = r.getOptions().stream()
                    .filter(o -> o.getType() == ActionType.PASS).findFirst().orElse(null);
            boolean eligible = shot != null && !shot.getReason().contains("not in zone");
            if (!eligible) continue;
            inBox++;
            if (shot.getScore() <= -9999) {                       // gated
                gated++;
                if (r.getChosen().getType() == ActionType.DRIBBLE) dribbledAnyway++;
            } else if (r.getChosen().getType() == ActionType.SHOT) {
                shotWhenOpen++;
            }
        }
        System.out.printf("  in a shooting position          : %d%n", inBox);
        System.out.printf("  shot vetoed by the freq gate    : %d  (%.0f%%)%n", gated, 100.0 * gated / Math.max(1, inBox));
        System.out.printf("  ... and DRIBBLED anyway         : %d  (%.0f%% of gated)%n",
                dribbledAnyway, 100.0 * dribbledAnyway / Math.max(1, gated));
        System.out.printf("  shot taken when gate allowed    : %d  (%.0f%% of eligible)%n",
                shotWhenOpen, 100.0 * shotWhenOpen / Math.max(1, inBox));
    }

    // ---------------------------------------------------------------- 4. receiver audit

    /**
     * For the chosen pass, list every teammate with the sub-factors that decide the
     * pass, so a better target can be spotted by eye. Only pass-relevant players.
     */
    public static void receiverAudit(Scenario sc) {
        System.out.println();
        System.out.println("=".repeat(96));
        System.out.println("RECEIVER AUDIT  —  every teammate as a pass target, scenario " + sc.name());
        System.out.println("=".repeat(96));
        MatchState s = state(sc);
        Player carrier = s.getCarrier();
        System.out.println("  carrier " + carrier.getId() + " at " + pos(carrier.getPosition())
                + "   attacking " + ("HOME".equals(carrier.getTeam()) ? "toward row 8.0" : "toward row 1.0"));
        DecisionOption passProbe = new CleanDecisionEngine().decideWithOptions(s).getOptions().stream()
                .filter(o -> o.getType() == ActionType.PASS).findFirst().orElse(null);
        Player chosenTarget = passProbe != null ? passProbe.getTarget() : null;
        System.out.printf("  %-10s %-6s %-7s %-9s %-9s%n",
                "player", "dist", "fwd", "openness", "pressure");
        for (Player t : s.getPlayers()) {
            if (!t.getTeam().equals(carrier.getTeam())) continue;   // teammates only
            if (t.getId().equals(carrier.getId())) continue;
            if (t.getRole().equals("GK")) continue;                 // never a passing target
            double dist = Math.hypot(t.getPosition().getRow() - carrier.getPosition().getRow(),
                    t.getPosition().getColumn() - carrier.getPosition().getColumn());
            boolean home = "HOME".equals(carrier.getTeam());
            double fwd = home ? t.getPosition().getRow() - carrier.getPosition().getRow()
                    : carrier.getPosition().getRow() - t.getPosition().getRow();
            // openness = distance to nearest opponent
            double open = Double.MAX_VALUE;
            for (Player o : s.getPlayers()) {
                if (o.getTeam().equals(carrier.getTeam())) continue;
                open = Math.min(open, Math.hypot(o.getPosition().getRow() - t.getPosition().getRow(),
                        o.getPosition().getColumn() - t.getPosition().getColumn()));
            }
            if (open == Double.MAX_VALUE) open = 99;
            double press = 0;
            for (Player o : s.getPlayers()) {
                if (o.getTeam().equals(carrier.getTeam())) continue;
                double d = Math.hypot(o.getPosition().getRow() - t.getPosition().getRow(),
                        o.getPosition().getColumn() - t.getPosition().getColumn());
                press += Math.max(0, 1.0 - d);
            }
            String mark = (chosenTarget != null && chosenTarget.getId().equals(t.getId())) ? "  <== engine picked" : "";
            System.out.printf("  %-10s %-6.2f %-7.1f %-9.2f %-9.2f%s%n",
                    t.getId(), dist, fwd, open, press, mark);
        }
        CleanDecisionEngine e = new CleanDecisionEngine();
        DecisionOption pass = e.decideWithOptions(s).getOptions().stream()
                .filter(o -> o.getType() == ActionType.PASS).findFirst().orElse(null);
        if (pass != null) {
            System.out.println("  engine picked: "
                    + (pass.getTarget() != null ? pass.getTarget().getId() : "none")
                    + "   score " + String.format("%.1f", pass.getScore()));
            System.out.println("  reason: " + pass.getReason());
        }
    }

    /**
     * The goal-proximity term. The engine computes the DISTANCE to the opponent's
     * goal line and then ADDS it as if it were a proximity, so a bigger number means
     * "further from goal" and the caller rewards exactly that. Shown side by side:
     * the bonus the engine gives today, and the bonus a sign-correct version would
     * give, plus which receiver each one elects.
     */
    public static void proximitySignCheck() {
        System.out.println();
        System.out.println("=".repeat(96));
        System.out.println("PROXIMITY SIGN CHECK — the pass/carry 'prox' term (weight 3.5 on passes)");
        System.out.println("=".repeat(96));
        Scenario sc = pressedInOwnThird();
        MatchState s = state(sc);
        Player carrier = s.getCarrier();
        System.out.println("  carrier " + carrier.getId() + " at " + pos(carrier.getPosition())
                + "  (HOME attacks row 8.0)");
        System.out.println();
        System.out.printf("  %-9s %-6s %-13s %-15s %-8s%n",
                "receiver", "row", "prox TODAY", "prox CORRECT", "delta");
        String bestToday = null, bestCorrect = null;
        double bestTodayV = -99, bestCorrectV = -99;
        for (Player t : s.getPlayers()) {
            if (!t.getTeam().equals("HOME") || t.getId().equals(carrier.getId()) || t.getRole().equals("GK")) continue;
            double r = t.getPosition().getRow();
            double today = (8.0 - r) * 3.5;                 // distance, rewarded
            double correct = (r - 4.5) * 3.5;               // 0 at halfway, +12 in the box
            System.out.printf("  %-9s %-6.1f %-13.1f %-15.1f %-8.1f%n", t.getId(), r, today, correct, correct - today);
            if (today > bestTodayV) { bestTodayV = today; bestToday = t.getId(); }
            if (correct > bestCorrectV) { bestCorrectV = correct; bestCorrect = t.getId(); }
        }
        System.out.println();
        System.out.println("  most rewarded by the term as written : " + bestToday
                + "  (row " + rowOf(s, bestToday) + ", deep in his own half)");
        System.out.println("  most rewarded by a correct term    : " + bestCorrect
                + "  (row " + rowOf(s, bestCorrect) + ", high up the pitch)");
    }

    private static String rowOf(MatchState s, String id) {
        for (Player p : s.getPlayers()) if (p.getId().equals(id)) return String.format("%.1f", p.getPosition().getRow());
        return "?";
    }

    public static void main(String[] args) {
        System.out.println("DECISION ENGINE PROBE — all players skill 14 unless stated");
        System.out.println("pitch: rows 1.0 (HOME goal) .. 8.0 (AWAY goal), cols 1.0 .. 7.0");

        Scenario[] scenarios = {openBuildUp(), pressedInOwnThird(), inTheBox(), twoReceivers()};
        for (Scenario sc : scenarios) dumpScenario(sc);

        playmakingSweep(600);
        randomResistance(600);
        gateAudit(600);
        choiceDistribution(600);
        receiverAudit(twoReceivers());
        receiverAudit(pressedInOwnThird());
        proximitySignCheck();
    }
}
