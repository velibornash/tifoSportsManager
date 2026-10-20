package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSMatchEvent;
import org.example.footballtextmanager.model.CSMatchResult;
import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSPlayerMatchStats;
import org.example.footballtextmanager.model.CSTableEntry;
import org.example.footballtextmanager.state.CleanSheetGameState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inbox copy has to be about the match, not about a mood.
 *
 * <p>These tests exist because the previous generators picked sentences at random from pools of
 * three or four. Every one of those old versions passed a test that only checked "the message is
 * non-empty" and every one of them produced copy that referred to no game a manager ever played.
 *
 * <p>So the assertions below are deliberately about <b>facts</b>: the scoreline, the names of the
 * players who scored, the opponent's league record, the rating a call-up projection is based on.
 * A generator that goes back to random sentences fails immediately, which is the point.
 */
class CSInboxNarrativeTest {

    private static final String HOME = "OFK Omladinac";
    private static final String AWAY = "FK Metalac";

    // ---------------------------------------------------------------- helpers

    /** A goal event for the named team. */
    private static CSMatchEvent goal(int minute, String team, String scorer) {
        return CSMatchEvent.builder()
                .minute(minute)
                .eventType(org.example.footballtextmanager.model.CSEventType.GOAL)
                .playerName(scorer)
                .teamName(team)
                .build();
    }

    private static CSMatchEvent card(int minute, String team, String player, boolean red) {
        return CSMatchEvent.builder()
                .minute(minute)
                .eventType(red
                        ? org.example.footballtextmanager.model.CSEventType.RED_CARD
                        : org.example.footballtextmanager.model.CSEventType.YELLOW_CARD)
                .playerName(player)
                .teamName(team)
                .build();
    }

    /**
     * A finished match with an explicit event list, so the narrative can be asserted against a
     * truth that was written down rather than against whatever the simulator rolled.
     */
    private static CSMatchResult match(int homeGoals, int awayGoals, List<CSMatchEvent> events) {
        CSMatchResult result = new CSMatchResult();
        result.setHomeTeamId(1L);
        result.setAwayTeamId(2L);
        result.setHomeTeamName(HOME);
        result.setAwayTeamName(AWAY);
        result.setHomeGoals(homeGoals);
        result.setAwayGoals(awayGoals);
        result.setRound(3);
        result.setEvents(new ArrayList<>(events));
        result.setSummary(HOME + " " + homeGoals + "-" + awayGoals + " " + AWAY);
        result.setHomePlayerStats(List.of());
        result.setAwayPlayerStats(List.of());
        return result;
    }

    private static CSPlayerMatchStats stat(String name, double rating, int goals) {
        return CSPlayerMatchStats.builder()
                .playerName(name)
                .position("ATT")
                .rating(rating)
                .goals(goals)
                .minutesPlayed(90)
                .build();
    }

    /** State with the user's team at home, a squad, and a small table. */
    /**
     * A player with an explicit rating.
     *
     * <p>{@code SquadFixture.player} deliberately leaves rating at 0 so the simulator has to
     * compute it. These tests are about the rating, so they set it themselves rather than
     * smuggling it in through the builder default.
     */
    private static CSPlayer rated(long id, String name, String position, int rating) {
        CSPlayer player = SquadFixture.player(id, name, position, rating);
        player.setRating(rating);
        return player;
    }

    private static CleanSheetGameState state(int round, int played, int points) {
        CleanSheetGameState state = new CleanSheetGameState();
        state.setCurrentRound(round);
        state.setUserTeam(SquadFixture.team(1L, HOME));
        // getTotalRounds() is derived from the schedule, not a field — a season of 34 rounds has
        // to actually be in there.
        List<org.example.footballtextmanager.model.CSFixture> schedule = new ArrayList<>();
        for (int r = 1; r <= 34; r++) {
            schedule.add(org.example.footballtextmanager.model.CSFixture.builder().round(r)
                    .homeTeamId(1L).homeTeamName(HOME).awayTeamId(2L).awayTeamName(AWAY).build());
        }
        state.setSchedule(schedule);
        state.setRoster(new ArrayList<>(SquadFixture.startingEleven(1L, "H", 6)));

        CSTableEntry user = CSTableEntry.builder()
                .teamId(1L).teamName(HOME).points(points).played(played)
                .wins(points / 3).draws(0).losses(0).goalsScored(played).goalsConceded(0)
                .position(4).build();
        CSTableEntry rival = CSTableEntry.builder()
                .teamId(2L).teamName(AWAY).points(points + 3).played(played)
                .wins(3).draws(0).losses(1).goalsScored(9).goalsConceded(4)
                .position(7).build();
        state.setLeagueTable(new ArrayList<>(List.of(rival, user)));
        state.setAllTeams(new ArrayList<>(List.of(
                SquadFixture.team(1L, HOME), SquadFixture.team(2L, AWAY))));
        state.setAllTeamRosters(new java.util.HashMap<>(java.util.Map.of(
                1L, SquadFixture.startingEleven(1L, "H", 6),
                2L, SquadFixture.startingEleven(2L, "A", 5))));
        return state;
    }

    // ------------------------------------------------------- shape detection

    /**
     * The shape decides which questions are asked, so a misread here produces a press conference
     * congratulating a manager on a comeback he never made.
     */
    @Test
    void aWinAfterBeingBehindIsACombackAndNotANarrowWin() {
        CSMatchResult result = match(2, 1, List.of(
                goal(12, AWAY, "Kovac"),
                goal(55, HOME, "Ristic"),
                goal(88, HOME, "Jovanovic")));

        CSMatchFacts facts = CSMatchFacts.from(result, HOME, 1L);
        assertEquals(CSMatchFacts.Shape.COMEBACK, facts.shape());
    }

    @Test
    void aFourGoalComebackIsStillAComback() {
        // A guard written as "was behind AND the final total is small" passes every two-goal
        // comeback and misses every larger one. Written after that defect was reintroduced on
        // purpose and watched go green, which is the only evidence that a test can fail.
        CSMatchResult result = match(4, 3, List.of(
                goal(8, AWAY, "Kovac"),
                goal(19, AWAY, "Milos"),
                goal(31, HOME, "Ristic"),
                goal(40, AWAY, "Pavlovic"),
                goal(55, HOME, "Jovanovic"),
                goal(66, HOME, "Nikolic"),
                goal(88, HOME, "Simic")));

        assertEquals(CSMatchFacts.Shape.COMEBACK,
                CSMatchFacts.from(result, HOME, 1L).shape());
    }

    @Test
    void beingLevelForAnInstantIsNotHavingBeenAhead() {
        // 0-0 level is not a lead. If "ever ahead" counted equality, a clean-sheet win that was
        // briefly level would be reported as a collapse.
        CSMatchResult result = match(1, 0, List.of(goal(90, HOME, "Ristic")));
        assertEquals(CSMatchFacts.Shape.CLEAN_SHEET_WIN,
                CSMatchFacts.from(result, HOME, 1L).shape());
    }

    @Test
    void losingAfterBeingAheadIsACollapse() {
        CSMatchResult result = match(1, 2, List.of(
                goal(9, HOME, "Ristic"),
                goal(40, HOME, "Jovanovic"),
                goal(70, AWAY, "Kovac")));

        CSMatchFacts facts = CSMatchFacts.from(result, HOME, 1L);
        assertEquals(CSMatchFacts.Shape.COLLAPSE, facts.shape());
    }

    @Test
    void aWinScoredFirstAndHeldOnIsNotAComback() {
        CSMatchResult result = match(1, 0, List.of(goal(9, HOME, "Ristic")));

        CSMatchFacts facts = CSMatchFacts.from(result, HOME, 1L);
        assertEquals(CSMatchFacts.Shape.CLEAN_SHEET_WIN, facts.shape());
    }

    @Test
    void aNarrowWinWithNoComebackIsANarrowWin() {
        CSMatchResult result = match(2, 1, List.of(
                goal(30, HOME, "Ristic"),
                goal(31, AWAY, "Kovac"),
                goal(77, HOME, "Jovanovic")));

        CSMatchFacts facts = CSMatchFacts.from(result, HOME, 1L);
        assertEquals(CSMatchFacts.Shape.NARROW_WIN, facts.shape());
    }

    @Test
    void aHeavyWinIsARoutAndAGoallessDrawIsRecognised() {
        CSMatchFacts rout = CSMatchFacts.from(
                match(4, 0, List.of(goal(5, HOME, "A"), goal(20, HOME, "B"),
                        goal(50, HOME, "C"), goal(80, HOME, "D"))), HOME, 1L);
        assertEquals(CSMatchFacts.Shape.CLEAN_SHEET_WIN, rout.shape());

        CSMatchFacts thrashing = CSMatchFacts.from(
                match(0, 4, List.of(goal(5, AWAY, "A"), goal(20, AWAY, "B"),
                        goal(50, AWAY, "C"), goal(80, AWAY, "D"))), HOME, 1L);
        assertEquals(CSMatchFacts.Shape.THRASHING, thrashing.shape());

        CSMatchFacts goalless = CSMatchFacts.from(match(0, 0, List.of()), HOME, 1L);
        assertEquals(CSMatchFacts.Shape.GOALLESS, goalless.shape());
    }

    // ------------------------------------------------- post-match conference

    @Test
    void thePostMatchConferenceNamesTheScoreAndEveryScorer() {
        CSMatchResult result = match(2, 1, List.of(
                goal(12, AWAY, "Kovac"),
                goal(55, HOME, "Ristic"),
                goal(88, HOME, "Jovanovic"),
                card(60, HOME, "Nikolic", false),
                card(70, HOME, "Simic", true)));
        result.setHomePlayerStats(List.of(stat("Jovanovic", 8.5, 1)));

        String text = new CSInboxGenerator(new Random(1))
                .generatePostMatchPressConference(state(3, 3, 6), result);

        // The scoreline, in the orientation of the side being written about.
        assertTrue(text.contains("2-1"), "score missing:\n" + text);
        // Every scorer, by name. This is the assertion that a random-sentence generator fails.
        assertTrue(text.contains("Ristic"), "home scorer missing:\n" + text);
        assertTrue(text.contains("Jovanovic"), "home scorer missing:\n" + text);
        assertTrue(text.contains("Kovac"), "opponent scorer missing:\n" + text);
        // And the incidents, which are real events in the result.
        assertTrue(text.contains("red"), "red card count missing:\n" + text);
    }

    @Test
    void aCollapseIsAskedAboutTheCollapseAndNotGivenAGenericQuestion() {
        CSMatchResult collapsed = match(1, 2, List.of(
                goal(9, HOME, "Ristic"),
                goal(40, HOME, "Jovanovic"),
                goal(70, AWAY, "Kovac")));

        String text = new CSInboxGenerator(new Random(2))
                .generatePostMatchPressConference(state(3, 3, 3), collapsed);

        assertTrue(text.contains("ahead"), "a thrown-away lead was never mentioned:\n" + text);
    }

    @Test
    void aComebackAndARoutProduceDifferentConferences() {
        // Same result, opposite stories: 2-1 from behind versus 2-1 with the opener first.
        CSMatchResult comeback = match(2, 1, List.of(
                goal(12, AWAY, "Kovac"), goal(55, HOME, "Ristic"), goal(88, HOME, "Jovanovic")));
        CSMatchResult controlled = match(2, 1, List.of(
                goal(12, HOME, "Ristic"), goal(20, AWAY, "Kovac"), goal(88, HOME, "Jovanovic")));

        CSInboxGenerator generator = new CSInboxGenerator(new Random(7));
        String comebackText = generator.generatePostMatchPressConference(state(3, 3, 6), comeback);
        String controlledText = generator.generatePostMatchPressConference(state(3, 3, 6), controlled);

        assertFalse(comebackText.equals(controlledText),
                "a comeback and a controlled win produced identical copy");
    }

    /**
     * These four came out of reading a real 0-3 defeat in the running application. Every one of them
     * was a false statement about a match that had already happened, and none was covered by a test.
     */
    @Test
    void aThrashingDoesNotClaimTheManagerWasLevelAtHalfTime() {
        // 0-3 with the first goal in minute 9. The copy asserted "we were level with them at
        // half-time", which is simply untrue, and it called the opposition scorers "ours".
        CSMatchResult result = match(0, 3, List.of(
                goal(9, AWAY, "Stevanovic"),
                goal(11, AWAY, "Sekulic"),
                goal(74, AWAY, "Sekulic")));

        String text = new CSInboxGenerator(new Random(11))
                .generatePostMatchPressConference(state(3, 3, 0), result);

        assertFalse(text.contains("level with them at half-time"),
                "claimed to have been level at half-time in a 0-3 defeat:\n" + text);
        assertFalse(text.contains("scorers for us were"),
                "called the opposition's scorers ours:\n" + text);
        // And it must actually name them as theirs.
        assertTrue(text.contains("They scored through"), "did not credit the opposition scorers:\n" + text);
    }

    @Test
    void anOpposingStandoutIsNeverPraisedAsIfItWereOurs() {
        // The opponent's striker was the best player on the pitch. The manager was told he was the
        // best player and handed him his own man of the match.
        CSMatchResult result = match(0, 2, List.of(
                goal(20, AWAY, "Stevanovic"),
                goal(70, AWAY, "Sekulic")));
        result.setHomePlayerStats(List.of(stat("Ristic", 5.0, 0)));
        result.setAwayPlayerStats(List.of(stat("Stevanovic", 9.1, 2)));

        String text = new CSInboxGenerator(new Random(12))
                .generatePostMatchPressConference(state(3, 3, 3), result);

        assertTrue(text.contains("concede"),
                "was never asked to concede the opposing standout:\n" + text);
        assertFalse(text.contains("gets my man of the match"),
                "handed the opposition player his own man of the match:\n" + text);
    }

    @Test
    void ourOwnStandoutIsStillCreditedWithTheManOfTheMatch() {
        // The mirror of the test above, so the fix cannot be "never mention the man of the match".
        CSMatchResult result = match(2, 1, List.of(
                goal(20, HOME, "Ristic"),
                goal(55, AWAY, "Sekulic"),
                goal(88, HOME, "Jovanovic")));
        result.setHomePlayerStats(List.of(stat("Ristic", 9.2, 1)));
        result.setAwayPlayerStats(List.of(stat("Sekulic", 6.0, 1)));

        String text = new CSInboxGenerator(new Random(13))
                .generatePostMatchPressConference(state(3, 3, 6), result);

        assertTrue(text.contains("man of the match"), "our own standout was not credited:\n" + text);
        assertTrue(text.contains("Ristic"), "the wrong player was credited:\n" + text);
    }

    @Test
    void aDefeatDoesNotTellTheSquadTheyGetTheCreditForWinning() {
        CSMatchResult rout = match(0, 4, List.of(
                goal(9, AWAY, "A"), goal(20, AWAY, "B"), goal(50, AWAY, "C"), goal(80, AWAY, "D")));

        String text = new CSInboxGenerator(new Random(14))
                .generatePostMatchPressConference(state(3, 3, 0), rout);

        assertFalse(text.contains("credit for winning"),
                "told a losing manager the squad gets the credit for winning:\n" + text);
    }

    @Test
    void noAnswerSplicesOneSentenceIntoTheMiddleOfAnother() {
        // A helper returned whole sentences ("That was a chastening afternoon.") and was then
        // embedded in a longer sentence, producing "Holding That was a chastening afternoon.
        // together for ninety minutes is one problem". The first version of this test looked for a
        // literal ". ." that never occurs, and passed while the defect was live — reintroducing the
        // defect proved it. This checks the actual signature: a capitalised "That was"/"This was"
        // appearing mid-sentence.
        // A lookbehind, not \b: a word boundary can never be followed by a lowercase letter, so
        // the obvious version of this regex matches nothing at all and silently passes forever.
        java.util.regex.Pattern splice =
                java.util.regex.Pattern.compile("(?<=[a-z] )(?:That|This|It) was\\b");

        for (int seed = 0; seed < 25; seed++) {
            CSMatchResult thrashing = match(0, 3, List.of(
                    goal(9, AWAY, "A"), goal(30, AWAY, "B"), goal(60, AWAY, "C")));
            String loss = new CSInboxGenerator(new Random(seed))
                    .generatePostMatchPressConference(state(3, 3, 0), thrashing);
            assertFalse(splice.matcher(loss).find(),
                    "spliced sentence in a loss:\n" + loss);

            // 4-0 would be a CLEAN_SHEET_WIN, which never reaches managementAnswer at all. A rout
            // needs a goal conceded as well, or the assertion is vacuous for the winning branch.
            CSMatchResult rout = match(4, 1, List.of(
                    goal(9, HOME, "A"), goal(30, HOME, "B"), goal(45, AWAY, "D"),
                    goal(60, HOME, "C"), goal(80, HOME, "D2")));
            String win = new CSInboxGenerator(new Random(seed))
                    .generatePostMatchPressConference(state(3, 3, 12), rout);
            assertFalse(splice.matcher(win).find(),
                    "spliced sentence in a win:\n" + win);
        }
    }

    // --------------------------------------------------------------- media

    @Test
    void everyMediaRoundUpCarriesTheActualScore() {
        CSMatchResult result = match(3, 2, List.of(
                goal(10, HOME, "Ristic"), goal(30, HOME, "Jovanovic"), goal(45, AWAY, "Kovac"),
                goal(60, AWAY, "Milos"), goal(75, HOME, "Nikolic")));
        result.setHomePlayerStats(List.of(stat("Ristic", 9.0, 2)));

        String text = new CSInboxGenerator(new Random(3))
                .generateMediaHeadlines(state(3, 3, 6), result, "WIN");

        assertTrue(text.contains(HOME + " 3-2 " + AWAY), "scoreline missing:\n" + text);
        assertTrue(text.contains("3-2") || text.contains("Ristic"),
                "no headline carried a fact about the match:\n" + text);
    }

    // -------------------------------------------------------- pre-match desk

    @Test
    void thePreMatchConferenceStatesTheOpponentsRealRecord() {
        CleanSheetGameState state = state(4, 3, 6);
        // The state helper gives the opponent 6 points from 3 with 3 wins — check it is echoed.
        CSInboxGenerator generator = new CSInboxGenerator(new Random(4));

        org.example.footballtextmanager.model.CSFixture fixture =
                org.example.footballtextmanager.model.CSFixture.builder()
                        .round(4)
                        .homeTeamId(1L).homeTeamName(HOME)
                        .awayTeamId(2L).awayTeamName(AWAY)
                        .build();

        String text = generator.generatePreMatchPressConference(state, fixture);

        assertTrue(text.contains(AWAY), "the opponent was never named:\n" + text);
        assertTrue(text.contains("Scouting:"), "no scouting line:\n" + text);
        // The record line must carry the real numbers, not an adjective.
        assertTrue(text.contains("7"), "the opponent's position was not stated:\n" + text);
        assertTrue(text.contains("points from"), "no record line at all:\n" + text);
    }

    // ------------------------------------------------------------ call-ups

    @Test
    void theCallUpProjectionIsRatingDrivenAndNotRandom() {
        // Rating lives on a 1-10 scale. One clear international at the top, the rest squad players.
        List<CSPlayer> roster = new ArrayList<>();
        roster.add(rated(90L, "Marko Ristic", "ATT", 10));
        for (int i = 0; i < 6; i++) {
            roster.add(rated(100L + i, "Squad " + i, "MID", 3));
        }

        assertEquals(1, CSInboxGenerator.projectedCallUps(roster).size(),
                "only the standout should be projected");
        assertEquals("Marko Ristic", CSInboxGenerator.projectedCallUps(roster).getFirst().getName());

    }

    @Test
    void theThresholdIsInclusiveAtTheBoundary() {
        // An absolute bar on the 1-10 rating scale: a 7 is projected and a 6 is not. An off-by-one
        // here silently drops a real international or invents one.
        List<CSPlayer> roster = List.of(
                rated(1L, "On the line", "ATT", CSInboxGenerator.CALL_UP_RATING),
                rated(2L, "Just below", "DEF", CSInboxGenerator.CALL_UP_RATING - 1));

        List<String> names = CSInboxGenerator.projectedCallUps(roster).stream()
                .map(CSPlayer::getName).toList();

        assertTrue(names.contains("On the line"), "a player exactly on the threshold was dropped");
        assertFalse(names.contains("Just below"), "a player below the threshold was projected");
    }

    /**
     * The rule was "squad best minus four", which on a 1-10 scale selected every player in the
     * club. The bulletin reported fifteen of fifteen as internationals, which is a tautology and
     * not a selection model.
     */
    @Test
    void aRealSquadDoesNotProjectEverybodyAsInternational() {
        List<CSPlayer> roster = new ArrayList<>();
        // The squad the running application actually has: one 8, a few 5-6, the rest 3-4.
        roster.add(rated(1L, "Star", "ATT", 8));
        for (int i = 0; i < 5; i++) {
            roster.add(rated(10L + i, "Good " + i, "MID", 6));
        }
        for (int i = 0; i < 9; i++) {
            roster.add(rated(50L + i, "Squad " + i, "DEF", 3 + (i % 2)));
        }

        List<CSPlayer> projected = CSInboxGenerator.projectedCallUps(roster);
        assertTrue(projected.size() <= 2,
                "projected " + projected.size() + " of " + roster.size()
                        + " — a relative threshold selects the whole squad");
    }

    @Test
    void aWeakSquadCorrectlyProjectsNobody() {
        List<CSPlayer> roster = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            roster.add(rated(200L + i, "Squad " + i, "DEF", 4));
        }
        assertTrue(CSInboxGenerator.projectedCallUps(roster).isEmpty(),
                "a squad with nobody at international level still produced call-ups");
    }

    @Test
    void theCallUpProjectionIsStableAcrossRepeatedCalls() {
        List<CSPlayer> roster = new ArrayList<>();
        roster.add(rated(1L, "A", "ATT", 9));
        for (int i = 0; i < 10; i++) {
            roster.add(rated(10L + i, "P" + i, "MID", 4 + (i % 3)));
        }
        String first = CSInboxGenerator.projectedCallUps(roster).toString();
        for (int i = 0; i < 50; i++) {
            assertEquals(first, CSInboxGenerator.projectedCallUps(roster).toString(),
                    "the projection changed between identical calls");
        }
    }

    @Test
    void theCallUpWatchNamesRealPlayersAndTheRealCost() {
        CleanSheetGameState state = state(5, 5, 7);
        List<CSPlayer> roster = new ArrayList<>(state.getRoster());
        roster.add(rated(999L, "Dejan Petrovic", "ST", 9));
        state.setRoster(roster);

        String text = new CSInboxGenerator(new Random(5))
                .generateNationalTeamUpdate(state);
        assertNotNull(text, "no call-up message produced");
        assertTrue(text.contains("Petrovic"), "the only international was not named:\n" + text);
        assertTrue(text.contains("Domestic cover"), "the cost was never stated:\n" + text);
        assertTrue(text.contains("round"), "no round was mentioned:\n" + text);
    }

    // ---------------------------------------------------------- milestones

    @Test
    void aMilestoneNamesARealPlayerAndTheRealTally() {
        CleanSheetGameState state = state(6, 6, 10);
        List<CSPlayer> roster = new ArrayList<>(state.getRoster());
        CSPlayer scorer = rated(500L, "Nikola Jovanovic", "ATT", 7);
        scorer.setGoals(9);
        roster.add(scorer);
        state.setRoster(roster);

        String text = new CSInboxGenerator(new Random(6)).generateMilestoneAnnouncement(state);
        assertNotNull(text, "no milestone produced despite a player on 9 goals");
        assertTrue(text.contains("Nikola Jovanovic"), "the player was not named:\n" + text);
        assertTrue(text.contains("9"), "the real tally was not stated:\n" + text);
        assertFalse(text.contains("Future marker on the horizon"),
                "the old placeholder headline survived");
    }
}