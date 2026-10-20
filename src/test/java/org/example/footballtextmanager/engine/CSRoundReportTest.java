package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSMatchEvent;
import org.example.footballtextmanager.model.CSMatchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round review is the one page a manager reads after every fixture, so it has to say something
 * about the round that just happened.
 *
 * <p>The generator it replaces carried a headline option with no fact in it at all — "24 total goals
 * were logged this round, and several coaches will spend the week replaying the bigger moments" —
 * and described a goalless draw as "one for the purists", including when it was the manager's own
 * match.
 */
class CSRoundReportTest {

    private static CSMatchEvent goal(int minute, String team, String scorer) {
        return CSMatchEvent.builder()
                .minute(minute)
                .eventType(org.example.footballtextmanager.model.CSEventType.GOAL)
                .playerName(scorer)
                .teamName(team)
                .build();
    }

    private static CSMatchEvent shot(int minute, String team, String player, boolean onTarget) {
        return CSMatchEvent.builder()
                .minute(minute)
                .eventType(onTarget
                        ? org.example.footballtextmanager.model.CSEventType.SHOT_ON_TARGET
                        : org.example.footballtextmanager.model.CSEventType.SHOT_OFF_TARGET)
                .playerName(player)
                .teamName(team)
                .build();
    }

    private static CSMatchResult match(String home, String away, int hg, int ag, List<CSMatchEvent> events) {
        CSMatchResult r = new CSMatchResult();
        r.setHomeTeamId(Math.abs(home.hashCode()) + 1L);
        r.setAwayTeamId(Math.abs(away.hashCode()) + 1L);
        r.setHomeTeamName(home);
        r.setAwayTeamName(away);
        r.setHomeGoals(hg);
        r.setAwayGoals(ag);
        r.setRound(6);
        r.setEvents(new ArrayList<>(events));
        r.setHomePlayerStats(List.of());
        r.setAwayPlayerStats(List.of());
        return r;
    }

    @Test
    void theRoundHeadlineNeverCarriesAFactlessSentence() {
        // Every seed, every round shape: the filler line must not be reachable.
        for (int seed = 0; seed < 60; seed++) {
            List<CSMatchResult> results = List.of(
                    match("A", "B", 2, 1, List.of(goal(20, "A", "X"))),
                    match("C", "D", 0, 0, List.of()),
                    match("E", "F", 4, 0, List.of(goal(10, "E", "Y"), goal(80, "E", "Z"))));

            String text = new CSMatchReportGenerator(new Random(seed))
                    .buildRoundReport(6, results, null);

            assertFalse(text.contains("several coaches will spend the week"),
                    "the factless headline came back:\n" + text);
            assertFalse(text.contains("bigger moments"),
                    "the factless headline came back:\n" + text);
        }
    }

    @Test
    void aGoallessMatchIsNotDescribedAsAnEnthusiasticMasterpiece() {
        // "One for the purists" was what a manager was told about his own 0-0 draw.
        for (int seed = 0; seed < 30; seed++) {
            List<CSMatchResult> results = List.of(
                    match("A", "B", 2, 1, List.of(goal(20, "A", "X"))),
                    match("C", "D", 0, 0, List.of(shot(30, "C", "P", true), shot(44, "D", "Q", false))));

            String text = new CSMatchReportGenerator(new Random(seed))
                    .buildRoundReport(6, results, null);

            assertFalse(text.contains("for the purists"),
                    "a goalless draw was praised as an enthusiast's treat:\n" + text);
            assertFalse(text.contains("organisation first, fireworks second"),
                    "the old filler survived:\n" + text);
        }
    }

    @Test
    void aGoallessMatchStatesTheScorelineAndTheShotCount() {
        List<CSMatchResult> results = List.of(
                match("OFK Omladinac", "FK Napredak", 0, 0, List.of(
                        shot(30, "OFK Omladinac", "P", true), shot(44, "FK Napredak", "Q", true),
                        shot(70, "FK Napredak", "R", false))));

        String text = new CSMatchReportGenerator(new Random(1)).buildRoundReport(6, results, null);

        assertTrue(text.contains("OFK Omladinac 0:0 FK Napredak"),
                "the scoreline was not stated:\n" + text);
        assertTrue(text.contains("3 shots"),
                "the real shot count was not stated:\n" + text);
    }

    @Test
    void aDrawIsNeverDescribedAsThoughSomebodyWon() {
        // "the winner's last goal" was printed under a 1-1.
        for (int seed = 0; seed < 20; seed++) {
            List<CSMatchResult> results = List.of(
                    match("FK Metalac", "FK Sloga", 1, 1, List.of(
                            goal(3, "FK Metalac", "Nemanja Stankovic"),
                            goal(67, "FK Sloga", "Dragan Petrovic"))));

            String text = new CSMatchReportGenerator(new Random(seed))
                    .buildRoundReport(6, results, null);

            assertFalse(text.contains("winner"),
                    "a draw was reported as a win:\n" + text);
        }
    }

    @Test
    void minutesAreWrittenAsProperOrdinals() {
        // "in the 83th minute" came straight from interpolating the number into the word.
        assertEquals("83rd", CSMatchReportGenerator.ordinalMinute(83));
        assertEquals("3rd", CSMatchReportGenerator.ordinalMinute(3));
        assertEquals("21st", CSMatchReportGenerator.ordinalMinute(21));
        assertEquals("22nd", CSMatchReportGenerator.ordinalMinute(22));
        assertEquals("11th", CSMatchReportGenerator.ordinalMinute(11));
        assertEquals("12th", CSMatchReportGenerator.ordinalMinute(12));
        assertEquals("13th", CSMatchReportGenerator.ordinalMinute(13));
        assertEquals("111th", CSMatchReportGenerator.ordinalMinute(111));
        assertEquals("90th", CSMatchReportGenerator.ordinalMinute(90));

        List<CSMatchResult> results = List.of(
                match("FK Rudar", "FK Buducnost", 1, 0, List.of(goal(83, "FK Rudar", "X"))));
        String text = new CSMatchReportGenerator(new Random(1)).buildRoundReport(6, results, null);
        assertFalse(text.contains("83th"), "the bad ordinal reached the report:\n" + text);
        assertTrue(text.contains("83rd"), "the correct ordinal is missing:\n" + text);
    }

    /**
     * Summary lines used to begin lowercase ("the match was settled in the 89th minute"), because
     * one of the three phrasings was stored lower-case and interpolated straight into the page.
     *
     * <p>Deliberately not "every line starts with a capital": a headline beginning with a figure is
     * correct. The check is scoped to the lines that follow a scoreline.
     */
    @Test
    void everySummaryLineStartsWithACapital() {
        java.util.regex.Pattern scoreline =
                java.util.regex.Pattern.compile("^\\S+ \\d+:\\d+ \\S+( \\[YOUR MATCH\\])?$");

        for (int seed = 0; seed < 25; seed++) {
            List<CSMatchResult> results = List.of(
                    match("A", "B", 2, 1, List.of(
                            goal(10, "A", "X"), goal(89, "A", "Y"), goal(89, "B", "Z"))),
                    match("C", "D", 1, 1, List.of(goal(20, "C", "P"), goal(40, "D", "Q"))),
                    match("E", "F", 0, 0, List.of()));

            String[] lines = new CSMatchReportGenerator(new Random(seed))
                    .buildRoundReport(6, results, null).split("\n");

            int checked = 0;
            for (int i = 0; i + 1 < lines.length; i++) {
                if (!scoreline.matcher(lines[i].trim()).matches()) continue;
                String summary = lines[i + 1].trim();
                assertFalse(summary.isEmpty(), "a scoreline had no summary after it");
                assertTrue(Character.isUpperCase(summary.charAt(0)),
                        "summary line does not start with a capital: \"" + summary + "\"");
                checked++;
            }
            assertTrue(checked >= 3, "only " + checked + " summary lines were found to check");
        }
    }

    @Test
    void theDecisiveGoalIsNamedWithItsMinute() {
        List<CSMatchResult> results = List.of(
                // 3-2, and the events actually add up to 3-2: two for Sloga at 40 and 80, three
                // for Metalac at 12, 64 and 70. The winner's last goal is the 70th, not the 80th.
                match("FK Metalac", "FK Sloga", 3, 2, List.of(
                        goal(12, "FK Metalac", "Early"),
                        goal(40, "FK Sloga", "Other"),
                        goal(64, "FK Metalac", "Tihomir Vukovic"),
                        goal(70, "FK Metalac", "Third"),
                        goal(80, "FK Sloga", "Late"))));

        String text = new CSMatchReportGenerator(new Random(2)).buildRoundReport(6, results, null);

        assertTrue(text.contains("FK Sloga"), "the fixture was not named:\n" + text);
        assertTrue(text.contains("70") && text.contains("Third"),
                "the winner's last goal and its minute were not both stated:\n" + text);
        assertFalse(text.contains("Late scored") && text.contains("decisive"),
                "the loser's consolation goal was called decisive:\n" + text);
        assertFalse(text.contains("supplied it"),
                "the old non-sentence survived:\n" + text);
        assertFalse(text.contains("Defining moment:"),
                "the old headline form survived:\n" + text);
    }
}