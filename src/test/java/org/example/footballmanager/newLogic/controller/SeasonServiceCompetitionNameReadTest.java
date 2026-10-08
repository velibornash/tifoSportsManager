package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Advance Week must not throw {@code LazyInitializationException} (found in the running application,
 * 2026-10-08).
 *
 * <p><b>What the live log said:</b>
 *
 * <pre>
 * ERROR GlobalApiExceptionHandler : Unhandled exception during POST /simulation/week/advance:
 *   could not initialize proxy [Team#1] - no Session
 *   at SimulationController.advanceWeek(SimulationController.java:290)
 *       Team$HibernateProxy.getCompetition(Unknown Source)
 * </pre>
 *
 * <p>The controller method has no transaction around it, so {@code user.getFootballTeam()} is a detached
 * team and {@code .getCompetition().getName()} is a lazy proxy with no session to open. Two hops of
 * laziness for one string, and the effect was that the button the whole game is driven from answered 500
 * and did nothing.
 *
 * <p><b>What this asserts</b> is the scalar read: the competition's name, asked for as a value. The old
 * shape cannot be asserted directly without reproducing a detached entity, so the guard is that the read
 * exists as a <b>method on a service</b> — a place that can hold a session — rather than as a walk on a
 * controller's field access.
 */
class SeasonServiceCompetitionNameReadTest extends BaseTest {

    @Autowired SeasonService seasons;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;

    @Test
    @Transactional
    @DisplayName("a club's competition name is readable as a value, and a club without one reads as null")
    void theCompetitionNameIsReadableAsAValue() {
        Competition league = new Competition();
        league.setName("Lazy " + UUID.randomUUID().toString().substring(0, 6));
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setTeamType(CompetitionTeamType.CLUB);
        league.setTier(1);
        competitions.save(league);

        Team club = new Team();
        club.setName("Lazy club " + UUID.randomUUID().toString().substring(0, 6));
        club.setFormation("4-3-3");
        club.setCompetition(league);
        teams.save(club);

        assertEquals(league.getName(), seasons.competitionNameOf(club.getId()),
                "one string, asked for as one string — which is the whole point of the fix");

        Team withoutLeague = new Team();
        withoutLeague.setName("No league " + UUID.randomUUID().toString().substring(0, 6));
        withoutLeague.setFormation("4-3-3");
        teams.save(withoutLeague);
        assertNull(seasons.competitionNameOf(withoutLeague.getId()),
                "a club in no competition is null, not an exception and not an empty string");
        assertNull(seasons.competitionNameOf(null), "and no team at all is null rather than a crash");
    }

    @Test
    @DisplayName("advanceWeek reads the name through that method, not by walking a detached proxy")
    void theRouteDoesNotWalkTheProxy() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/org/example/footballmanager/newLogic/controller/SimulationController.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        String advanceWeek = source.substring(
                source.indexOf("@PostMapping(\"/week/advance\")"),
                source.indexOf("@GetMapping(\"/week/advance/status\")"));

        assertTrue(advanceWeek.contains("seasonService.competitionIdOf("),
                "advanceWeek must read the league id through SeasonService, which can hold a session");

        // **Scoped to advanceWeek on purpose.** The first version of this guard banned the walk from the
        // whole controller, and it failed against a walk that is fine: `prepareCurrentRound` is annotated
        // @Transactional, so its lazy read has a session. A guard that bans a correct pattern teaches
        // the next person to work around the guard rather than the defect.
        // The walk on the *club's* competition is what threw. The walk on a *fixture's* competition was
        // the same defect one line below it, surviving only because a repository call happened to leave a
        // session open — so both are banned here, and neither is legitimate in a method with no
        // transaction around it.
        assertFalse(advanceWeek.replaceAll("(?s)^.*?//.*?$", "").contains("getCompetition()"),
                "and must not walk getCompetition() in that method (outside comments) — the walk is "
                        + "exactly what threw LazyInitializationException and killed the button");
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}