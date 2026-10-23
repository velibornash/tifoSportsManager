package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ME-3 (owner 2026-10-23): the shot frequency gate is GONE — "SKLONI TO PRAVILO, igrac treba da
 * odluci u odnosu na to da li je pozicija za sut ili nije".
 *
 * <p><b>The report.</b> Match 1 had 11 shots in 90 minutes: a 0.30 per-possession roll vetoed SHOT to
 * {@code UNAVAILABLE} regardless of position, so the striker's decision came out of a dice throw
 * instead of out of the position. {@code 6015b04f} then demoted the gated carrier's DRIBBLE, which
 * turned the vetoed shots into backward passes (the ME-2 complaint).
 *
 * <p><b>What is checked.</b> Two levels:
 * <ol>
 *   <li><b>The position, deterministically.</b> A striker alone in the box — every opponent parked
 *       deep in the far half, his own team-mates just outside the area — is asked the same question
 *       50 times. Every single decision must be a SHOT. The position is the whole judgement: zone,
 *       chance quality, corner openness (the {@code SHOT_MIN_OPENNESS} veto stays — that IS the
 *       position judgement). This is also ME-2's cascade criterion: the same box scenario that used
 *       to produce a vetoed shot and a pass into his own half now produces a shot.</li>
 *   <li><b>The volume, measured.</b> Five full matches under five fixed seeds, each shot count must
 *       sit in a sane band: well above the 11-shot desert the gate produced and well below the
 *       83-shot machine-gun the engine had before any gate. Real football is ~25; the owner accepts
 *       30-40 ("ma to je ok 30-40 udaraca").</li>
 * </ol>
 *
 * <p>Seeded explicitly ({@code SimulationRandom.seed}) so the band compares like with like — the
 * thread-local source is unseeded by default, and a baseline is only comparable to a baseline
 * measured the same way.
 */
class ShotFrequencyGateIsGoneTest {

    private static final int FULL_MATCH = 3600;
    private static final int DECIDES = 50;

    /** Measured over seeds 1..5 on 2026-10-23 — see the progress log for the observed counts. */
    private static final int SHOTS_PER_MATCH_LO = 15;
    private static final int SHOTS_PER_MATCH_HI = 60;

    @Test
    @DisplayName("a carrier alone in the box shoots on every decision — the position decides, not a roll")
    void theLoneBoxCarrierAlwaysShoots() {
        MatchOrchestrator orchestrator = buildMatch();
        MatchState state = orchestrator.getState();
        state.setKickoffPending(false); // open play — this is the rule's scenario

        // The striker with the ball a yard from goal, alone: every opponent parked deep in
        // the far half (both corners open, no pressure from defenders), his team-mates just
        // outside the area (a pass exists — the shot has to WIN, not win by default).
        Player striker = byId(state, "10"); // HOME ATT (idBase 1 -> ids 1..11)
        state.setCarrier(striker);

        int i = 0;
        for (Player p : state.getPlayers()) {
            if (p.equals(striker)) continue;
            if ("HOME".equals(p.getTeam())) {
                if (p.isGoalkeeper()) continue; // stays on his line
                p.setPosition(new Position(4.8 + (i % 2) * 0.5, 1.5 + (i % 6) * 1.0));
            } else {
                if (p.isGoalkeeper()) continue; // his keeper's position is not this test's business
                p.setPosition(new Position(1.0 + (i % 3) * 0.5, 1.5 + (i % 6) * 1.0));
            }
            i++;
        }

        CleanDecisionEngine engine = new CleanDecisionEngine();
        List<String> wrong = new ArrayList<>();
        for (int d = 0; d < DECIDES; d++) {
            // Leave and re-enter the zone between asks: the original gate re-armed on ARRIVAL, so
            // this gives a restored gate a fresh roll every other decide instead of one cached
            // answer for the whole test.
            striker.setPosition(new Position(5.0, 3.0));
            engine.decideWithOptions(state); // out of zone — no shot is asked here
            striker.setPosition(new Position(7.6, 3.4)); // a yard from goal — the shooting position

            DecisionResult result = engine.decideWithOptions(state);
            boolean shotOffered = result.getOptions().stream()
                    .anyMatch(o -> o.getType() == org.tifo.footballmanager.newLogic.sim.model.ActionType.SHOT
                            && o.getScore() >= 0);
            boolean shotChosen = result.getChosen() != null
                    && result.getChosen().getType() == org.tifo.footballmanager.newLogic.sim.model.ActionType.SHOT;
            if (!shotOffered) {
                DecisionOption shot = result.getOptions().stream()
                        .filter(o -> o.getType() == org.tifo.footballmanager.newLogic.sim.model.ActionType.SHOT)
                        .findFirst().orElse(null);
                wrong.add("decide #" + d + ": the SHOT option is not offered ("
                        + (shot == null ? "missing" : "score " + shot.getScore() + " — " + shot.getReason()) + ")");
            } else if (!shotChosen) {
                wrong.add("decide #" + d + ": chose " + result.getChosen().getType() + " — "
                        + result.getChosen().getReason());
            }
        }

        assertTrue(wrong.isEmpty(),
                () -> "a lone carrier a yard from goal must be offered and take the shot on EVERY "
                        + "decision — the shot frequency gate is gone, the position is the judgement:\n"
                        + String.join("\n", wrong));
    }

    @Test
    @DisplayName("five seeded matches land in a sane shot band — above the 11-shot desert, below the 83-shot flood")
    void theShotVolumeIsSaneOnEverySeed() {
        List<String> outside = new ArrayList<>();
        int[] counts = new int[5];
        for (int s = 0; s < counts.length; s++) {
            SimulationRandom.seed(s + 1L);
            MatchOrchestrator orchestrator = buildMatch();
            orchestrator.simulate(FULL_MATCH);
            counts[s] = (int) orchestrator.getRecorder().getEvents().stream()
                    .filter(e -> "SHOT".equals(e.getType()))
                    .count();
            if (counts[s] < SHOTS_PER_MATCH_LO || counts[s] > SHOTS_PER_MATCH_HI) {
                outside.add("seed " + (s + 1) + ": " + counts[s] + " shots");
            }
        }

        assertTrue(outside.isEmpty(),
                () -> "shot volume outside [" + SHOTS_PER_MATCH_LO + ", " + SHOTS_PER_MATCH_HI
                        + "] — counts were " + java.util.Arrays.toString(counts) + "\n"
                        + String.join("\n", outside));
        assertTrue(java.util.Arrays.stream(counts).sum() > 0,
                "the matches must contain shots, or this check proves nothing");
        assertEquals(counts.length, 5, "five seeds, five matches");
    }

    // --- helpers ------------------------------------------------------------

    private static Player byId(MatchState state, String id) {
        return state.getPlayers().stream()
                .filter(p -> id.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no player with id " + id));
    }

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
