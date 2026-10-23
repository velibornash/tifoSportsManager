package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.recording.MatchEvent;
import org.tifo.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ME-1 (owner 2026-10-23): a red card REMOVES the player — from the pitch, from every snapshot and
 * from every pool.
 *
 * <p><b>What was actually wrong.</b> {@code DisciplineService} set {@code sentOff=true} and movement,
 * restarts and offside all skipped him — so the player FREEZED at the spot of his offence and stayed
 * in every recorded snapshot for the rest of the match (measured in match 3: Dabić held one single
 * coordinate from 3:00 to 90:00), while {@code findBestReceiver} and {@code kickoffOption} had no
 * filter at all. Because nobody marks a player the engine considers gone, the ghost was the most open
 * man on the pitch: he received a kickoff pass at 12:37 and was the ball carrier in 131 snapshots.
 * The owner watched it: "Ostaje na terenu, primao je loptu u sredini."
 *
 * <p>Two levels of guard, both running real production code: a deterministic snapshot check straight
 * through {@link MatchRecorder}, and a full match played on AFTER the card, asserting the man never
 * appears again — not in a snapshot, not as carrier, not as the target of a pass.
 *
 * <p>Modelled on {@code ConditionalSubstitutionFiresInAMatchTest} for the squad builders and the
 * build-then-tick seam.
 */
class SentOffPlayerLeavesTheMatchTest {

    @Test
    @DisplayName("a snapshot captured after the card contains no sent-off player")
    void snapshotOmitsTheSentOffPlayer() {
        MatchOrchestrator orchestrator = buildMatch();
        MatchState state = orchestrator.getState();

        org.tifo.footballmanager.newLogic.sim.model.Player victim = cardAnOutfieldPlayer(state);
        assertTrue(victim.getSentOffTick() >= 0, "the card must carry the tick it happened on");

        MatchRecorder recorder = new MatchRecorder();
        recorder.captureSnapshot(state);

        List<MatchSnapshot> snapshots = recorder.getSnapshots();
        assertTrue(!snapshots.isEmpty(), "a snapshot must have been captured");
        MatchSnapshot snap = snapshots.get(snapshots.size() - 1);
        assertTrue(snap.getPlayers().stream().noneMatch(p -> p.getId().equals(victim.getId())),
                "a sent-off player must not be written into the snapshot — he is off the pitch, "
                        + "and the viewer draws every player it is given as a circle");
    }

    @Test
    @DisplayName("played on after the card, the match never shows, serves or passes to the man sent off")
    void theRestOfTheMatchHasNoGhost() {
        MatchOrchestrator orchestrator = buildMatch();
        MatchState state = orchestrator.getState();

        // A real card timing: some football first (~30 match-ticks in this engine's unit),
        // then the sanction, then the match goes on for another hour.
        orchestrator.simulate(600);
        org.tifo.footballmanager.newLogic.sim.model.Player victim = cardAnOutfieldPlayer(state);
        long cardTick = victim.getSentOffTick();
        assertTrue(cardTick > 0, "the card must land after kick-off for this test to mean anything");

        orchestrator.simulate(3600); // play on — simulate() stops itself at full time

        MatchRecorder recorder = orchestrator.getRecorder();

        int snapshotsAfterCard = 0;
        for (MatchSnapshot s : recorder.getSnapshots()) {
            // The card is applied between the two simulate() calls, at the same tick value as the
            // last pre-card snapshot — so only snapshots STRICTLY after it can be expected to be
            // clean, and those are the ones that matter (the whole rest of the match).
            if (s.getMatchTicks() <= cardTick) continue;
            snapshotsAfterCard++;
            assertTrue(s.getPlayers().stream().noneMatch(p -> p.getId().equals(victim.getId())),
                    "tick " + s.getMatchTicks() + ": the sent-off player is still in the snapshot "
                            + "(he must be absent from the pitch entirely)");
            assertNotEquals(victim.getId(), s.getBallCarrierId(),
                    "tick " + s.getMatchTicks() + ": the sent-off player is still carrying the ball");
        }
        assertTrue(snapshotsAfterCard > 100,
                "the match must actually be observed after the card — only " + snapshotsAfterCard
                        + " snapshots were checked, which means the run stopped early");

        int deliveries = 0;
        for (MatchEvent e : recorder.getEvents()) {
            if (e.getTick() <= cardTick) continue;
            if (!"PASS".equals(e.getType()) && !"RECEIVE".equals(e.getType())) continue;
            deliveries++;
            assertNotEquals(victim.getId(), e.getTargetPlayerId(),
                    e.getTick() + " " + e.getType() + ": the ball was played to the player sent off — "
                            + e.getDescription());
        }
        assertTrue(deliveries > 100,
                "a match must contain passes after the card to prove the pool was exercised — only "
                        + deliveries + " checked");
    }

    @Test
    @DisplayName("the kickoff pass never offers a player who is off the pitch")
    void kickoffPoolRefusesUnavailablePlayers() {
        MatchOrchestrator orchestrator = buildMatch();
        MatchState state = orchestrator.getState();

        org.tifo.footballmanager.newLogic.sim.model.Player taker = state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && !p.isGoalkeeper())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HOME outfield taker"));
        state.setCarrier(taker);
        state.setKickoffPending(true);

        // Card every OTHER outfield player of the kicking team. An absurd scoreline, but it makes
        // the pool question absolute: if one unavailable player can be offered, this catches him —
        // the kickoff branch returns BEFORE any other option is scored, so a chosen kickoff pass
        // with an off-the-pitch target fails here deterministically. (No receiver at all is a legal
        // outcome: kickoffOption returns null, the pass option scores "no receiver", the carrier
        // keeps his other options.)
        for (org.tifo.footballmanager.newLogic.sim.model.Player p :
                List.copyOf(state.getPlayers())) {
            if ("HOME".equals(p.getTeam()) && !p.isGoalkeeper() && !p.equals(taker)) {
                p.setSentOff(true);
                p.setSentOffTick(state.getMatchTicks());
            }
        }

        DecisionOption decision = new org.tifo.footballmanager.newLogic.sim.engine.decision
                .CleanDecisionEngine().decide(state);
        assertTrue(decision.getTarget() == null || !decision.getTarget().isUnavailable(),
                "the kickoff pass offered a player who has left the pitch: "
                        + decision.getType() + " -> "
                        + (decision.getTarget() == null ? "null" : decision.getTarget().getId()));
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Sends an outfield player off exactly the way {@code DisciplineService} does it — the two field
     * writes, nothing more — so the test exercises the real consequence of a red card rather than a
     * test-only state. The GK is excluded: his disappearance would raise keeper-of-last-resort
     * questions that are not what this guard is about.
     */
    private static org.tifo.footballmanager.newLogic.sim.model.Player cardAnOutfieldPlayer(
            MatchState state) {
        org.tifo.footballmanager.newLogic.sim.model.Player victim = state.getPlayers().stream()
                .filter(p -> "AWAY".equals(p.getTeam()) && !p.isGoalkeeper() && !p.isSentOff())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outfield AWAY player to send off"));
        victim.setSentOff(true);
        victim.setSentOffTick(state.getMatchTicks());
        return victim;
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
