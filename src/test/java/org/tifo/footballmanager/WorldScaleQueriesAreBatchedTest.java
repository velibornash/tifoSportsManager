package org.tifo.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.newLogic.service.SeasonService;
import org.tifo.footballmanager.newLogic.service.YouthAcademyService;
import org.tifo.footballmanager.newLogic.sim.SimMatchService;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loops that walk the whole world ask the database once, not once per row.
 *
 * <p>Each of these was measured against the world this game actually holds — 48 countries, 31 divisions
 * each, 14,976 clubs — and each of them fired a query per row of that scale:
 *
 * <ul>
 *   <li>the season's youth intake loaded <b>every club</b> to find the handful with a junior school;</li>
 *   <li>a match read its players back with one {@code findById} per player, four times over;</li>
 *   <li>pyramids looked a club up <b>by name</b> once per existing club;</li>
 *   <li>promotion and relegation asked for two things per child league.</li>
 * </ul>
 *
 * <p><b>This asserts the shape of the query, not the result.</b> Every behavioural test for these four
 * passed while they cost that much, because correctness and cost are different properties and only one
 * of them was being checked. The source is read rather than the calls spied on because three of the four
 * sites are buried in long world-building loops that no unit test drives end to end.
 */
class WorldScaleQueriesAreBatchedTest {

    @Test
    @DisplayName("the youth intake asks for the clubs that can have one, not every club in the world")
    void theIntakeDoesNotWalkEveryClub() throws IOException {
        String source = code(YouthAcademyService.class);
        assertEquals(0, count(source, "findClubTeamsForOperations"),
                "the intake loaded every club in the world and skipped the ones that cannot roll an "
                        + "intake in memory. Ask findClubsWithAnActiveAcademy() instead");
        assertTrue(source.contains("findClubsWithAnActiveAcademy"),
                "the intake must ask for the clubs that can actually have one");
        assertEquals(0, count(source, "countVisibleByTeamIdAndStatus"),
                "the free-slot count was one query per club. It is one grouped query now");
        assertEquals(0, count(source, "countByTeamIdAndArrivalSeasonNumberAndArrivalWeekNumber"),
                "\"has this club already rolled?\" was one query per club. It is one query for the "
                        + "world now - findTeamIdsWithIntakeIn");
    }

    @Test
    @DisplayName("a match reads its players back in one query per pass, not one per player")
    void matchPersistenceDoesNotFetchPlayerByPlayer() throws IOException {
        String source = code(SimMatchService.class);
        assertEquals(0, count(source, "playerRepository.findById"),
                "the stats, career totals, condition and zone load each fetched its players one at a "
                        + "time - the same twenty-two players, four times, once per player. "
                        + "loadPlayers() reads them in one query");
        assertTrue(source.contains("findAllById"),
                "loadPlayers() must be the one place a match's players are read back");
    }

    @Test
    @DisplayName("promotion and relegation ask for a tier's tables, not one table at a time")
    void promotionReadsTheTierNotTheLeague() throws IOException {
        String source = code(SeasonService.class);
        assertEquals(0, count(source, "findByCompetitionAndSeasonYear(childLeague"),
                "the child league's season competition was one query per child league. The tier is "
                        + "read in one query: findByCompetitionInAndSeasonYear");
        assertEquals(0, count(source, "findBySeasonCompetition(childSeasonCompetition)"),
                "the child league's table was one query per child league. The tier's tables are read "
                        + "in one query: findBySeasonCompetitionIn");
        assertTrue(source.contains("findByCompetitionInAndSeasonYear")
                        && source.contains("findBySeasonCompetitionIn"),
                "both reads must be batched for the whole tier");
    }

    /** The file without its comment lines — the javadoc explains the old query, it does not issue it. */
    private static String code(Class<?> type) throws IOException {
        StringBuilder kept = new StringBuilder();
        for (String line : Files.readAllLines(ProductionSource.file(type))) {
            String stripped = line.strip();
            if (stripped.startsWith("*") || stripped.startsWith("/*") || stripped.startsWith("//")) {
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static int count(String haystack, String needle) {
        int seen = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            seen++;
        }
        return seen;
    }
}
