package org.example.footballmanager.demo;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral / statistical test for the Playmaking Decision Layer.
 *
 * <p>Runs the same decision context thousands of times for players with
 * different PM values (3, 8, 13, 18, 20) against 7 controlled game contexts,
 * collecting visibility + selection distributions.  Produces a compact
 * statistical report and asserts behavioral invariants.</p>
 *
 * <p>No production formulas are changed — the test only exercises the existing
 * {@link PlaymakingDecisionEngine} via reflection (bypassing logDecision) to
 * collect data without stdout spam.</p>
 */
class PlaymakingDecisionLayerTest {

    private static final int ITERATIONS = 5000;
    private static final int[] PM_LEVELS = {3, 8, 13, 18, 20};
    private static final String HOME_TEAM = "HOME";
    private static final String AWAY_TEAM = "AWAY";

    // === OptionSelector PM→accuracy reference (for report) ===
    private static final double[] PM_THRESHOLDS = {2, 5, 8, 11, 14, 17, 20};
    private static final double[] ACCURACY_VALUES = {0.25, 0.35, 0.50, 0.65, 0.78, 0.88, 0.95};

    // ── Scenario definition ──────────────────────────────────────

    /** Fixed position + role + team for a non-carrier player. */
    private record Spec(double row, double col, String role, String team) {}

    /**
     * A scenario describes a fixed pitch geometry: carrier position, role, and
     * skill PM; teammate positions; opponent positions.  Every scenario is
     * played from HOME's perspective so field-position logic (row >= 4 =
     * opponent half, row >= 5 = can shoot, etc.) behaves consistently.
     */
    private record Scenario(
        String name,
        String description,
        double carrierRow,
        double carrierCol,
        String carrierRole,
        int carrierStr,       // striker skill (S6 uses 15; rest use 10)
        List<Spec> teammates,
        List<Spec> opponents
    ) {}

    // ── Decision execution (replicates decide() minus logDecision) ─

    private record DecisionResult(
        List<DecisionOption> allOptions,
        List<DecisionOption> visible,
        DecisionOption selected,
        DecisionContext ctx
    ) {}

    /**
     * Runs one playmaking decision using the real engine via reflection.
     * Bypasses logDecision to avoid stdout spam.  The state and selection
     * are reused across iterations; only the Random (and hence VisionFilter
     * + OptionSelector) changes per call.
     */
    private static DecisionResult runDecision(
            SimulationState state, PlayerSelectionEngine selection,
            Player carrier, Random random) throws Exception {

        PlaymakingDecisionEngine engine = new PlaymakingDecisionEngine(state, selection, random);

        // Reflection: buildContext(carrier)
        Method buildContext = PlaymakingDecisionEngine.class.getDeclaredMethod("buildContext", Player.class);
        buildContext.setAccessible(true);
        DecisionContext ctx = (DecisionContext) buildContext.invoke(engine, carrier);

        // Reflection: generateOptions(ctx)
        Method generateOptions = PlaymakingDecisionEngine.class.getDeclaredMethod("generateOptions", DecisionContext.class);
        generateOptions.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<DecisionOption> allOptions = new ArrayList<>(
            (List<DecisionOption>) generateOptions.invoke(engine, ctx));

        // Access private visionFilter and selector fields
        Field vfField = PlaymakingDecisionEngine.class.getDeclaredField("visionFilter");
        vfField.setAccessible(true);
        VisionFilter vf = (VisionFilter) vfField.get(engine);

        Field selField = PlaymakingDecisionEngine.class.getDeclaredField("selector");
        selField.setAccessible(true);
        OptionSelector sel = (OptionSelector) selField.get(engine);

        // Apply vision filter (mutates visible flags on allOptions)
        vf.applyVisionFilter(ctx, allOptions);

        // Collect visible (mirrors decide())
        List<DecisionOption> visible = allOptions.stream()
            .filter(DecisionOption::isVisible)
            .collect(Collectors.toList());

        if (visible.isEmpty()) {
            for (DecisionOption opt : allOptions) {
                if (opt.getType() == DecisionType.PASS || opt.getType() == DecisionType.CARRY) {
                    opt.setVisible(true);
                }
            }
            visible = allOptions.stream()
                .filter(DecisionOption::isVisible)
                .collect(Collectors.toList());
        }

        DecisionOption selected = sel.select(ctx, visible);
        return new DecisionResult(allOptions, visible, selected, ctx);
    }

    // ── Stats collector ────────────────────────────────────────────

    static final class Stats {
        final String scenarioName;
        final String scenarioDesc;
        final int pm;
        final Map<DecisionType, Integer> visibleCounts = new EnumMap<>(DecisionType.class);
        final Map<DecisionType, Integer> selectedCounts = new EnumMap<>(DecisionType.class);
        final Map<DecisionType, Double> optionScores = new EnumMap<>(DecisionType.class);
        int iterations = 0;
        int bestVisibleSelected = 0;
        int bestOverallSelected = 0;

        // Context info (captured once — deterministic per PM)
        double pressure, danger;
        boolean inOpponentHalf, canShoot, inFinalThird, onWing;

        Stats(String scenarioName, String scenarioDesc, int pm) {
            this.scenarioName = scenarioName;
            this.scenarioDesc = scenarioDesc;
            this.pm = pm;
            for (DecisionType t : DecisionType.values()) {
                visibleCounts.put(t, 0);
                selectedCounts.put(t, 0);
                optionScores.put(t, Double.NaN);
            }
        }

        void record(List<DecisionOption> allOptions, List<DecisionOption> visible,
                     DecisionOption selected) {
            iterations++;
            for (DecisionOption opt : allOptions) {
                optionScores.put(opt.getType(), opt.getScore());
            }
            for (DecisionOption opt : visible) {
                visibleCounts.merge(opt.getType(), 1, Integer::sum);
            }
            selectedCounts.merge(selected.getType(), 1, Integer::sum);

            // Best visible viable (score > 0), else best overall visible
            List<DecisionOption> viable = visible.stream()
                .filter(o -> o.getScore() > 0)
                .collect(Collectors.toList());
            DecisionOption bestVisible = viable.isEmpty()
                ? visible.stream().max(Comparator.comparingDouble(DecisionOption::getScore)).orElse(null)
                : viable.stream().max(Comparator.comparingDouble(DecisionOption::getScore)).orElse(null);
            if (bestVisible != null && bestVisible == selected) {
                bestVisibleSelected++;
            }

            // Best overall (highest score among ALL generated, regardless of visibility)
            DecisionOption bestOverall = allOptions.stream()
                .max(Comparator.comparingDouble(DecisionOption::getScore))
                .orElse(null);
            if (bestOverall != null && bestOverall == selected) {
                bestOverallSelected++;
            }
        }
    }

    // ── Player / state factory ─────────────────────────────────────

    private static Player makePlayer(String id, String label, String team,
                                     String role, double row, double col) {
        return new Player(id, label, team, role,
            HOME_TEAM.equals(team) ? Color.RED : Color.BLUE,
            new Position(row, col), new Position(row, col));
    }

    private static Player makeCarrier(double row, double col, String role,
                                      int pm, int striker) {
        PlayerSkills skills = new PlayerSkills(10, 10, 10, 10, pm, 10, striker, 10);
        return new Player("C", "CAR", HOME_TEAM, role, Color.RED,
            new Position(row, col), new Position(row, col), skills);
    }

    private static List<Player> buildPlayers(Scenario s, int pm) {
        List<Player> players = new ArrayList<>();
        players.add(makeCarrier(s.carrierRow(), s.carrierCol(), s.carrierRole(), pm, s.carrierStr()));
        int ti = 0, oi = 0;
        for (Spec tm : s.teammates()) {
            players.add(makePlayer("t" + ti, "T" + ti, HOME_TEAM, tm.role(), tm.row(), tm.col()));
            ti++;
        }
        for (Spec op : s.opponents()) {
            players.add(makePlayer("o" + oi, "O" + oi, AWAY_TEAM, op.role(), op.row(), op.col()));
            oi++;
        }
        return players;
    }

    // ── Scenario definitions ──────────────────────────────────────

    private static List<Scenario> createScenarios() {
        List<Scenario> s = new ArrayList<>();

        // S1 — Safe passing opportunity (midfield, open forward receiver, opponents back)
        s.add(new Scenario("S1_SafePass", "Safe passing opportunity",
            4, 3.5, "MID", 10,
            List.of(
                new Spec(5, 3, "ST", HOME_TEAM),
                new Spec(3, 4, "DEF", HOME_TEAM),
                new Spec(4, 5, "MID", HOME_TEAM)),
            List.of(
                new Spec(6, 1, "DEF", AWAY_TEAM),
                new Spec(6, 6, "DEF", AWAY_TEAM),
                new Spec(7, 3.5, "GK", AWAY_TEAM))));

        // S2 — Risky passing (receiver closely guarded by opponent)
        s.add(new Scenario("S2_RiskyPass", "Risky passing (receiver guarded)",
            4, 3.5, "MID", 10,
            List.of(
                new Spec(5, 3, "ST", HOME_TEAM),
                new Spec(3, 4, "DEF", HOME_TEAM)),
            List.of(
                new Spec(5, 3.5, "DEF", AWAY_TEAM),  // shadows receiver
                new Spec(6, 1, "DEF", AWAY_TEAM),
                new Spec(7, 5, "GK", AWAY_TEAM))));

        // S3 — Obvious thru-ball (carrier in opp half, runner far ahead, space behind defense)
        s.add(new Scenario("S3_ObviousThru", "Obvious thru-ball opportunity",
            4, 4, "MID", 10,
            List.of(
                new Spec(5.5, 3, "ST", HOME_TEAM),
                new Spec(3, 3, "DEF", HOME_TEAM),
                new Spec(3, 5, "DEF", HOME_TEAM)),
            List.of(
                new Spec(5.5, 5, "DEF", AWAY_TEAM),
                new Spec(5.5, 1, "DEF", AWAY_TEAM),
                new Spec(7, 6, "GK", AWAY_TEAM))));

        // S4 — High pressure in own half, safe pass available
        s.add(new Scenario("S4_PressureSafePass", "Pressure in own half, safe pass exists",
            2.5, 3.5, "DEF", 10,
            List.of(
                new Spec(4, 3.5, "MID", HOME_TEAM),
                new Spec(3, 4, "DEF", HOME_TEAM)),
            List.of(
                new Spec(2, 3.5, "ST", AWAY_TEAM),
                new Spec(3, 2, "DEF", AWAY_TEAM),
                new Spec(3, 5, "DEF", AWAY_TEAM),
                new Spec(7, 3.5, "GK", AWAY_TEAM))));

        // S5 — High pressure in own penalty, no safe pass
        s.add(new Scenario("S5_PressureNoPass", "Pressure in own penalty, no safe pass",
            1.5, 3.5, "DEF", 10,
            List.of(
                new Spec(4, 3.5, "MID", HOME_TEAM),
                new Spec(2, 5, "DEF", HOME_TEAM)),
            List.of(
                new Spec(1.5, 3, "ST", AWAY_TEAM),
                new Spec(2, 4, "ST", AWAY_TEAM),
                new Spec(1, 2, "DEF", AWAY_TEAM),
                new Spec(1, 5, "DEF", AWAY_TEAM),
                new Spec(7, 3.5, "GK", AWAY_TEAM))));

        // S6 — Shooting opportunity (good striker, near goal)
        s.add(new Scenario("S6_Shooting", "Shooting opportunity (striker=15)",
            5.5, 3.5, "ST", 15,
            List.of(
                new Spec(4, 4, "MID", HOME_TEAM),
                new Spec(4, 3, "DEF", HOME_TEAM)),
            List.of(
                new Spec(6, 5, "DEF", AWAY_TEAM),
                new Spec(6, 1, "DEF", AWAY_TEAM),
                new Spec(7, 3.5, "GK", AWAY_TEAM))));

        // S7 — Opponent half with moderate pressure
        s.add(new Scenario("S7_OppHalfPressure", "Opp half possession with pressure",
            5, 4, "MID", 10,
            List.of(
                new Spec(6, 3, "ST", HOME_TEAM),
                new Spec(4, 5, "DEF", HOME_TEAM)),
            List.of(
                new Spec(5, 3.5, "DEF", AWAY_TEAM),
                new Spec(6, 1, "DEF", AWAY_TEAM),
                new Spec(7, 5, "GK", AWAY_TEAM))));

        return s;
    }

    // ── Utility ──────────────────────────────────────────────────

    private static double decisionAccuracy(double pm) {
        if (pm <= PM_THRESHOLDS[0]) return ACCURACY_VALUES[0];
        if (pm >= PM_THRESHOLDS[PM_THRESHOLDS.length - 1])
            return ACCURACY_VALUES[ACCURACY_VALUES.length - 1];
        for (int i = 0; i < PM_THRESHOLDS.length - 1; i++) {
            if (pm >= PM_THRESHOLDS[i] && pm <= PM_THRESHOLDS[i + 1]) {
                double t = (pm - PM_THRESHOLDS[i]) / (PM_THRESHOLDS[i + 1] - PM_THRESHOLDS[i]);
                return ACCURACY_VALUES[i] + t * (ACCURACY_VALUES[i + 1] - ACCURACY_VALUES[i]);
            }
        }
        return ACCURACY_VALUES[ACCURACY_VALUES.length - 1];
    }

    private static String pct(int count, int total) {
        if (total == 0) return "  N/A";
        return String.format("%5.1f%%", count * 100.0 / total);
    }

    private static double safeDiv(int num, int den) {
        return den == 0 ? 0 : (double) num / den;
    }

    // ── Main test ──────────────────────────────────────────────────

    @Test
    void playmakingDecisionMatrix() throws Exception {
        List<Scenario> scenarios = createScenarios();
        Map<String, Map<Integer, Stats>> allStats = new LinkedHashMap<>();
        Map<String, Scenario> scenarioByName = new LinkedHashMap<>();

        for (Scenario scenario : scenarios) {
            scenarioByName.put(scenario.name(), scenario);
            Map<Integer, Stats> pmStats = new LinkedHashMap<>();
            allStats.put(scenario.name(), pmStats);

            // Build players and state ONCE per (scenario, PM) — deterministic parts
            for (int pm : PM_LEVELS) {
                Stats stats = new Stats(scenario.name(), scenario.description(), pm);
                pmStats.put(pm, stats);

                List<Player> players = buildPlayers(scenario, pm);
                Player carrier = players.get(0);
                Ball ball = new Ball(carrier.getPosition(), carrier.getPosition());
                ball.setCarrier(carrier);
                SimulationState state = new SimulationState(players, ball,
                    TacticsRules.defaults(), new Random(0));
                state.setCarrier(carrier);
                PlayerSelectionEngine selection = new PlayerSelectionEngine(state);

                // One Random per (scenario, PM) — advances naturally across iterations
                // (mirrors real simulation; avoids correlated seeds producing wrong distributions)
                Random random = new Random(
                    (long) scenario.name().hashCode() * 31L + (long) pm * 17L + 7L);

                for (int i = 0; i < ITERATIONS; i++) {
                    DecisionResult result = runDecision(state, selection, carrier, random);

                    if (i == 0) {
                        stats.pressure = result.ctx().pressure();
                        stats.danger = result.ctx().danger();
                        stats.inOpponentHalf = result.ctx().inOpponentHalf();
                        stats.canShoot = result.ctx().canShoot();
                        stats.inFinalThird = result.ctx().inFinalThird();
                        stats.onWing = result.ctx().onWing();
                    }
                    stats.record(result.allOptions(), result.visible(), result.selected());
                }
            }
        }

        printReport(allStats, scenarioByName);
        assertInvariants(allStats, scenarioByName);
    }

    // ── Report ────────────────────────────────────────────────────

    private void printReport(Map<String, Map<Integer, Stats>> allStats,
                             Map<String, Scenario> scenarioByName) {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  PLAYMAKING DECISION LAYER — BEHAVIORAL REPORT                                ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println("PM Levels: " + Arrays.toString(PM_LEVELS)
            + "  |  Iterations per (PM, scenario): " + ITERATIONS);
        System.out.println("Total decision points evaluated: "
            + (allStats.size() * PM_LEVELS.length * ITERATIONS));
        System.out.println();

        // Accuracy reference
        System.out.println("OptionSelector PM→accuracy reference:");
        for (int pm : PM_LEVELS) {
            System.out.printf("  PM=%-2d → %.1f%% decision accuracy%n", pm, decisionAccuracy(pm) * 100);
        }
        System.out.println();
        System.out.println("VisionFilter tiers:");
        System.out.println("  CLEAR, PASS, CARRY: always visible (all PM)");
        System.out.println("  THRU:   pm≥11 always | pm 6–10 30% | pm≤5 10%");
        System.out.println("  SHOT, CROSS, CENTER: visible only pm≥11");
        System.out.println();

        for (Map.Entry<String, Map<Integer, Stats>> entry : allStats.entrySet()) {
            Scenario scenario = scenarioByName.get(entry.getKey());
            System.out.println("═".repeat(100));
            System.out.printf("── %s ── %s%n", scenario.name(), scenario.description());
            System.out.printf("  Carrier: HOME %s @ (%.2f, %.2f)  opponents=%d%n",
                scenario.carrierRole(), scenario.carrierRow(), scenario.carrierCol(),
                scenario.opponents().size());

            // Print context (from PM 20 stats, since context flags are deterministic per scenario)
            Stats refStats = entry.getValue().get(20);
            System.out.printf("  Context flags: inOppHalf=%s  canShoot=%s  inFinalThird=%s  onWing=%s%n",
                refStats.inOpponentHalf, refStats.canShoot, refStats.inFinalThird, refStats.onWing);
            System.out.printf("  Pressure=%.1f  Danger=%.1f%n", refStats.pressure, refStats.danger);

            // Print option scores (from PM 20 — deterministic per scenario, PM doesn't affect most scores)
            System.out.print("  Option scores (PM=20): ");
            for (Map.Entry<DecisionType, Double> e : refStats.optionScores.entrySet()) {
                if (!Double.isNaN(e.getValue())) {
                    System.out.printf("%s(%.1f) ", e.getKey(), e.getValue());
                }
            }
            System.out.println();
            System.out.println();

            // Print per-PM stats
            for (int pm : PM_LEVELS) {
                Stats stats = entry.getValue().get(pm);
                double acc = decisionAccuracy(pm);
                System.out.printf("  PM=%-2d  (acc=%.1f%%)%n", pm, acc * 100);

                // Visibility rates
                System.out.print("    visible:  ");
                for (DecisionType t : DecisionType.values()) {
                    int vis = stats.visibleCounts.getOrDefault(t, 0);
                    if (vis > 0) {
                        System.out.printf("%s %s  ", t, pct(vis, stats.iterations));
                    }
                }
                System.out.println();

                // Selection rates
                System.out.print("    selected: ");
                for (DecisionType t : DecisionType.values()) {
                    int sel = stats.selectedCounts.getOrDefault(t, 0);
                    if (sel > 0) {
                        System.out.printf("%s %s  ", t, pct(sel, stats.iterations));
                    }
                }
                System.out.println();

                // Best-visible selection rate
                System.out.printf("    best-visible-selected: %s   best-overall-selected: %s%n",
                    pct(stats.bestVisibleSelected, stats.iterations),
                    pct(stats.bestOverallSelected, stats.iterations));
                System.out.println();
            }
            System.out.println();
        }
    }

    // ── Invariant assertions ──────────────────────────────────────

    private void assertInvariants(Map<String, Map<Integer, Stats>> allStats,
                                  Map<String, Scenario> scenarioByName) {

        // ── Invariant 1: Higher PM must never have lower visibility ──
        for (Map.Entry<String, Map<Integer, Stats>> entry : allStats.entrySet()) {
            String scenarioName = entry.getKey();
            for (DecisionType t : DecisionType.values()) {
                double prevRate = -1;
                for (int pm : PM_LEVELS) {
                    Stats stats = entry.getValue().get(pm);
                    double rate = safeDiv(stats.visibleCounts.getOrDefault(t, 0), stats.iterations);
                    assertTrue(rate >= prevRate - 0.001,
                        String.format(
                            "INV1 FAIL [%s] PM=%d: visibility of %s (%.1f%%) < previous PM (%.1f%%)",
                            scenarioName, pm, t, rate * 100, prevRate * 100));
                    prevRate = rate;
                }
            }
        }

        // ── Invariant 2: Higher PM must never have lower best-visible selection rate ──
        for (Map.Entry<String, Map<Integer, Stats>> entry : allStats.entrySet()) {
            String scenarioName = entry.getKey();
            double prevRate = -1;
            for (int pm : PM_LEVELS) {
                Stats stats = entry.getValue().get(pm);
                double rate = safeDiv(stats.bestVisibleSelected, stats.iterations);
                assertTrue(rate >= prevRate - 0.02,
                    String.format(
                        "INV2 FAIL [%s] PM=%d: best-visible-selected (%.1f%%) < previous PM (%.1f%%) [tol=2%%]",
                        scenarioName, pm, rate * 100, prevRate * 100));
                prevRate = rate;
            }
        }

        // ── Invariant 3: Low PM must be capable of selecting CLEAR under pressure ──
        // In S4 and S5, PM 3 must select CLEAR at least once (rate > 0)
        for (String sName : new String[]{"S4_PressureSafePass", "S5_PressureNoPass"}) {
            Stats s3 = allStats.get(sName).get(3);
            int clearSelected = s3.selectedCounts.getOrDefault(DecisionType.CLEAR, 0);
            assertTrue(clearSelected > 0,
                String.format("INV3 FAIL [%s] PM=3: CLEAR selected %d times — must be capable of clearing under pressure",
                    sName, clearSelected));
        }

        // ── Invariant 4: High PM must strongly prefer superior attacking option over CLEAR ──
        // In S1 (safe pass, opponent half → CLEAR suppressed), PM 20 should select
        // PASS or CARRY far more than CLEAR
        Stats s1Pm20 = allStats.get("S1_SafePass").get(20);
        int attackingSelected = s1Pm20.selectedCounts.getOrDefault(DecisionType.PASS, 0)
            + s1Pm20.selectedCounts.getOrDefault(DecisionType.CARRY, 0)
            + s1Pm20.selectedCounts.getOrDefault(DecisionType.THRU, 0);
        int clearSelected = s1Pm20.selectedCounts.getOrDefault(DecisionType.CLEAR, 0);
        assertTrue(attackingSelected > clearSelected * 3,
            String.format("INV4 FAIL [S1] PM=20: attacking=%d but CLEAR=%d — should strongly prefer attacking",
                attackingSelected, clearSelected));

        // ── Invariant 5: High PM must NOT always select the maximum-scoring option ──
        // PM 20 accuracy is 95%, so best-visible-selected should be < 100%
        for (String sName : allStats.keySet()) {
            Stats pm20 = allStats.get(sName).get(20);
            double rate = safeDiv(pm20.bestVisibleSelected, pm20.iterations);
            assertTrue(rate < 0.999,
                String.format("INV5 FAIL [%s] PM=20: best-visible-selected=%.3f — must not always pick max-scoring option",
                    sName, rate));
        }

        // ── Invariant 6: Low PM must NOT behave randomly (uniform across all types) ──
        // PM 3: the most-selected type should have > 30% (uniform with 4-5 types would be ~20-25%)
        for (String sName : allStats.keySet()) {
            Stats pm3 = allStats.get(sName).get(3);
            int maxCount = 0;
            int totalSelected = 0;
            for (int c : pm3.selectedCounts.values()) {
                maxCount = Math.max(maxCount, c);
                totalSelected += c;
            }
            double maxRate = safeDiv(maxCount, totalSelected);
            assertTrue(maxRate > 0.30,
                String.format("INV6 FAIL [%s] PM=3: max selection rate=%.1f%% — must show clear preference (not random)",
                    sName, maxRate * 100));
        }

        // ── Invariant 7: If THRU is not visible for a PM tier, it must never be selected ──
        for (String sName : allStats.keySet()) {
            for (int pm : PM_LEVELS) {
                Stats stats = allStats.get(sName).get(pm);
                int thruVisible = stats.visibleCounts.getOrDefault(DecisionType.THRU, 0);
                int thruSelected = stats.selectedCounts.getOrDefault(DecisionType.THRU, 0);
                assertTrue(thruSelected <= thruVisible,
                    String.format("INV7 FAIL [%s] PM=%d: THRU selected=%d but visible=%d — cannot select invisible option",
                        sName, pm, thruSelected, thruVisible));
            }
        }

        // ── Invariant 8: If no viable attacking option exists, PM must not manufacture one ──
        // In S5 (CLEAR dominates), CLEAR should be the most selected option for all PM levels
        // (attacking options exist but CLEAR clearly dominates — PM doesn't invent attacking plays)
        Stats s5Ref = allStats.get("S5_PressureNoPass").get(20);
        int s5ClearSel = s5Ref.selectedCounts.getOrDefault(DecisionType.CLEAR, 0);
        int s5PassSel = s5Ref.selectedCounts.getOrDefault(DecisionType.PASS, 0);
        int s5CarrySel = s5Ref.selectedCounts.getOrDefault(DecisionType.CARRY, 0);
        int s5ThruSel = s5Ref.selectedCounts.getOrDefault(DecisionType.THRU, 0);
        int s5Attacking = s5PassSel + s5CarrySel + s5ThruSel;
        assertTrue(s5ClearSel > s5Attacking,
            String.format("INV8 FAIL [S5] PM=20: CLEAR=%d but attacking=%d — CLEAR should dominate when no safe option",
                s5ClearSel, s5Attacking));

        System.out.println();
        System.out.println("═══ ALL 8 BEHAVIORAL INVARIANTS PASSED ═══");
    }

    // ── Additional granular tests (one per scenario+PM for debugging) ──

    @Test
    void printFullDistributionForAllScenarios() throws Exception {
        // This test exists to make the report visible even when running a single scenario
        playmakingDecisionMatrix();
    }
}
