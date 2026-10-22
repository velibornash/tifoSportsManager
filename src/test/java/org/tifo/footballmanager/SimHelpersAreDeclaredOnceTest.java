package org.tifo.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.util.TieWinners;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The geometry, the tie winner, the season row and "is he a keeper" are each answered in one place.
 *
 * <p>All four were copied out of each other, and the copies had started to say different things:
 * {@code pointToLineDistance} was the same arithmetic as {@code pointSegmentDistance} under a name that
 * claimed to measure against a line, and the decision engine's keeper test asked the role string while the
 * lineup's asked the effective role.
 *
 * <p><b>What is checked.</b> The one rule whose behaviour can differ by copy — who won the tie — is tested
 * as behaviour. The rest are checked by shape, because "it is declared once" is a claim about the source
 * and not about a result: every behavioural test of a cup draw passed while three copies of {@code
 * winnerOf} sat in three files.
 */
class SimHelpersAreDeclaredOnceTest {

    @Test
    @DisplayName("a tie is decided by the scoreline, then by the shootout, and never by a coin")
    void theTieWinnerRule() {
        Team home = new Team();
        home.setName("Home");
        Team away = new Team();
        away.setName("Away");

        assertSame(home, TieWinners.winnerOf(tie(home, away, 2, 1, null, null)));
        assertSame(away, TieWinners.winnerOf(tie(home, away, 0, 3, null, null)));

        // Level, settled from the spot: the shootout decides, and the scoreline stays as it finished.
        assertSame(home, TieWinners.winnerOf(tie(home, away, 1, 1, 4, 3)));
        assertSame(away, TieWinners.winnerOf(tie(home, away, 1, 1, 2, 5)));

        // Level with no shootout, or one that cannot be read: nothing is invented.
        assertNull(TieWinners.winnerOf(tie(home, away, 1, 1, null, null)));
        assertNull(TieWinners.winnerOf(tie(home, away, 1, 1, 3, 3)));

        // A fixture that has not been played has no winner, and neither has nothing.
        MatchFixture unplayed = new MatchFixture();
        unplayed.setHomeTeam(home);
        unplayed.setAwayTeam(away);
        assertNull(TieWinners.winnerOf(unplayed));
        assertNull(TieWinners.winnerOf(null));
    }

    @Test
    @DisplayName("no file keeps its own copy of the segment distance, the tie winner, the season row or the keeper test")
    void theCopiesAreGone() throws IOException {
        List<String> segments = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> tieWinners = new ArrayList<>();
        List<String> seasonRows = new ArrayList<>();
        List<String> keepers = new ArrayList<>();

        try (Stream<Path> files = Files.walk(ProductionSource.root())) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                String where = ProductionSource.root().relativize(file).toString();
                for (String line : Files.readAllLines(file)) {
                    String stripped = line.strip();
                    if (stripped.startsWith("*") || stripped.startsWith("/*") || stripped.startsWith("//")) {
                        continue;
                    }
                    if (line.contains("pointSegmentDist(")) {
                        segments.add(where);
                    }
                    if (line.contains("pointToLineDistance(") && !where.endsWith("DecisionTraceDiag.java")) {
                        lines.add(where);
                    }
                    if (line.contains("Team winnerOf(MatchFixture")) {
                        tieWinners.add(where);
                    }
                    if (line.contains("new SeasonCompetition()")
                            && !where.endsWith("SeasonCompetitionRepository.java")
                            && !where.endsWith("DatabaseInitializer.java")) {
                        seasonRows.add(where);
                    }
                    // This game's own helpers. The Clean Sheet text manager has one too, for its own
                    // player type - three games share this source tree and only one of them is this one.
                    if (where.startsWith("org/tifo/footballmanager/")
                            && (line.contains("boolean isKeeper(") || line.contains("boolean isGoalkeeper("))) {
                        keepers.add(where);
                    }
                }
            }
        }

        assertEquals(List.of(), segments,
                "the segment distance was copy-pasted into GoalPhysical, BallPhysicsEngine and "
                        + "BallPhysicsProbe. SimUtils.pointSegmentDistance(row, column, a, b) is the one");
        assertEquals(List.of(), lines,
                "pointToLineDistance was the same arithmetic as pointSegmentDistance under a name that "
                        + "claims a line. DecisionTraceDiag keeps its own on purpose - an instrument that "
                        + "agrees with the engine by construction measures nothing - and nothing else may");
        assertEquals(List.of("org/tifo/footballmanager/newLogic/util/TieWinners.java",
                        "org/tifo/footballmanager/newLogic/service/HonourService.java"), tieWinners,
                "three cup draws carried near-identical copies of winnerOf. TieWinners.winnerOf(tie) is the "
                        + "rule; HonourService's is listed here because it is NOT the same rule - it ignores "
                        + "the penalty columns, so consolidating it would change who is crowned");
        assertEquals(List.of(), seasonRows,
                "the find-or-create of a season row was written three times, twice byte for byte. "
                        + "SeasonCompetitionRepository.ensure(competition, seasonYear) is the one");
        assertEquals(List.of("org/tifo/footballmanager/newLogic/model/Player.java",
                        "org/tifo/footballmanager/newLogic/sim/model/Player.java"), keepers,
                "the keeper question had four answers. model.Player.isGoalkeeper() (by effectiveRole) and "
                        + "the simulation model's own are the two that remain, and they answer different "
                        + "objects");
    }

    private static MatchFixture tie(Team home, Team away, int homeGoals, int awayGoals,
                                    Integer homePens, Integer awayPens) {
        Match played = new Match();
        played.setHomeGoals(homeGoals);
        played.setAwayGoals(awayGoals);
        played.setHomePenaltyGoals(homePens);
        played.setAwayPenaltyGoals(awayPens);

        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setPlayedMatch(played);
        return fixture;
    }
}
