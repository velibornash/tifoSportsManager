package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.recording.MatchEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ME-2 (owner 2026-10-23): from a shooting position a pass back into his own half is IMPOSSIBLE —
 * the option is removed, not penalised. From farther out the old football stands (backward −20).
 *
 * <p><b>The report.</b> Match 3, 0:22: Timić at (7.3, 2.4) — SHOT vetoed, DRIBBLE demoted, so the
 * carrier played "PASS: base 8 backward −20" back to Dabić at (3.5, 3.5), a full four cells out of
 * the attack: *"away tim iz pozicije za sut odigrava PAS NA SVOJU POLOVINU, ovo smo bili zabranili!"*
 * The owner's ruling when asked hard ban vs penalty: <b>hard veto</b> from a shooting position.
 *
 * <p>Two levels: a deterministic scenario where every team-mate stands behind the halfway line and
 * the carrier alone is in the box — no option may then offer an own-half target (pre-fix,
 * {@code findBestReceiver} offers one every time) — and a full match, where no PASS event with its
 * acting player in a shooting zone may land in the passer's own half.
 */
class BackwardPassFromAShootingPositionTest {

    private static final int FULL_MATCH = 3600;

    @Test
    @DisplayName("a carrier alone in the box is never offered his own half, by pass or through ball")
    void theBoxCarrierIsNeverOfferedHisOwnHalf() {
        MatchOrchestrator orchestrator = buildMatch();
        MatchState state = orchestrator.getState();
        state.setKickoffPending(false); // open play — not a restart — this is the rule's scenario

        // The exact shape of the complaint: the ball in the shooting zone (HOME rows 6.0–8.0),
        // every other team-mate of his behind the halfway line. If a backward pass exists AT ALL,
        // this position must find it — and after the fix it must not exist.
        org.tifo.footballmanager.newLogic.sim.model.Player carrier = state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && !p.isGoalkeeper())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HOME outfield carrier"));
        state.setCarrier(carrier);
        carrier.setPosition(new org.tifo.footballmanager.newLogic.sim.model.Position(7.0, 3.0));

        int i = 0;
        for (org.tifo.footballmanager.newLogic.sim.model.Player p : state.getPlayers()) {
            if (!"HOME".equals(p.getTeam()) || p.equals(carrier)) continue;
            if (p.isGoalkeeper()) continue; // the keeper sits on row ~1 — already in his own half
            p.setPosition(new org.tifo.footballmanager.newLogic.sim.model.Position(
                    2.0 + (i % 3),      // rows 2, 3, 4 — every outfield man in his own half
                    1.5 + ((i / 3) % 6)));
            i++;
        }

        DecisionResult result = new CleanDecisionEngine().decideWithOptions(state);
        assertTrue(!result.getOptions().isEmpty(), "the engine must evaluate its options");

        for (DecisionOption option : result.getOptions()) {
            org.tifo.footballmanager.newLogic.sim.model.Player target = option.getTarget();
            if (target == null) continue;
            double targetRow = target.getPosition().getRow();
            assertFalse(targetRow < 4.5,
                    "the engine offered an own-half target (" + option.getType() + " -> "
                            + target.getLabel() + " at row " + targetRow + ") from a shooting "
                            + "position — the option must not exist, not merely score badly. Reason: "
                            + option.getReason());
        }
    }

    @Test
    @DisplayName("played out, no chosen delivery from a shooting position ever targets his own half")
    void theMatchNeverPlaysBackFromTheBox() {
        MatchOrchestrator orchestrator = buildMatch();

        // The rule is asserted AT DECISION TIME through the engine's own observer seam: the same
        // state the decision saw, with the reason attached. Checking recorded events against
        // snapshots afterwards would compare the target's row at a DIFFERENT moment — players keep
        // moving within the tick — and would fail on positions the decision never saw.
        List<String> violations = new ArrayList<>();
        int[] fromZoneChoices = {0};
        orchestrator.setDecisionObserver((state, result, carrier) -> {
            DecisionOption chosen = result.getChosen();
            if (chosen == null || chosen.getTarget() == null) return;
            if (!inShootingZone(carrier)) return;
            if (!inOwnHalf(carrier, chosen.getTarget())) return;
            violations.add("tick " + state.getMatchTicks() + ": " + chosen.getType() + " by "
                    + carrier.getLabel() + " at row " + carrier.getPosition().getRow()
                    + " -> " + chosen.getTarget().getLabel() + " at row "
                    + chosen.getTarget().getPosition().getRow()
                    + " — in his own half. Reason: " + chosen.getReason());
        });
        orchestrator.simulate(FULL_MATCH);

        for (MatchEvent e : orchestrator.getRecorder().getEvents()) {
            if (!"PASS".equals(e.getType())) continue;
            Double actingRow = e.getPositionRow();
            String team = e.getTeam();
            if (actingRow == null || team == null) continue;
            boolean fromZone = "HOME".equals(team) ? actingRow >= 6.0 : actingRow <= 3.0;
            if (fromZone && e.getTargetPlayerId() != null) fromZoneChoices[0]++;
        }

        assertTrue(violations.isEmpty(),
                () -> "a pass back into his own half from a shooting position — the option must not "
                        + "exist:\n" + String.join("\n", violations));
        assertTrue(fromZoneChoices[0] > 0,
                "the match must contain passes from a shooting position, or this check proves "
                        + "nothing — got " + fromZoneChoices[0]);
    }

    /** The rule's own vocabulary, mirrored from {@link CleanDecisionEngine}'s helpers. */
    private static boolean inShootingZone(org.tifo.footballmanager.newLogic.sim.model.Player p) {
        double row = p.getPosition().getRow();
        return "HOME".equals(p.getTeam()) ? row >= 6.0 : row <= 3.0;
    }

    private static boolean inOwnHalf(org.tifo.footballmanager.newLogic.sim.model.Player passer,
                                     org.tifo.footballmanager.newLogic.sim.model.Player target) {
        double row = target.getPosition().getRow();
        return "HOME".equals(passer.getTeam()) ? row < 4.5 : row > 4.5;
    }

    // --- helpers ------------------------------------------------------------

    private static MatchOrchestrator buildMatch() {
        List<org.tifo.footballmanager.newLogic.model.Player> homeStarters = elevenStarters(1);
        List<org.tifo.footballmanager.newLogic.model.Player> awayStarters = elevenStarters(100);
        var homeSquad = RealSquadFactory.buildSquad(lineupWith(homeStarters), "HOME");
        var awaySquad = RealSquadFactory.buildSquad(lineupWith(awayStarters), "AWAY");
        return SimMatchRunner.build("Home FC", "Away United", homeSquad, awaySquad, null, null, null);
    }

    private static List<org.tifo.footballmanager.newLogic.model.Player> elevenStarters(int idBase) {
        List<org.tifo.footballmanager.newLogic.model.Player> out = new ArrayList<>();
        org.tifo.footballmanager.newLogic.model.Position[] line = {
                org.tifo.footballmanager.newLogic.model.Position.GK,
                org.tifo.footballmanager.newLogic.model.Position.DEF,
                org.tifo.footballmanager.newLogic.model.Position.DEF,
                org.tifo.footballmanager.newLogic.model.Position.DEF,
                org.tifo.footballmanager.newLogic.model.Position.DEF,
                org.tifo.footballmanager.newLogic.model.Position.MID,
                org.tifo.footballmanager.newLogic.model.Position.MID,
                org.tifo.footballmanager.newLogic.model.Position.MID,
                org.tifo.footballmanager.newLogic.model.Position.MID,
                org.tifo.footballmanager.newLogic.model.Position.ATT,
                org.tifo.footballmanager.newLogic.model.Position.ATT };
        for (int i = 0; i < 11; i++) {
            out.add(player(idBase + i, line[i]));
        }
        return out;
    }

    private static org.tifo.footballmanager.newLogic.model.Player player(
            long id, org.tifo.footballmanager.newLogic.model.Position position) {
        org.tifo.footballmanager.newLogic.model.Skills skills =
                new org.tifo.footballmanager.newLogic.model.Skills();
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.PACE, 15);
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.STAMINA, 14);
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.GOALKEEPER, 13);
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.DEFENDER, 14);
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.TECHNIQUE, 14);
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.PLAYMAKER, 14);
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.PASSING, 14);
        skills.setSkill(org.tifo.footballmanager.newLogic.model.SkillName.STRIKER, 14);
        skills.initializeExactFromVisibleIfNeeded();
        return new org.tifo.footballmanager.newLogic.model.Player(
                id, "Player " + id, skills, 0.5, 21, 0, 0,
                1.80, 78.0, 8.0, 60.0, 7, position,
                0, 0, null, false, 0, null, null, null,
                null, null, 100.0, null, null,
                null, null, null, false,
                null, false,
                0);
    }

    private static org.tifo.footballmanager.newLogic.model.Lineup lineupWith(
            List<org.tifo.footballmanager.newLogic.model.Player> starters) {
        org.tifo.footballmanager.newLogic.model.Lineup lineup =
                new org.tifo.footballmanager.newLogic.model.Lineup();
        lineup.setStartingPlayers(new ArrayList<>(starters));
        lineup.setStarterOrderFromIds(starters.stream()
                .map(org.tifo.footballmanager.newLogic.model.Player::getId).toList());
        return lineup;
    }
}
