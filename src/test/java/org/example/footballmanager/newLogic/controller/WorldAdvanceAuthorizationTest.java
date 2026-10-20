package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.commonmanager.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Advancing the world is an administrator action, and the server says so.
 *
 * <p><b>This exists because the protection was a hidden button.</b> {@code dashboard.js} hid the advance
 * buttons from non-administrators, and that was the whole of it: every endpoint took
 * {@code .anyRequest().authenticated()}, so any logged-in manager could move the world with a direct call —
 * every club in every country, not just his own. The codebase already writes the rule down, in
 * {@code CountryController}: <i>"a hidden button is not a permission."</i>
 *
 * <p>Two things are asserted that a naive role check would get wrong:
 *
 * <ul>
 *   <li><b>The owner is not locked out of his own game.</b> A fix that forbids the wrong set of roles is
 *       worse than the hole it closes, and "the owner can still run his world" has to be a test rather
 *       than an assumption.</li>
 *   <li><b>A huge {@code amount} is refused.</b> {@code amount = 2147483647} ran a two-billion-iteration
 *       loop, and {@code amount * 24} overflows int above 89,478,485 — so a large "days" request asked the
 *       clock to go <b>backwards</b>.</li>
 * </ul>
 */
class WorldAdvanceAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository users;

    @Autowired
    org.example.footballmanager.newLogic.service.SeasonService seasons;

    @Autowired
    org.example.footballmanager.newLogic.repository.GameClockRepository clockRepository;

    @Autowired
    org.example.footballmanager.newLogic.repository.CompetitionRepository competitions;

    @Autowired
    org.example.footballmanager.newLogic.repository.TeamRepository teams;

    @Autowired
    org.example.footballmanager.newLogic.repository.MatchFixtureRepository fixtureRows;

    @Test
    @DisplayName("a regular manager cannot advance the world")
    void aRegularManagerIsRefused() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.REGULAR))
                        .param("unit", "hour")
                        .param("amount", "1"))
                .andExpect(status().isForbidden());
    }

    /**
     * The same rule, on the other route that moves the world.
     *
     * <p>{@code POST /simulation/week/advance} is the season-flow's "Advance Week" button, and it ends in
     * {@code gameClockService.advanceWeek()} — the same call the guarded
     * {@code /api/game-clock/advance} makes. It was the one world-moving route the guard never reached,
     * because {@code WorldAdvanceAuthorizationTest} was written against the {@code /api} surface and the
     * button's own endpoint was simply not on the list.
     *
     * <p>The dashboard had already hidden the button from non-administrators, and that was the whole of the
     * protection: the control was invisible and the route was open. This is the rule the class javadoc
     * already states — <i>"a hidden button is not a permission"</i> — and this is the route it was written
     * about.
     */
    @Test
    @DisplayName("a regular manager cannot advance the week")
    void aRegularManagerCannotAdvanceTheWeek() throws Exception {
        mockMvc.perform(post("/simulation/week/advance")
                        .header("Authorization", bearerAs(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    /**
     * The same rule, on the other route that plays the world.
     *
     * <p>{@code POST /simulation/current-round/simulate-all} simulates the caller's own league and then hands
     * every <b>other</b> league's fixtures to the background runner — every club in every country, not the
     * caller's. The dashboard's own comment already called it an admin action and hid it; the route was the
     * part that was missing.
     *
     * <p>The two routes on that controller that stay open are the ones that are genuinely the manager's own:
     * {@code /current-round/prepare} (his own match) and {@code /exhibition} (his own practice match). They
     * are asserted below, because a guard that is too broad is as bad as one that is too narrow.
     */
    @Test
    @DisplayName("a regular manager cannot simulate the whole round")
    void aRegularManagerCannotSimulateTheRound() throws Exception {
        mockMvc.perform(post("/simulation/current-round/simulate-all")
                        .header("Authorization", bearerAs(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    /**
     * A manager still plays his own match.
     *
     * <p>His own match is his business, and the route has always been open to him. It is the <i>other</i>
     * leagues that are the world's. A fix that closed this one would have been a lockout, not a permission.
     */
    @Test
    @DisplayName("a regular manager can still prepare his own match")
    void aRegularManagerCanStillPrepareHisOwnMatch() throws Exception {
        mockMvc.perform(post("/simulation/current-round/prepare")
                        .header("Authorization", bearerAs(UserRole.REGULAR)))
                .andExpect(status().isOk());
    }


    /**
     * The owner is not locked out of it.
     *
     * <p><b>And the world is not moved by the test.</b> The handler refuses to advance while the round has
     * unplayed fixtures, and it refuses <i>before</i> it touches the clock. One unplayed fixture on the
     * current week and day therefore turns the owner's press into a no-op that still exercises the whole
     * route, including the authorization gate — which is the only thing under test.
     *
     * <p>The alternative was to let the owner's call move the shared clock a week, which is a side effect in
     * a database every test in the suite shares. A test that moves the world to prove it may look at it is
     * a test that can break a test it has never read.
     */
    @Test
    @DisplayName("the owner is not locked out of the week advance")
    void theOwnerIsNotLockedOutOfTheWeekAdvance() throws Exception {
        long offsetBefore = clockOffset();
        anUnplayedFixtureOnTheCurrentWeekAndDay();

        mockMvc.perform(post("/simulation/week/advance")
                        .header("Authorization", bearerAs(UserRole.OWNER)))
                .andExpect(status().isOk());

        assertEquals(offsetBefore, clockOffset(),
                "the owner's press moved the clock; the round was supposed to be incomplete, so the handler "
                        + "should have stopped before it advanced anything");
    }

    @Test
    @DisplayName("a regular manager cannot run the jobs either")
    void aRegularManagerCannotRunJobs() throws Exception {
        mockMvc.perform(post("/api/jobs/run-due")
                        .header("Authorization", bearerAs(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot jump to a kickoff hour")
    void aRegularManagerCannotAdvanceToHour() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance-to-hour")
                        .header("Authorization", bearerAs(UserRole.REGULAR))
                        .param("hour", "20"))
                .andExpect(status().isForbidden());
    }

    /** The owner's own world must still be his to run. */
    @Test
    @DisplayName("the owner is not locked out")
    void theOwnerCanStillAdvance() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.OWNER))
                        .param("unit", "hour")
                        .param("amount", "1"))
                .andExpect(status().isOk());
    }

    /**
     * The job schedule and its history are an operations view, not a manager's.
     *
     * <p>Listed in the same place as the world-advance endpoints because it is the same rule: a manager
     * running a club has no use for the game's machinery. {@code /jobs/runs} is the more sensitive of the
     * two — it carries the message from every job that failed and why.
     *
     * <p><b>A correction to the board:</b> these were never world-readable. {@code /api/jobs} is not on the
     * permit list, so it was already {@code authenticated()} — any logged-in manager, not the public. The
     * fix narrows "any manager" to "an administrator".
     */
    @Test
    @DisplayName("a regular manager cannot read the job schedule")
    void aRegularManagerCannotReadTheSchedule() throws Exception {
        mockMvc.perform(get("/api/jobs").header("Authorization", bearerAs(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot read the job history")
    void aRegularManagerCannotReadTheRuns() throws Exception {
        mockMvc.perform(get("/api/jobs/runs").header("Authorization", bearerAs(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    /** An administrator keeps the operations view — the gate is a narrowing, not a lockout. */
    @Test
    @DisplayName("an administrator still reads the job schedule")
    void anAdministratorStillReadsTheSchedule() throws Exception {
        mockMvc.perform(get("/api/jobs").header("Authorization", bearerAs(UserRole.OWNER)))
                .andExpect(status().isOk());
    }

    /**
     * The clock must not be asked to run for two billion iterations, and must not be sent backwards by an
     * {@code amount * 24} that overflows.
     */
    @Test
    @DisplayName("an absurd or overflowing advance is refused")
    void anAbsurdAdvanceIsRefused() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.OWNER))
                        .param("unit", "hour")
                        .param("amount", "2147483647"))
                .andExpect(status().isBadRequest());

        // 89,478,486 days * 24 overflows int. This is the request that used to move the clock backwards.
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.OWNER))
                        .param("unit", "day")
                        .param("amount", "89478486"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an anonymous caller is refused")
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .param("unit", "hour")
                        .param("amount", "1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The accounts are created here rather than read from the seed.
     *
     * <p>An earlier version of this test looked up {@code kecko@example.com} and failed with "no such user"
     * — the {@code test} profile runs on H2 with its own seed, so a test that depends on a particular row
     * in a particular database passes on the developer's machine and fails in CI for a reason that has
     * nothing to do with authorization. A test about a role should build the role, not find it.
     */    private User userWithRole(String email, UserRole role) {
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword("test-password-not-a-real-hash");
        user.setDisplayName(email);
        user.setRole(role);
        user.setPlusSubscription(false);
        return users.save(user);
    }

    /**
     * A token for a freshly created user of the given role.
     *
     * <p>The address is unique per call because these tests share one database: {@code @SpringBootTest}
     * does not roll back between methods, so a fixed address made the third method's lookup return two
     * users and the JWT filter refused the token — every test failed at 401 for a reason that had nothing
     * to do with authorization. Creating the user each time, with a fresh address, is both correct and the
     * only version of this that can be run in any order.
     */
    private String bearerAs(UserRole role) {
        String email = "world-advance-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.local";
        return "Bearer " + jwtUtil.generateToken(userWithRole(email, role));
    }

    /** The clock's offset, which is the only part of it that is a number. */
    private long clockOffset() {
        return clockRepository.findAll().stream().findFirst()
                .map(org.example.footballmanager.newLogic.model.GameClock::getAdvanceOffsetSeconds)
                .orElse(0L);
    }

    /**
     * One unplayed fixture on the week and day the clock is sitting on.
     *
     * <p>It is what makes the owner's press a no-op: the handler refuses to advance the week while the round
     * has unplayed fixtures, and it says so before it touches the clock. The names carry a marker so the
     * row is recognisable in a database that every test in the suite shares.
     */
    private void anUnplayedFixtureOnTheCurrentWeekAndDay() {
        var clock = seasons.getOrCreateClock();
        Integer season = clock.getCurrentSeason();
        int week = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int day = clock.getCurrentDay() != null ? clock.getCurrentDay() : 1;

        org.example.footballmanager.newLogic.model.Competition competition =
                new org.example.footballmanager.newLogic.model.Competition();
        competition.setName("ZZ World Advance " + UUID.randomUUID());
        competition.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        competition.setTier(1);
        competition = competitions.save(competition);

        org.example.footballmanager.newLogic.model.MatchFixture fixture =
                new org.example.footballmanager.newLogic.model.MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(aTeam("ZZ Advance Home " + UUID.randomUUID()));
        fixture.setAwayTeam(aTeam("ZZ Advance Away " + UUID.randomUUID()));
        fixture.setSeasonYear(season);
        fixture.setRoundNumber(1);
        fixture.setWeekNumber(week);
        fixture.setDayNumber(day);
        fixture.setPlayed(false);
        fixtureRows.save(fixture);
    }

    private org.example.footballmanager.newLogic.model.Team aTeam(String name) {
        org.example.footballmanager.newLogic.model.Team team =
                new org.example.footballmanager.newLogic.model.Team();
        team.setName(name);
        return teams.save(team);
    }
}
