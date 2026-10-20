package org.example.footballmanager.newLogic.sim.probe;

import org.example.footballmanager.newLogic.sim.engine.DuelEngine;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does a good decision survive the execution? (owner, 2026-10-18)
 *
 * <h2>The question</h2>
 *
 * <p>Everything measured so far has asked whether the engine <em>chooses</em> well. This asks what
 * happens next, and the two have different owners: if the engine picks the right option and the ball
 * still does not arrive, the fix is in {@code ExecutionQuality}, {@code ActionExecutor} or
 * {@code DuelEngine} — and retuning the decision engine would achieve nothing but a different set of
 * wrong decisions.
 *
 * <p>This matters now because the decision engine has been shown to be choosing well. Measured over 40
 * matches: 97.8% of shots were the highest-scoring option available, winning by a mean of 21 points. So
 * the live question is no longer "does he shoot" but "does the shot do anything".
 *
 * <h2>How a decision is matched to its outcome</h2>
 *
 * <p>The two observers fire in a known order inside one tick — the decision observer at pipeline step 6,
 * the tick observer after the Movement Engine at step 8b — so a decision made on tick T has had zero,
 * one and then two ticks of consequence by the time the tick observer next sees it.
 *
 * <p>Each decision is therefore held open for {@link #WINDOW} ticks and then classified from the live
 * state, which says what happened unambiguously:
 *
 * <ul>
 *   <li><b>Goal</b> — the scoreline moved between the two samples. Nothing else can be mistaken for it.</li>
 *   <li><b>Completed</b> — the intended receiver is the carrier.</li>
 *   <li><b>Intercepted</b> — an opponent has the ball.</li>
 *   <li><b>Misdirected</b> — a team-mate we did not mean to find has it.</li>
 *   <li><b>Gathered by the keeper</b> — the goalkeeper is the carrier.</li>
 *   <li><b>Blocked for a corner</b> / <b>off target</b> — read from the restart that followed.</li>
 * </ul>
 *
 * <p><b>Known weakness, stated rather than hidden.</b> The engine may re-aim the pass at the moment it
 * is struck, in which case the deviation this measures is not quite the deviation used. If the
 * misdirect rate comes out near zero, that is the first thing to check, not a good result.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   mvn -q -o -DskipTests exec:java \
 *     -Dexec.mainClass=org.example.footballmanager.newLogic.sim.probe.DecisionExecutionDiag \
 *     -Dexec.args="[ticksPerMatch] [matches] [baseSeed]"
 * </pre>
 */
public final class DecisionExecutionDiag {

    private static final double SKILL = 14.0;

    /** Ticks a decision is held open for, per action.
     *
     *  <p>These differ because the actions resolve at different speeds, and pooling them over one
     *  window produced a number that meant nothing: a dribble is one step of a possession, so ten
     *  ticks later the answer is "whatever happened to the possession", not "did the dribble work".
     *  The first version of this report pooled all seven types and reported 45% "ball still loose",
     *  which is not a finding about passing and never was.
     */
    private static final int DRIBBLE_WINDOW = 1;
    private static final int PASS_WINDOW = 10;
    private static final int SHOT_WINDOW = 12;

    private static final double UNAVAILABLE = -10_000.0;

    private DecisionExecutionDiag() {
    }

    private static int tick;
    private static int decisions;

    private static final class Pending {
        String team;
        ActionType type;
        String targetId;
        int decidedAtTick;
        int goalsAtDecision;
        boolean wasTopScore;
        double margin;
        /** For a dribble: how many opponents were close enough to contest it. Zero means nothing
         *  challenged him, which by the owner's definition makes it a carry and not a dribble. */
        int opponentsInRange;
    }

    private static final List<Pending> pending = new ArrayList<>();
    /** "ACTION|outcome" -> count. Kept per action because the actions have nothing in common: a
     *  cross and a clearance can only be judged against "did it arrive", a shot against "did it
     *  beat the keeper", a dribble against "did he keep it for one more tick". */
    private static final Map<String, int[]> byAction = new LinkedHashMap<>();

    private static int topScoreDecisions, decisiveDecisions;

    /** Real duels, taken from the match stats rather than inferred from who had the ball next
     *  tick. An inference cannot tell a duel that never fired from one the carrier won. */
    private static int matchesRun;
    private static int duels, duelsLostByCarrier;

    private static void bump(String action, String outcome) {
        byAction.computeIfAbsent(action + "|" + outcome, k -> new int[1])[0]++;
    }

    private static int totalFor(String action) {
        int t = 0;
        for (Map.Entry<String, int[]> e : byAction.entrySet()) {
            if (e.getKey().startsWith(action + "|")) t += e.getValue()[0];
        }
        return t;
    }

    public static void main(String[] args) {
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 400;
        int matches = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        int baseSeed = args.length > 2 ? Integer.parseInt(args[2]) : 42;

        var loaded = RealTacticsHarness.loadWorldDefault();
        System.out.printf("=== DECISION -> EXECUTION · %d matches x %d ticks · seed %d · skill %d · "
                        + "tactic '%s' (%s) from %s · %d rules · club '%s' ===%n%n",
                matches, ticks, baseSeed, (int) RealTacticsHarness.SKILL,
                loaded.formation(), loaded.style(), loaded.source(), loaded.ruleCount(), loaded.teamName());
        if (!loaded.source().startsWith("the WORLD DEFAULT")) {
            System.out.println("!! NOT a designated world default.\n");
        }

        for (int m = 0; m < matches; m++) {
            tick = 0;
            SimulationRandom.seed(baseSeed + m);
            MatchState state = new MatchState();
            RealTacticsHarness.addTeam(state, "HOME", loaded.formation());
            RealTacticsHarness.addTeam(state, "AWAY", loaded.formation());
            MatchOrchestrator o = RealTacticsHarness.match(state, loaded);
            o.getRestartManager().handleKickoff(state, "HOME");
            o.setDecisionObserver(DecisionExecutionDiag::onDecision);
            o.setTickObserver(DecisionExecutionDiag::onTick);
            DuelEngine.resetCounters();
            o.simulate(ticks);
            matchesRun++;
            duels += DuelEngine.duelsResolved;
            duelsLostByCarrier += DuelEngine.duelsLostByCarrier;
            duels += DuelEngine.duelsResolved;
            duelsLostByCarrier += DuelEngine.duelsLostByCarrier;
            DuelEngine.resetCounters();
        }

        report();
    }

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

    private static void onDecision(MatchState state, DecisionResult result, Player carrier) {
        decisions++;
        DecisionOption chosen = result.getChosen();

        double runnerUp = Double.NEGATIVE_INFINITY;
        for (DecisionOption o : result.getOptions()) {
            if (o == chosen || o.getScore() <= UNAVAILABLE) continue;
            runnerUp = Math.max(runnerUp, o.getScore());
        }
        boolean top = runnerUp == Double.NEGATIVE_INFINITY || chosen.getScore() >= runnerUp;
        if (top) topScoreDecisions++;

        Pending p = new Pending();
        p.team = carrier.getTeam();
        p.type = chosen.getType();
        p.targetId = chosen.getTarget() == null ? null : chosen.getTarget().getId();
        p.decidedAtTick = tick;
        p.goalsAtDecision = state.getHomeGoals() + state.getAwayGoals();
        p.wasTopScore = top;
        p.margin = runnerUp == Double.NEGATIVE_INFINITY ? 999.0 : chosen.getScore() - runnerUp;
        // "Decisive" means the engine was not choosing between two ideas — margin of 20 or more.
        if (p.margin >= 20.0) decisiveDecisions++;
        p.opponentsInRange = countOpponentsInDuelRange(state, carrier);
        pending.add(p);
    }

    /**
     * How many opponents were close enough to actually contest a dribble right now.
     *
     * <p>The owner, 2026-10-18: <i>"pretpostavka da kad igrač nosi loptu bez duela je carry ne
     * dribble"</i> — a player carrying the ball with nobody around him is carrying, not dribbling.
     * So a dribble attempt is one somebody could contest, and the success rate that means anything
     * is the rate over <em>those</em>.
     *
     * <p>Measured with the widest dribble radius, because that is the distance at which the engine
     * would call a challenge: if even that finds nobody, nothing was going to contest it.
     */
    private static int countOpponentsInDuelRange(MatchState state, Player carrier) {
        int n = 0;
        for (Player o : state.getPlayers()) {
            if (o.getTeam().equals(carrier.getTeam())) continue;
            if (o.isUnavailable()) continue;
            double d = SimUtils.distance(o.getPosition(), carrier.getPosition());
            if (d <= DuelEngine.DRIBBLE_DUEL_RADIUS) n++;
        }
        return n;
    }

    private static void onTick(MatchState state) {
        tick++;
        Player carrier = state.getCarrier();

        for (int i = pending.size() - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            int window = windowFor(p.type);

            // A pass resolves the moment anyone has the ball — there is nothing more to learn by
            // waiting. Resolving early is what stops one possession's worth of per-tick decisions
            // from all being judged against the same later moment.
            boolean hasSomeone = carrier != null;
            boolean due = tick - p.decidedAtTick >= window;
            boolean earlyExit = hasSomeone && isPassLike(p.type) && tick - p.decidedAtTick >= 1;
            if (!due && !earlyExit) continue;

            pending.remove(i);
            resolve(p, state, carrier);
        }
    }

    private static int windowFor(ActionType type) {
        switch (type) {
            case DRIBBLE: return DRIBBLE_WINDOW;
            case SHOT: return SHOT_WINDOW;
            default: return PASS_WINDOW;
        }
    }

    private static boolean isPassLike(ActionType type) {
        return type == ActionType.PASS || type == ActionType.THRU
                || type == ActionType.CROSS || type == ActionType.CENTER;
    }

    private static void resolve(Pending p, MatchState state, Player carrier) {
        int goalsNow = state.getHomeGoals() + state.getAwayGoals();
        boolean scored = goalsNow > p.goalsAtDecision;

        String outcome;
        if (scored) {
            outcome = "GOAL";
        } else {
            outcome = classify(p, state, carrier);
        }
        bump(label(p), outcome);
    }

    /** A DRIBBLE nobody could contest is a carry. Reported under its own name, per the owner. */
    private static String label(Pending p) {
        if (p.type != ActionType.DRIBBLE) return p.type.name();
        return p.opponentsInRange == 0 ? "CARRY (uncontested)" : "DRIBBLE (contested)";
    }

    private static String classify(Pending p, MatchState state, Player carrier) {
        switch (p.type) {
            case PASS:
            case THRU:
            case CROSS:
            case CENTER:
                if (carrier == null) return "ball still loose";
                if (carrier.isGoalkeeper()) return "gathered by keeper";
                if (!carrier.getTeam().equals(p.team)) return "intercepted";
                if (p.targetId != null && carrier.getId().equals(p.targetId)) return "completed";
                return "misdirected";
            case DRIBBLE:
                if (carrier == null) return "lost it";
                if (carrier.isGoalkeeper()) return "gathered by keeper";
                return carrier.getTeam().equals(p.team) ? "kept it" : "lost it";
            case SHOT:
                if (carrier != null && carrier.isGoalkeeper()) return "gathered by keeper";
                String sp = state.getSetPieceType();
                if (sp != null) {
                    String s = sp.toUpperCase();
                    if (s.contains("CORNER")) return "blocked for a corner";
                    if (s.contains("GOAL")) return "off target";
                }
                return "no outcome seen";
            case CLEAR:
                return "cleared";
            default:
                return "no outcome seen";
        }
    }

    private static void report() {
        System.out.println("DID THE ENGINE PICK THE BEST OPTION?");
        System.out.printf("   decisions                                  %6d%n", decisions);
        System.out.printf("   the top-scoring option                     %6d / %-6d %5.1f%%%n",
                topScoreDecisions, decisions, pct(topScoreDecisions, decisions));
        System.out.printf("   won by 20+ points, so not a coin flip       %6d / %-6d %5.1f%%%n",
                decisiveDecisions, decisions, pct(decisiveDecisions, decisions));
        System.out.println();

        System.out.println("REAL DUELS (counted in DuelEngine, not inferred from who held the ball)");
        System.out.printf("   duels per match                              %8.1f%n",
                matchesRun == 0 ? 0 : duels / (double) matchesRun);
        System.out.printf("   of those, won by the carrier                 %8.1f%% per match%n",
                duels == 0 ? 0 : duelsLostByCarrier * 100.0 / duels);
        System.out.println();
        System.out.println("DID IT WORK?  — per action, because the actions have nothing in common");
        System.out.println("   a dribble is judged over one tick, a pass over ten, a shot until the ball is dead");
        for (String action : new String[]{"CARRY (uncontested)", "DRIBBLE (contested)", "PASS", "THRU",
                "CROSS", "CENTER", "CLEAR", "SHOT"}) {
            int total = totalFor(action);
            if (total == 0) continue;
            System.out.printf("   %s (%d)%n", action, total);
            List<Map.Entry<String, int[]>> rows = new ArrayList<>();
            for (Map.Entry<String, int[]> e : byAction.entrySet()) {
                if (e.getKey().startsWith(action + "|")) rows.add(e);
            }
            rows.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            for (Map.Entry<String, int[]> e : rows) {
                String outcome = e.getKey().substring(action.length() + 1);
                System.out.printf("      %-22s %6d / %-6d %5.1f%%%n",
                        outcome, e.getValue()[0], total, e.getValue()[0] * 100.0 / total);
            }
        }
        System.out.println();
    }

    private static double pct(int n, int d) {
        return d == 0 ? 0.0 : n * 100.0 / d;
    }
}