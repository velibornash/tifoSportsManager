package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.CompetitionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rating replays cannot start fetching the per-tick event log again.
 *
 * <p><b>The defect this guards.</b> {@code Match.eventJson} is 742 KB to 1,035 KB on every simulated
 * match — the whole per-tick decision log in one text column. Both Elo replays read a match's date, its
 * two sides and its score and never touch it, but returning {@code Match} entities made Hibernate select
 * it anyway: 89,280 matches a season is <b>~66 GB of Strings in one result list</b>. Measured on the real
 * rows, the entity shape costs 443–554 ms for 155 matches against 6.5–11.2 ms for the projection.
 *
 * <p><b>Why a reflection test and not a timing test.</b> A timing assertion on a test database would
 * pass on the 155-row fixture no matter what, which is how a regression here would get in. This asserts
 * the shape of the query and of the record that carries it, and it fails the moment either grows a field
 * or a property that can hold the blob.
 */
class ScoredMatchCarriesNoBlobTest {

    /** The three columns on {@code Match} that hold a whole match's worth of JSON. */
    private static final List<String> BLOB_PROPERTIES = List.of("eventJson", "lineupJson", "statsJson");

    @Test
    @DisplayName("the projection has no field that could hold the blob")
    void theProjectionCarriesNoBlobField() {
        List<String> components = Arrays.stream(ScoredMatch.class.getRecordComponents())
                .map(c -> c.getName())
                .toList();

        assertEquals(List.of("id", "homeTeamId", "homeTeamName", "awayTeamId", "awayTeamName",
                        "homeGoals", "awayGoals", "scope", "type"),
                components,
                "ScoredMatch changed shape. Its two Strings are team names and nothing else; a field "
                        + "here is a field the replays will select, and the blob is 742 KB a match.");

        for (String blob : BLOB_PROPERTIES) {
            assertFalse(components.contains(blob),
                    "ScoredMatch now has a " + blob + " field, which puts 66 GB back on the replay's heap.");
        }
    }

    @Test
    @DisplayName("neither replay query selects a blob column")
    void neitherReplayQuerySelectsABlobColumn() throws Exception {
        for (String methodName : List.of("findPlayedClubScoredInOrder", "findPlayedScoredByCompetitionTypeInOrder")) {
            Method method = find(methodName);
            String jpql = method.getAnnotation(Query.class).value();
            String normalised = jpql.toLowerCase(Locale.ROOT);

            for (String blob : BLOB_PROPERTIES) {
                assertFalse(normalised.contains(blob.toLowerCase(Locale.ROOT)),
                        methodName + " selects " + blob + ". The replays do not read it and it is 742 KB a "
                                + "match; see ScoredMatch.");
            }
        }
    }

    @Test
    @DisplayName("the replay readers hand back projections, not Match entities")
    void theReplayReadersReturnProjections() throws Exception {
        for (String methodName : List.of("findPlayedClubScoredInOrder", "findPlayedScoredByCompetitionTypeInOrder")) {
            Method method = find(methodName);
            assertEquals(List.class, method.getReturnType(), methodName + " no longer returns a List");
            assertEquals(ScoredMatch.class,
                    ((java.lang.reflect.ParameterizedType) method.getGenericReturnType())
                            .getActualTypeArguments()[0],
                    methodName + " returns something other than ScoredMatch. A List<Match> here is the "
                            + "66 GB regression, whatever the method is called.");
        }
    }

    @Test
    @DisplayName("the entity-returning replay readers are gone, not left beside the new ones")
    void theOldEntityReadersAreGone() {
        assertFalse(hasMethod("findPlayedClubMatchesInOrder"),
                "findPlayedClubMatchesInOrder returned List<Match> and had one caller, the replay. If a "
                        + "caller needs it again it needs the projection, not the blob.");
        assertFalse(hasMethod("findPlayedByCompetitionTypeOrderByMatchDateAscIdAsc"),
                "findPlayedByCompetitionTypeOrderByMatchDateAscIdAsc returned List<Match> and had one "
                        + "caller, the international replay.");
    }

    @Test
    @DisplayName("the club replay still excludes international club cups, by type")
    void theClubReplayStillExcludesInternationalClubCups() throws Exception {
        String jpql = find("findPlayedClubScoredInOrder").getAnnotation(Query.class).value();

        assertTrue(jpql.contains(CompetitionType.LEAGUE.name()) && jpql.contains(CompetitionType.CUP.name()),
                "Leagues and domestic cups are the club pool; dropping either narrows what gets rated.");
        assertFalse(jpql.contains(CompetitionType.INTERNATIONAL.name()),
                "A national side plays in an INTERNATIONAL competition and a club never does, so this "
                        + "type test is the whole club-versus-national split. Widening it would rate "
                        + "national sides on the club ladder.");
    }

    private Method find(String name) {
        return Arrays.stream(MatchRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is gone from MatchRepository"));
    }

    private boolean hasMethod(String name) {
        return Arrays.stream(MatchRepository.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals(name));
    }
}
