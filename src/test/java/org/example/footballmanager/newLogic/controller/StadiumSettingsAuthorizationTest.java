package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One controller, one guarded route, five unguarded ones — and four of them spend the club's money.
 *
 * <p>{@code POST /image} has carried {@code PlusFeatureService.isOwnTeam} since it was written, with a
 * comment explaining why: "Without it any authenticated manager could overwrite a rival's ground." The same
 * reasoning was never applied to the four routes beside it.
 *
 * <ul>
 *   <li>{@code POST /build} — expand, improve seats, or put a roof on. <b>Costs money.</b></li>
 *   <li>{@code POST /training-facilities/{facility}/upgrade} — a level of gym. <b>Costs money.</b></li>
 *   <li>{@code POST /maintenance} — the weekly pitch budget. <b>Costs money.</b></li>
 *   <li>{@code POST /tickets} — the face value of every tier, i.e. gate revenue</li>
 *   <li>{@code POST /paint} — cosmetic, but still somebody else's ground</li>
 * </ul>
 *
 * <p>This is the same defect P0-1a closed on {@code TeamController}, in the same package, with the ownership
 * helper already injected into the constructor. A guard on one route of a controller is not a guard on the
 * controller.
 *
 * <p>Reads stay open: a stadium's capacity, its prices and its projected gate revenue are things a manager
 * needs in order to decide whether to sign anyone. The tests assert them 200 on purpose.
 */
@Import(ControllerAuthFixture.class)
class StadiumSettingsAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    TeamRepository teams;

    private Team myClub;
    private Team rivalClub;

    @BeforeEach
    @Transactional
    void twoClubsWithGrounds() {
        myClub = withGround(auth.club("Mine"));
        rivalClub = withGround(auth.club("Rival"));
    }

    /**
     * A ground, and a budget that can actually afford things.
     *
     * <p><b>The budget is load-bearing, and finding out why cost a confusing failure.</b>
     * {@code StadiumBuildService.buildRoof} <i>refuses</i> when the roof costs more than the club has, and
     * the controller wraps that refusal in a 200 — deliberately, with a comment saying so. So an
     * under-funded fixture produced a <b>200 that built nothing</b>, and a test asserting "200 then the
     * ground has a roof" failed on the second half for a reason that had nothing to do with authorization.
     *
     * <p>That is this repository's standing failure in miniature: a green status is not evidence. The money
     * assertions here read the stored ground back rather than trusting the status, which is the only reason
     * the difference was visible at all.
     */
    private Team withGround(Team club) {
        club.setBudget(50_000_000.0);
        Stadium ground = new Stadium();
        ground.setName(club.getName() + " Ground");
        ground.setCapacity(20_000);
        ground.setTicketPrice(20.0);
        ground.setPitchQuality(80.0);
        ground.setPitchCondition(80);
        ground.setMaintenanceRemaining(0);
        club.setStadium(ground);
        return teams.save(club);
    }

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot read a stadium")
    void anAnonymousCallerCannotRead() throws Exception {
        assertRefused(mockMvc.perform(get("/api/teams/{teamId}/stadium", rivalClub.getId()))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot build a ground")
    void anAnonymousCallerCannotBuild() throws Exception {
        assertRefused(mockMvc.perform(post("/api/teams/{teamId}/stadium/build", rivalClub.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"roof\"}"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot set ticket prices")
    void anAnonymousCallerCannotSetPrices() throws Exception {
        assertRefused(mockMvc.perform(post("/api/teams/{teamId}/stadium/tickets", rivalClub.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"STANDARD\",\"price\":999}"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot upgrade a training facility")
    void anAnonymousCallerCannotUpgradeAFacility() throws Exception {
        assertRefused(mockMvc.perform(post("/api/teams/{teamId}/stadium/training-facilities/GYM/upgrade",
                        rivalClub.getId()))
                .andReturn().getResponse().getStatus());
    }

    // ── The hole: a manager spending a rival's money ─────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a manager cannot put a roof on a rival's ground")
    void aManagerCannotBuildOnARivalsGround() throws Exception {
        mockMvc.perform(post("/api/teams/{teamId}/stadium/build", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"roof\"}"))
                .andExpect(status().isForbidden());

        assertTrue(!groundOf(rivalClub).isRoof(),
                "a refused build still put a roof on a rival's ground");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot expand a rival's ground")
    void aManagerCannotExpandARivalsGround() throws Exception {
        int capacityBefore = groundOf(rivalClub).getCapacity();

        mockMvc.perform(post("/api/teams/{teamId}/stadium/build", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"expand\",\"seats\":5000}"))
                .andExpect(status().isForbidden());

        assertEquals(capacityBefore, groundOf(rivalClub).getCapacity(),
                "a refused expansion still added seats to a rival's ground");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot re-price a rival's tickets")
    void aManagerCannotSetARivalsTicketPrices() throws Exception {
        double before = groundOf(rivalClub).getTicketPrice();

        mockMvc.perform(post("/api/teams/{teamId}/stadium/tickets", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"STANDARD\",\"price\":999}"))
                .andExpect(status().isForbidden());

        assertEquals(before, groundOf(rivalClub).getTicketPrice(),
                "a refused price change still moved a rival's gate prices");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot set a rival's maintenance budget")
    void aManagerCannotSetARivalsMaintenanceBudget() throws Exception {
        mockMvc.perform(post("/api/teams/{teamId}/stadium/maintenance", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weeklyBudget\":999999}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Refusal only, and the reason is worth stating.
     *
     * <p>The upgrade path reads {@code facilities.levels(team).get(GYM)}, and a fixture ground with no
     * training-facility columns answers null there — so the route 500s before it reaches anything worth
     * asserting. Building a ground complete enough to price a gym is a fixture's whole job here and buys
     * nothing: what is under test is the guard, and the guard answers before the pricing is consulted.
     *
     * <p>The narrowing side of this route is proved by {@code /tickets} and {@code /build} instead, which
     * need no facility state.
     */
    @Test
    @Transactional
    @DisplayName("a manager cannot upgrade a rival's gym")
    void aManagerCannotUpgradeARivalsGym() throws Exception {
        mockMvc.perform(post("/api/teams/{teamId}/stadium/training-facilities/GYM/upgrade", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .param("targetLevel", "3"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot repaint a rival's ground")
    void aManagerCannotRepaintARivalsGround() throws Exception {
        mockMvc.perform(post("/api/teams/{teamId}/stadium/paint", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"northColour\":\"#ff0000\"}"))
                .andExpect(status().isForbidden());
    }

    // ── The guard is a narrowing, not a lockout ───────────────────────────────────────────────────────

    /**
     * The route that already had the guard must keep it, and the manager must keep his own ground.
     *
     * <p>Two things at once, because they fail independently: the 200 says the write went through, and the
     * stored value says it landed <i>there</i>. A 200 with nothing written is the failure mode this
     * repository keeps producing.
     */
    @Test
    @Transactional
    @DisplayName("a manager can still build his own ground")
    void aManagerCanStillBuildHisOwnGround() throws Exception {
        mockMvc.perform(post("/api/teams/{teamId}/stadium/build", myClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"roof\"}"))
                .andExpect(status().isOk());

        assertTrue(groundOf(myClub).isRoof(),
                "his own build reported success and the ground has no roof");
    }

    @Test
    @Transactional
    @DisplayName("a manager can still re-price his own tickets")
    void aManagerCanStillSetHisOwnTicketPrices() throws Exception {
        mockMvc.perform(post("/api/teams/{teamId}/stadium/tickets", myClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"STANDARD\",\"price\":35}"))
                .andExpect(status().isOk());

        assertEquals(35.0, groundOf(myClub).getTicketPrice(), 0.001,
                "his own price change reported success and the price did not move");
    }

    @Test
    @DisplayName("a manager can still read a rival's stadium — a manager needs it to sign anyone")
    void aManagerCanStillReadARivalsStadium() throws Exception {
        mockMvc.perform(get("/api/teams/{teamId}/stadium", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("an administrator is not locked out of any ground")
    void anAdministratorIsNotLockedOut() throws Exception {
        mockMvc.perform(post("/api/teams/{teamId}/stadium/paint", rivalClub.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"northColour\":\"#00ff00\"}"))
                .andExpect(status().isOk());
    }

    /**
     * The ground as the database holds it, not as the fixture is holding it.
     *
     * <p>Deliberate: this class asserts that a refused write changed nothing and that a permitted one
     * changed something, and both claims are about stored state. Reading the instance the fixture created
     * would let a service that quietly worked on a different copy of the row pass.
     */
    private Stadium groundOf(Team club) {
        return teams.findById(club.getId()).orElseThrow().getStadium();
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served the stadium");
    }
}