package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The discipline rules are actually applied by a simulation (T-DISC).
 *
 * <p><b>This class exists because the rules shipped green and unwired.</b> A red-card ban was implemented,
 * tested and unreachable: nothing recorded the cards a player picked up, and nothing served the bans. The
 * tests all passed, and a manager whose player took a red card still played the next match — the exact
 * failure this log has now recorded three times.
 *
 * <p><b>These are structural guards and are described as such.</b> Proving the wiring behaviourally means
 * simulating a match, which means seeding a competition, drawing a fixture and running ninety minutes of
 * engine — the same reason `OrchestratorConsultsTheResolverTest` reads its subject's source. What they
 * assert is that the three call sites exist and are ordered correctly, which is the part that was missing.
 * The arithmetic behind the rules is covered behaviourally by {@link DisciplineServiceTest}.
 */
class DisciplineIsAppliedInASimulationTest {

    private static final Path SIM =
            Path.of("src/main/java/org/example/footballmanager/newLogic/sim/SimMatchService.java");

    private static String source() throws IOException {
        assertTrue(Files.exists(SIM), "the simulation service is not where this test looks, so every "
                + "check below is vacuous");
        String text = Files.readString(SIM, StandardCharsets.UTF_8);
        assertTrue(text.contains("public class SimMatchService"),
                "the file no longer holds the simulation service this test guards");
        return text;
    }

    @Test
    @DisplayName("bans are served before the squads are chosen")
    void bansAreServedBeforeTheSquadsAreBuilt() throws IOException {
        String text = source();

        assertTrue(text.contains("discipline.serve("),
                "a ban is never spent, so a red card would follow a player for the rest of the season");
        assertTrue(text.contains("discipline.suspensionFor("),
                "nothing asks whether a player may play, so a suspended player would be picked");

        int serve = text.indexOf("serveSuspensions(fixture)");
        int squad = text.indexOf("loadRealSquad(homeTeam");
        assertTrue(serve > 0 && squad > 0 && serve < squad,
                "the bans must be served BEFORE the squads are built, or the players this fixture frees "
                        + "are not the ones the auto-pick sees");
    }

    @Test
    @DisplayName("a suspended player is kept out of the auto-picked eleven")
    void suspendedPlayersAreFilteredOut() throws IOException {
        String text = source();

        assertTrue(text.contains("!isSuspended(player, fixture)"),
                "the auto-pick pool must exclude a player who is barred from this fixture");
    }

    @Test
    @DisplayName("the cards a player picked up are written into the record after the match")
    void theCardsAreRecorded() throws IOException {
        String text = source();

        assertTrue(text.contains("discipline.record("),
                "nothing writes the cards the engine awards, so no ban is ever earned — the rules would "
                        + "be correct and unreachable, which is what they were");
        assertTrue(text.contains("po.redCards()") && text.contains("po.yellowCards()"),
                "both cards matter: a red is club-wide and a yellow accumulates in the league");

        int record = text.indexOf("discipline.record(");
        int persist = text.indexOf("persistPlayerStats(match, outcome)");
        assertTrue(record > 0 && persist > 0 && record > persist,
                "the cards are recorded from the same per-player outcomes the match stats are written "
                        + "from, so the two cannot disagree about who was carded");
    }

    @Test
    @DisplayName("a synthetic engine player is not given a disciplinary record")
    void syntheticPlayersAreSkipped() throws IOException {
        String text = source();

        assertTrue(text.contains("parsePlayerId(po.playerId())"),
                "the engine's synthetic players have ids like HOME-1, which resolve to nobody; giving one "
                        + "a record would either fail or write a row against a player that does not exist");
    }

    @Test
    @DisplayName("a failure to record a card cannot cost a played match")
    void recordingIsBestEffort() throws IOException {
        String text = source();

        assertTrue(text.contains("Could not record cards for fixture"),
                "the match is already played when this runs, so an exception here would lose it");
    }
}