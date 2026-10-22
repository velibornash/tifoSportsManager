package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.ActionType;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.LinkedHashMap;
import java.util.Map;

public class DecisionQualityDiag {

    private static final int N_TICKS = 7200;

    private static long decTotal = 0;
    private static final Map<String, Long> chosen = new LinkedHashMap<>();
    private static long shotAvailable = 0, shotChosen = 0;
    private static long dribbleInDangerZone = 0;
    private static long clearMissed = 0;

    private static long ticksTotal = 0;
    private static long threatActive = 0;
    private static long carrierPressed = 0;
    private static long loneAttackerInDanger = 0;
    private static long loneAttackerPressed = 0;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        long baseSeed = args.length > 1 ? Long.parseLong(args[1]) : 42L;

        for (String a : new String[]{"PASS", "SHOT", "DRIBBLE", "CLEAR", "THRU", "CROSS", "CENTER"})
            chosen.put(a, 0L);

        for (int m = 0; m < n; m++) {
            SimulationRandom.seed(baseSeed + m * 7);
            MatchState state = new MatchState();
            SkillVariantDiag.addTeam(state, "HOME", 15);
            SkillVariantDiag.addTeam(state, "AWAY", 15);
            MatchOrchestrator o = new MatchOrchestrator(state);
            o.setDecisionObserver((st, result, carrier) -> onDecision(st, result, carrier));
            o.getRestartManager().handleKickoff(state, "HOME");
            for (Player p : state.getPlayers()) {
                state.setRoundStartPosition(p.getId(), p.getPosition());
                state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
            }
            for (int t = 0; t < N_TICKS && !state.isMatchFinished(); t++) {
                captureThreat(state);
                o.tick();
            }
        }

        long tot = decTotal == 0 ? 1 : decTotal;
        System.out.println("=== DECISION QUALITY AUDIT — " + n + " matches ===");
        System.out.println();
        System.out.println("--- chosen action distribution ---");
        for (Map.Entry<String, Long> e : chosen.entrySet()) {
            System.out.printf("%-8s %8d  %5.1f%%%n", e.getKey(), e.getValue(), 100.0 * e.getValue() / tot);
        }
        System.out.printf("total decisions: %d%n", decTotal);
        System.out.println();
        System.out.println("--- shot quality ---");
        System.out.printf("shot available: %d  chosen: %d  (%.1f%% of available)%n",
                shotAvailable, shotChosen, shotAvailable == 0 ? 0 : 100.0 * shotChosen / shotAvailable);
        System.out.println();
        System.out.println("--- dribble in dangerous zone (carrier in own def third) ---");
        System.out.printf("count: %d%n", dribbleInDangerZone);
        System.out.println();
        System.out.println("--- missed clearances (pressured in def third, clear available, not chosen) ---");
        System.out.printf("count: %d%n", clearMissed);
        System.out.println();
        System.out.println("--- threat override engagement ---");
        System.out.printf("ticks sampled: %d%n", ticksTotal);
        System.out.printf("threat-override active players: %d  (%.2f%% of ticks)%n", threatActive,
                100.0 * threatActive / (ticksTotal == 0 ? 1 : ticksTotal));
        System.out.printf("carrier pressed (opponent within 1.5, targeting carrier): %d  (%.2f%%)%n", carrierPressed,
                100.0 * carrierPressed / (ticksTotal == 0 ? 1 : ticksTotal));
        System.out.println();
        System.out.println("--- lone attacker in danger zone (no defender within 0.5 cells / 7 m) ---");
        System.out.printf("occurrences: %d   pressed by threat override: %d  (%.1f%%)%n",
                loneAttackerInDanger, loneAttackerPressed,
                loneAttackerInDanger == 0 ? 0 : 100.0 * loneAttackerPressed / loneAttackerInDanger);
    }

    private static void onDecision(MatchState state, DecisionResult result, Player carrier) {
        decTotal++;
        DecisionOption chosenOpt = result.getChosen();
        String t = chosenOpt.getType().name();
        chosen.merge(t, 1L, Long::sum);

        DecisionOption shotOpt = null;
        for (DecisionOption o : result.getOptions()) {
            if (o.getType() == ActionType.SHOT) shotOpt = o;
        }
        boolean shotAvail = shotOpt != null && shotOpt.getScore() > -1000;
        if (shotAvail) {
            shotAvailable++;
            if (ActionType.SHOT.name().equals(t)) shotChosen++;
        }

        boolean home = "HOME".equals(carrier.getTeam());
        double carrierRow = carrier.getPosition().getRow();
        boolean inDefThird = home ? carrierRow <= 3.5 : carrierRow >= 5.5;
        if (ActionType.DRIBBLE.name().equals(t) && inDefThird) {
            dribbleInDangerZone++;
        }
        if (inDefThird) {
            DecisionOption clearOpt = null;
            for (DecisionOption o : result.getOptions()) {
                if (o.getType() == ActionType.CLEAR) clearOpt = o;
            }
            boolean clearAvail = clearOpt != null && clearOpt.getScore() > -1000;
            if (clearAvail && !ActionType.CLEAR.name().equals(t)) {
                clearMissed++;
            }
        }
    }

    private static void captureThreat(MatchState state) {
        ticksTotal++;
        for (Player p : state.getPlayers()) {
            if (p.isThreatOverrideActive()) threatActive++;
        }
        Player carrier = state.getCarrier();
        if (carrier != null) {
            String defTeam = "HOME".equals(carrier.getTeam()) ? "AWAY" : "HOME";
            boolean pressed = false;
            for (Player p : state.getPlayers()) {
                if (!defTeam.equals(p.getTeam())) continue;
                if (p.getTarget() != null && SimUtils.distance(p.getTarget(), carrier.getPosition()) < 0.5) {
                    pressed = true; break;
                }
            }
            if (pressed) carrierPressed++;
        }
        for (Player att : state.getPlayers()) {
            boolean home = "HOME".equals(att.getTeam());
            double row = att.getPosition().getRow();
            boolean inDanger = home ? row >= 6.0 : row <= 3.0;
            if (!inDanger || att == state.getCarrier()) continue;
            if (att.isSentOff() || att.isInjured()) continue;
            String defTeam = home ? "AWAY" : "HOME";
            boolean hasDefenderNear = false;
            for (Player def : state.getPlayers()) {
                if (!defTeam.equals(def.getTeam())) continue;
                if (def.isSentOff() || def.isInjured()) continue;
                if (SimUtils.distance(def.getPosition(), att.getPosition()) <= 0.5) {
                    hasDefenderNear = true; break;
                }
            }
            if (!hasDefenderNear) {
                loneAttackerInDanger++;
                boolean pressed = false;
                for (Player def : state.getPlayers()) {
                    if (!defTeam.equals(def.getTeam())) continue;
                    if (def.getTarget() != null && SimUtils.distance(def.getTarget(), att.getPosition()) < 0.5) {
                        pressed = true; break;
                    }
                }
                if (pressed) loneAttackerPressed++;
            }
        }
    }
}
