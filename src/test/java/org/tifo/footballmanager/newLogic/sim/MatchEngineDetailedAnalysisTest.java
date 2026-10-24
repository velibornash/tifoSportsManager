package org.tifo.footballmanager.newLogic.sim;

import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.recording.MatchEvent;
import org.tifo.footballmanager.newLogic.sim.tactics.SideTactics;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticsRules;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic: dumps per-tick positions of all 22 players + ball + desired cell (target) for a
 * 60-tick (1.5 min) match, plus the recorded events, to target/match-analysis.txt for analysis
 * of movement/decision/duel defects reported in real matches.
 *
 * NOT a pass/fail guard - it always passes; its output is the artifact.
 */
class MatchEngineDetailedAnalysisTest {

    private static final int TICKS = 60;

    @Test
    void dumpDetailedAnalysis() throws Exception {
        StringBuilder sb = new StringBuilder();
        // Load the real world default tactic from the tracked profiles file, so the analysis reflects
        // the actual default (Osnovna) every club carries, not the bundled fallback.
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(new FileReader("var/tactics-editor-profiles.json"));
        JsonNode prof = root.get(0);
        JsonNode tac = prof.get("tactics").get(0);
        String rulesJson = tac.get("rulesJson").asText();
        String formation = tac.get("formation").asText();
        TacticsRules realTactic = TacticsRules.fromProfileJson(rulesJson, formation, "Osnovna");
        final SideTactics tactics = new SideTactics(realTactic, realTactic);
        System.out.println("Loaded real tactic: formation=" + formation + " rules=" + realTactic.getRuleCount());
        MatchOrchestrator orch = SimMatchRunner.build("Home FC", "Away United",
                null, null, null, null, tactics);

        orch.setTickObserver(state -> {
            int tick = state.getMatchTicks();
            sb.append(String.format("=== TICK %d (ball %.2f,%.2f %s) carrier=%s ===%n",
                    tick,
                    state.getBall().getPosition().getColumn(),
                    state.getBall().getPosition().getRow(),
                    state.getBall().getBallState(state.getCarrier()),
                    state.getCarrier() == null ? "none" : (state.getCarrier().getLabel() == null ? "?" : state.getCarrier().getLabel()) + "(" + state.getCarrier().getTeam() + ")"));
            for (String team : new String[]{"HOME", "AWAY"}) {
                sb.append(team).append(":\n");
                for (Player p : state.getPlayers()) {
                    if (p == null || !team.equals(p.getTeam())) continue;
                    if (p.getPosition() == null) continue;
                    Position pos = p.getPosition();
                    Position tgt = p.getTarget();
                    Position desired = null;
                    try {
                        desired = tactics.forPlayer(p).desiredCell(p.getRole(), state.getBall().getPosition(), p.getTeam(),
                                state.getCarrierTeam() != null ? state.getCarrierTeam()
                                        : state.getLastTouchTeam() != null ? state.getLastTouchTeam()
                                        : state.getRestartTeam(),
                                !state.isKickoffHalfHold());
                    } catch (Exception ex) { desired = null; }
                    String role = p.getRole() == null ? "?" : p.getRole();
                    String label = p.getLabel() == null ? "?" : p.getLabel();
                    sb.append(String.format("  %s %-18s pos=(%.2f,%.2f) tgt=%s desired=%s%n",
                            role,
                            label,
                            pos.getColumn(), pos.getRow(),
                            tgt == null ? "none" : String.format("(%.2f,%.2f)", tgt.getColumn(), tgt.getRow()),
                            desired == null ? "NULL" : String.format("(%.2f,%.2f)", desired.getColumn(), desired.getRow())));
                }
            }
            // events this tick
            // (recorder events are appended during the tick; capture after by tick filter at end)
        });

        orch.simulate(TICKS);

        // Append recorded events grouped by tick
        sb.append("%n%n===== RECORDED EVENTS =====%n");
        List<MatchEvent> events = orch.getRecorder().getEvents();
        int lastTick = -1;
        for (MatchEvent e : events) {
            if (e.getTick() != lastTick) {
                lastTick = (int) e.getTick();
                sb.append(String.format("%n--- tick %d ---%n", lastTick));
            }
            sb.append(String.format("  %s: %s%n", e.getType(), e.getDescription()));
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter("target/match-analysis.txt"))) {
            pw.print(sb);
        }
        System.out.println("WROTE target/match-analysis.txt (" + sb.length() + " chars, " + events.size() + " events)");
        assertTrue(true);
    }
}