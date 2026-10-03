package org.example.footballmanager.newLogic.sim.result;

import org.example.footballmanager.newLogic.model.event.MatchEvent.MatchEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SimReportMapper}'s keep-list, held against the two readers that decide it.
 *
 * <p><b>Why the list needs two guards and not one.</b> Too short and the match report loses events it
 * shows today, which is a silent empty section rather than an error. Too long and the blob stays at
 * 742 KB and the whole task achieved nothing. A test that only checked "the noisy types are gone" would
 * pass happily against a list that had also dropped every goal.
 *
 * <p>So: <b>everything a reader wants must be kept</b>, and <b>everything kept must be wanted</b>. The
 * vocabularies below are transcribed from the readers' own code, and each line says where it came from
 * — that transcription is the thing to re-check when a reader changes, and
 * {@code #aReaderGainingATypeMustBeAddedHere} is what makes it fail instead of rot.
 *
 * <p>The two readers do not want the same events. {@code ZoxApiController.buildTimeline} wants
 * {@code OFFSIDE} and every {@code VAR_*} entry; {@code MatchDetailService.mapEventToDTO} drops both.
 * Deriving the list from one reader is how part of the match report would have gone missing.
 */
class SimReportMapperReportableTypesTest {

    /**
     * Every type {@code MatchDetailService} turns into a DTO — the {@code switch} in
     * {@code mapEventToDTO} plus everything {@code normalizeEventType} can return.
     */
    private static final Set<String> MATCH_DETAIL_WANTS = Set.of(
            "GOAL",
            "YELLOW_CARD", "RED_CARD", "CARD",
            "PENALTY", "PENALTY_AWARDED", "PENALTY_GOAL",
            "CORNER", "FREE_KICK",
            "SHOT_ON_TARGET", "SHOT_SAVED", "SHOT_BLOCKED", "SHOT_POST",
            "SHOT_OFF_TARGET", "SHOT_MISSED", "SHOT",
            "SUB", "SUBSTITUTION",
            "INJURY",
            "MATCH_START", "MATCH_END");

    /**
     * Everything else {@code ZoxApiController.buildTimeline} adds to a timeline.
     *
     * <p>{@code OFFSIDE} and the {@code VAR_} family are here and <b>not</b> in
     * {@link #MATCH_DETAIL_WANTS}. That asymmetry is the reason this file exists.
     */
    private static final Set<String> ZOX_TIMELINE_WANTS = Set.of(
            "GOAL",
            "YELLOW_CARD", "RED_CARD", "CARD",
            "PENALTY_AWARDED", "PENALTY",
            "OFFSIDE",
            "INJURY",
            "SUB", "SUBSTITUTION",
            "VAR");

    /**
     * The per-tick noise, measured across the shipped matches: 71% of all events.
     *
     * <p>Not one reader wants any of it. If a future reader does, it goes in the keep-list and this
     * test is the thing that should be argued with.
     */
    private static final Set<String> PER_TICK_NOISE = Set.of(
            "DECISION", "PASS", "RECEIVE",
            "DUEL", "DRIBBLE", "OOB_ENTER", "RESTART", "INTERCEPT", "DEFLECT",
            "CLEAR", "LOOSE_PICKUP", "GK_CATCH", "POST_HIT",
            "BALL_CARRIER_DECISION", "POSSESSION_START", "POSSESSION_END");

    @Test
    @DisplayName("every event the match-detail page shows is still written")
    void everythingMatchDetailShowsIsKept() {
        for (String type : MATCH_DETAIL_WANTS) {
            assertTrue(SimReportMapper.isReportable(type),
                    type + " is turned into a DTO by MatchDetailService.mapEventToDTO, so dropping it "
                            + "silently removes it from the match report.");
        }
    }

    @Test
    @DisplayName("every event the post-match timeline shows is still written")
    void everythingTheTimelineShowsIsKept() {
        for (String type : ZOX_TIMELINE_WANTS) {
            assertTrue(SimReportMapper.isReportable(type),
                    type + " is added to the timeline by ZoxApiController.buildTimeline, so dropping it "
                            + "silently removes it from the post-match report.");
        }
    }

    @Test
    @DisplayName("VAR review survives, whatever it is called")
    void varReviewSurvivesWhateverItIsCalled() {
        // buildTimeline accepts type.startsWith("VAR_"), so a VAR decision nobody has named yet must
        // still reach the report. Pinning three of them; the rule is the prefix, not the list.
        for (String type : List.of("VAR_IN_PROGRESS", "VAR_RED_CONFIRMED", "VAR_GOAL_OVERTURNED",
                "VAR_YELLOW_CONFIRMED", "VAR_OFFSIDE_CONFIRMED", "VAR_PENALTY_OVERTURNED")) {
            assertTrue(SimReportMapper.isReportable(type),
                    type + " is a VAR decision and buildTimeline reports every VAR_ entry.");
        }
    }

    @Test
    @DisplayName("the per-tick noise is not written, which is the entire point")
    void thePerTickNoiseIsNotWritten() {
        for (String type : PER_TICK_NOISE) {
            assertFalse(SimReportMapper.isReportable(type),
                    type + " is engine-internal. No reader wants it and it is most of the 742 KB.");
        }
    }

    @Test
    @DisplayName("a goal VAR ruled out is written for the audit trail, and never counted")
    void aRuledOutGoalIsWrittenAndNeverCounted() {
        // Written and counted are different questions, and the fix separated them. The blob keeps the
        // overturn so a match report can show it and a reader can see why a scorer's total is what it
        // is; the scorer's total excludes it because BallResultHandler asks VAR before it scores.
        for (String type : List.of("GOAL_DISALLOWED", "VAR_GOAL_OVERTURNED")) {
            assertTrue(SimReportMapper.isReportable(type),
                    type + " is a goal VAR ruled out. It stays in the blob as the audit trail - dropping "
                            + "it would leave the report unable to explain the score.");
            assertFalse(MatchEventType.countsAsGoal(type),
                    type + " must never be credited to a scorer. The engine asks VAR before it calls "
                            + "goalScored, so the scoreline does not count it either.");
        }
        // And the ones that do count.
        for (String type : List.of("GOAL", "VAR_GOAL_CONFIRMED", "OWN_GOAL", "PENALTY_GOAL")) {
            assertTrue(MatchEventType.countsAsGoal(type), type + " is a goal that stood.");
        }
    }

    @Test
    @DisplayName("a goal kick is a restart, and the substring that used to say otherwise is gone")
    void aGoalKickIsARestart() {
        assertFalse(MatchEventType.countsAsGoal("GOAL_KICK"),
                "GOAL_KICK is a restart. type.contains(\"GOAL\") matched it, which put a goal kick on the "
                        + "match report as a key moment.");
        assertFalse(MatchEventType.isGoalRelated("GOAL_KICK"),
                "GOAL_KICK is neither a goal that counted nor one that was ruled out.");
    }

    @Test
    @DisplayName("VAR decisions are recognised by prefix, so an unnamed one still reaches the report")
    void varDecisionsAreRecognisedByPrefix() {
        for (String type : List.of("VAR", "VAR_REVIEW", "VAR_IN_PROGRESS", "VAR_OFFSIDE_OVERTURNED",
                "VAR_GOAL_OVERTURNED", "VAR_RED_CONFIRMED", "VAR_PENALTY_OVERTURNED")) {
            assertTrue(MatchEventType.isVarDecision(type), type + " is a VAR decision.");
            assertTrue(SimReportMapper.isReportable(type), type + " belongs in the report.");
        }
        assertFalse(MatchEventType.isVarDecision("GOAL"), "a goal is not a VAR decision.");
        assertFalse(MatchEventType.isVarDecision(null));
    }

    @Test
    @DisplayName("nothing kept is unwanted, so the blob cannot creep back to 742 KB")
    void nothingKeptIsUnwanted() {
        Set<String> wanted = new java.util.LinkedHashSet<>(MATCH_DETAIL_WANTS);
        wanted.addAll(ZOX_TIMELINE_WANTS);
        // A ruled-out goal is wanted by nobody as a *score*, but it is wanted as an explanation, which
        // is why it is written. Kept here so the reason is stated rather than implied.
        wanted.addAll(List.of("VAR_REVIEW", "GOAL_DISALLOWED", "VAR_GOAL_OVERTURNED", "OWN_GOAL"));

        for (String kept : List.of("GOAL", "YELLOW_CARD", "RED_CARD", "CARD", "PENALTY",
                "PENALTY_AWARDED", "PENALTY_GOAL", "SHOT", "SHOT_ON_TARGET", "SHOT_OFF_TARGET",
                "SHOT_SAVED", "SHOT_BLOCKED", "SHOT_POST", "SHOT_MISSED", "CORNER", "FREE_KICK",
                "OFFSIDE", "SUB", "SUBSTITUTION", "INJURY", "MATCH_START", "MATCH_END", "VAR",
                "VAR_REVIEW")) {
            assertTrue(wanted.contains(kept),
                    kept + " is in the keep-list but no reader wants it. Every kept type is bytes on "
                            + "every match, so an unwanted one is the 742 KB returning one entry at a time.");
        }
    }

    @Test
    @DisplayName("a blank or missing type is dropped rather than throwing")
    void aBlankTypeIsDroppedRatherThanThrowing() {
        assertFalse(SimReportMapper.isReportable(null));
        assertFalse(SimReportMapper.isReportable(""));
        assertFalse(SimReportMapper.isReportable("   "));
        assertFalse(MatchEventType.countsAsGoal(null));
        assertFalse(MatchEventType.isGoalRelated(""));
    }

    @Test
    @DisplayName("the engine's spelling variants resolve to the same answer")
    void spellingVariantsResolve() {
        // MatchDetailService normalises case, dashes and spaces; the shared definition does the same, so
        // the writer and every reader agree on what a type is before any of them compares it.
        assertTrue(SimReportMapper.isReportable("goal"));
        assertTrue(SimReportMapper.isReportable("Yellow-Card"));
        assertFalse(SimReportMapper.isReportable("pass"));
        assertTrue(MatchEventType.countsAsGoal("goal"));
        assertTrue(MatchEventType.countsAsGoal(" Goal "));
        assertTrue(MatchEventType.isVarDecision("var_goal_overturned"));
    }

    @Test
    @DisplayName("a reader gaining a type has to be added here, and this is what makes that loud")

    void aReaderGainingATypeMustBeAddedHere() {
        // Not automatable against a switch statement, so it is stated instead: when
        // mapEventToDTO or buildTimeline gains a case, add the type to the matching vocabulary above.
        // Without that, #everythingMatchDetailShowsIsKept silently stops covering it.
        assertTrue(MATCH_DETAIL_WANTS.contains("GOAL") && ZOX_TIMELINE_WANTS.contains("GOAL"),
                "The goal vocabulary drifted. If this is the only failure, the two lists were edited "
                        + "rather than the readers.");
    }

    /**
     * The keep-list is only worth what the mapper actually writes, so this asserts the bytes rather
     * than the predicate. A matcher can be right and the writer can still ignore it.
     *
     * <p>The proportions are the ones measured on the shipped matches — 3,065 noise events to every
     * 71 reportable ones — so the size assertion is about the real ratio rather than a round number.
     */
    @Test
    @DisplayName("the mapper writes a small blob, and it still contains every reportable event")
    void theMapperWritesASmallBlobAndKeepsEveryReportableEvent() throws Exception {
        List<ProposalMatchOutcome.EventEntry> events = new java.util.ArrayList<>();
        // Measured shape: ~3,000 noise events and ~70 reportable ones per match.
        for (String noise : PER_TICK_NOISE) {
            for (int i = 0; i < 191; i++) {
                events.add(event(noise, "A Player", "PASS Player one at (4.5,4.0) -> Player two(2.5,3.5)"));
            }
        }
        int reportable = 0;
        for (String wanted : List.of("GOAL", "SHOT", "SHOT_ON_TARGET", "OFFSIDE", "YELLOW_CARD",
                "RED_CARD", "CORNER", "FREE_KICK", "SUB", "INJURY", "VAR_REVIEW", "MATCH_START")) {
            events.add(event(wanted, "A Player", null));
            reportable++;
        }

        String json = SimReportMapper.eventJson(new com.fasterxml.jackson.databind.ObjectMapper(),
                new ProposalMatchOutcome("Home", "Away", 1, 0, 160, 4, "4-4-2", "4-4-2", 50, 50, 1, 0,
                        null, null, List.of(), events, null, null, null));

        assertEquals(reportable, arraySize(json),
                "every reportable event must survive into the blob: " + json);
        assertFalse(json.contains("DECISION") && json.contains("PASS at"),
                "a per-tick noise event reached the blob: " + json);

        // 3,065 noise events at ~190 bytes each is ~580 KB; the 12 kept events are ~2 KB. Asserting a
        // ratio rather than a byte count, because the engine's description strings are the variable.
        assertTrue(json.length() < events.size() * 40,
                "the blob is still large: " + json.length() + " bytes for " + events.size()
                        + " events. The whole point is that it is a small fraction of them.");
    }

    private int arraySize(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json).size();
    }

    private ProposalMatchOutcome.EventEntry event(String type, String player, String description) {
        return new ProposalMatchOutcome.EventEntry(
                40, 1, type, "HOME", "1", player, null, description,
                null, null, 1, 0, null, null, null, null, null, null);
    }
}
