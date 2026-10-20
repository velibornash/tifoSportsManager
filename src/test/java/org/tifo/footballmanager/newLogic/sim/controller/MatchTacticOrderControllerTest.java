package org.tifo.footballmanager.newLogic.sim.controller;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.MatchTacticOrder;
import org.tifo.footballmanager.newLogic.model.tactics.Tactic;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.MatchTacticOrderRepository;
import org.tifo.footballmanager.newLogic.repository.TacticRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.commonmanager.model.UserRole;
import org.tifo.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-MATCH-6, the orders page's API: the cap, the lock, and who may write.
 *
 * <p>Owner ruling: three tactics in the plan and ten instruction rows that switch between them. Ten is the
 * cap on the <em>orders</em>; three is untouched on the plan, and this is what keeps them apart.
 */
@Import(ControllerAuthFixture.class)
class MatchTacticOrderControllerTest extends BaseTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private TacticRepository tactics;
    @Autowired private MatchTacticOrderRepository orders;
    @Autowired private ControllerAuthFixture auth;

    @Test
    @Transactional
    @DisplayName("the cap is ten, and the API says so")
    void theCapIsTenAndStated() throws Exception {
        assertEquals(10, MatchTacticOrder.MAX_ORDERS_PER_SIDE,
                "the owner said ten tactic orders per match");

        asOwner(aFixture());
        mvc.perform(get("/api/sim/fixtures/{id}/tactic-orders", fixtureId()).header("Authorization", bearer()))
                        .andExpect(status().isOk())
                .andExpect(jsonPath("$.cap").value(10));
    }

    @Test
    @Transactional
    @DisplayName("the eleventh order is refused, and the refusal names the limit")
    void theEleventhIsRefused() throws Exception {
        asOwner(aFixture());
        for (int i = 0; i < 10; i++) {
            addOrder();
        }
        mvc.perform(post("/api/sim/fixtures/{id}/tactic-orders", fixtureId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("Authorization", bearer()))
                        .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TOO_MANY_ORDERS"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("10 tactic orders")));
    }

    @Test
    @Transactional
    @DisplayName("ten orders are accepted and all ten come back")
    void tenOrdersAreAccepted() throws Exception {
        asOwner(aFixture());
        for (int i = 0; i < 10; i++) {
            addOrder();
        }
        mvc.perform(get("/api/sim/fixtures/{id}/tactic-orders", fixtureId()).header("Authorization", bearer()))
                        .andExpect(jsonPath("$.orders.length()").value(10));
    }

    @Test
    @Transactional
    @DisplayName("removing an order renumbers the rest, so 'last one wins' still means something")
    void removingRenumbersTheRest() throws Exception {
        asOwner(aFixture());
        for (int i = 0; i < 3; i++) {
            addOrder();
        }
        Long middle = orders.findByFixtureIdAndTeamOrderByPositionAsc(fixtureId(), "HOME").get(1).getId();

        mvc.perform(delete("/api/sim/fixtures/{id}/tactic-orders/{oid}", fixtureId(), middle).header("Authorization", bearer()))
                        .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));

        var remaining = orders.findByFixtureIdAndTeamOrderByPositionAsc(fixtureId(), "HOME");
        assertEquals(1, remaining.get(0).getPosition());
        assertEquals(2, remaining.get(1).getPosition(),
                "positions now skip a number, so two orders can tie on nothing");
    }

    @Test
    @Transactional
    @DisplayName("a decided match refuses orders with 423, the same lock the plan asks")
    void aDecidedMatchIsLocked() throws Exception {
        MatchFixture fixture = aFixture();
        fixture.setPreparedMatchId(99L);
        fixtures.save(fixture);
        asOwner(fixture);

        mvc.perform(post("/api/sim/fixtures/{id}/tactic-orders", fixture.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("Authorization", bearer()))
                        .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("MATCH_CLOSED"));
    }

    @Test
    @Transactional
    @DisplayName("another club's tactic cannot be ordered")
    void aStrangersTacticIsRefused() throws Exception {
        MatchFixture fixture = aFixture();
        Team other = aTeam("ZZ Orders stranger");
        Tactic theirs = saveTactic(other, "Theirs");

        asOwner(fixture);
        mvc.perform(post("/api/sim/fixtures/{id}/tactic-orders", fixture.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tacticId\":" + theirs.getId() + "}").header("Authorization", bearer()))
                        .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOT_YOUR_TACTIC"));
    }

    @Test
    @Transactional
    @DisplayName("a minute outside the match is refused")
    void aMinuteOutsideTheMatchIsRefused() throws Exception {
        asOwner(aFixture());
        // A valid tactic as well: the controller refuses a missing tactic first, so a body carrying only a
        // bad minute proves nothing about the minute.
        mvc.perform(post("/api/sim/fixtures/{id}/tactic-orders", fixtureId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tacticId\":" + tactics.findByTeamIdOrderByIdAsc(ownerClub.getId()).get(0).getId()
                                + ",\"minuteFrom\":120}").header("Authorization", bearer()))
                        .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_MINUTE"));
    }

    // --- helpers ----------------------------------------------------------

    private Long currentFixture;
    private Team ownerClub;

    private Long fixtureId() {
        return currentFixture;
    }

    private MatchFixture aFixture() {
        Competition competition = new Competition();
        competition.setName("ZZ Orders competition " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        ownerClub = aTeam("ZZ Orders home " + UUID.randomUUID());
        currentFixture = fixtures.save(fixture(ownerClub,
                aTeam("ZZ Orders away " + UUID.randomUUID()), competition)).getId();
        return fixtures.findById(currentFixture).orElseThrow();
    }

    private MatchFixture fixture(Team home, Team away, Competition competition) {
        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        return fixture;
    }

    private Team aTeam(String name) {
        Country country = new Country();
        country.setName("ZZ Orders country " + UUID.randomUUID());
        country.setIsoCode("R" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team team = new Team();
        team.setName(name + " " + UUID.randomUUID());
        team.setCountry(country);
        team.setType(CompetitionTeamType.CLUB);
        return teams.save(team);
    }

    private Tactic saveTactic(Team club, String name) {
        Tactic tactic = new Tactic();
        tactic.setTeam(club);
        tactic.setName(name);
        tactic.setFormation("4-4-2");
        tactic.setStyle("Balanced");
        tactic.setVersion(1L);
        return tactics.save(tactic);
    }

    /** The owner signed in and managing the home club, with a tactic of its own to point at. */
    private void asOwner(MatchFixture fixture) {
        Team club = fixture.getHomeTeam();
        if (tactics.findByTeamIdOrderByIdAsc(club.getId()).isEmpty()) {
            saveTactic(club, "Mine");
        }
        currentFixture = fixture.getId();
        ownerClub = club;
    }

    private String bearer() {
        return auth.bearerManaging(UserRole.OWNER, ownerClub);
    }

    private void addOrder() throws Exception {
        Team club = fixtures.findById(fixtureId()).orElseThrow().getHomeTeam();
        Tactic mine = tactics.findByTeamIdOrderByIdAsc(club.getId()).get(0);
        mvc.perform(post("/api/sim/fixtures/{id}/tactic-orders", fixtureId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tacticId\":" + mine.getId() + "}").header("Authorization", bearer()))
                        .andExpect(status().isOk());
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static void assertEquals(int expected, int actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }

    private static void assertEquals(long expected, long actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}