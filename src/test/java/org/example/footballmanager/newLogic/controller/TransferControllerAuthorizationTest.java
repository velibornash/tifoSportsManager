package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.example.footballmanager.newLogic.service.TransferService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The transfer market: ten writes, and not one of them asked who was acting.
 *
 * <p><b>Every write on this controller takes the acting club as a caller-supplied parameter, and the service
 * can only compare that parameter against the seller.</b> It cannot know who is holding the token. So the
 * question "is this your club?" was never asked anywhere on the surface, and the code says so itself:
 * {@code AdminController.forceUnlist} carries a javadoc explaining it lives under {@code /admin}
 * <i>because</i> {@code /transfers} is not role-guarded. That is the hole, documented in prose and left open.
 *
 * <p><b>And on four routes, omitting the parameter turns the check off.</b> The seller guards read
 * {@code if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId))} — so
 * {@code actingTeamId == null} skips the comparison entirely and the request proceeds. The guard is
 * <b>strictest when the caller can prove who they are and absent when they cannot</b>, which is exactly
 * backwards. {@code requireSeller}, two methods above, gets this right: null is a 400, not a pass.
 *
 * <p>The four affected routes, and what omitting the id buys:
 *
 * <ul>
 *   <li>{@code POST /list/{playerId}} — list <b>any</b> player at <b>any</b> price</li>
 *   <li>{@code DELETE /remove/{playerId}} — delist <b>any</b> player</li>
 *   <li>{@code POST /interest/{playerId}/clear} — reject every live offer on <b>any</b> listing</li>
 *   <li>{@code POST /reject-offers/{playerId}} — the same, by another name</li>
 * </ul>
 *
 * <p><b>The buyer side is the sharpest one.</b> {@code POST /buy/{playerId}} names the <i>buyer</i> in the body,
 * and {@code completeTransfer} checks that "the club has the cash" — so naming a rival's id spends a rival's
 * budget on a rival's players. That is the only route in this repository where one manager can move another
 * club's money.
 *
 * <p>Reads stay open. The market page is for every manager, and the country filter on
 * {@code getAllTransfers} is already the viewer's own by default — the tests below say so out loud, because a
 * later fix that closed the reads would be a lockout rather than a permission.
 */
@Import(ControllerAuthFixture.class)
class TransferControllerAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    TransferService transfers;

    @Autowired
    TransferRepository transferRows;

    @Autowired
    PlayerRepository players;

    @Autowired
    org.example.footballmanager.newLogic.repository.TeamRepository teams;

    @Autowired
    org.example.footballmanager.newLogic.repository.GameClockRepository clockRepository;

    private Team myClub;
    private Team rivalClub;
    private Team thirdClub;
    private Player myPlayer;
    private Player rivalPlayer;

    @BeforeEach
    @Transactional
    void threeClubsEachWithAStriker() {
        inTransferWindow();
        myClub = budgeted(auth.club("Mine"));
        rivalClub = budgeted(auth.club("Rival"));
        thirdClub = budgeted(auth.club("Third"));
        myPlayer = aPlayer("Mine", myClub);
        rivalPlayer = aPlayer("Rival", rivalClub);
    }

    /**
     * Puts the calendar inside a registration window.
     *
     * <p>Necessary, not cosmetic: {@code NegotiationService.openOffer} refuses an out-of-window attempt, and a
     * club cannot negotiate in week 2 whoever it is. Without this every test that needs a live offer failed
     * in the fixture with {@code TransferWindowClosed} — which is a correct refusal and says nothing about
     * authorization.
     */
    private void inTransferWindow() {
        // Boot writes nothing, so the test database has no GameClock row at all and
        // findFirst().orElseThrow() fails in every method. The row is created here rather than assumed --
        // a fixture that depends on a particular row in a particular database passes on one machine and
        // fails in CI for a reason that has nothing to do with authorization.
        org.example.footballmanager.newLogic.model.GameClock clock = clockRepository.findAll().stream()
                .findFirst()
                .orElseGet(org.example.footballmanager.newLogic.model.GameClock::new);
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(org.example.footballmanager.newLogic.service.TransferWindowService.SUMMER_OPEN);
        clock.setCurrentDay(1);
        clockRepository.save(clock);
    }

    /**
     * Money is set <b>and saved</b>.
     *
     * <p>An earlier version set the budget on the returned entity and forgot the save, so the row kept
     * whatever the column defaulted to. The purchase test then passed for the wrong reason: it was refused
     * with {@code TRANSFER_NOT_COMPLETED ... may no longer be able to afford it}, which reads exactly like a
     * correct authorization refusal and is actually an empty wallet. A test that cannot fail is worse than no
     * test, and this one was green for a defect's opposite reason.
     */
    private Team budgeted(Team club) {
        club.setBudget(5_000_000.0);
        return teams.save(club);
    }

    /**
     * A live, priced offer on a listing.
     *
     * <p>Needed because {@code rejectOffers} and {@code acceptBestOffer} both call
     * {@code getOpenOfferTransfer} first, which answers 409 when there is nothing to act on — so without a
     * real offer those tests would pass against a controller that never checked anything.
     */
    private void anOfferOn(Player listedPlayer) {
        transfers.addInterest(listedPlayer.getId(), myClub.getId(), myClub.getName());
    }

    private Player aPlayer(String label, Team club) {
        Player player = new Player();
        player.setName(label + " striker " + System.nanoTime());
        player.setTeam(club);
        player.setPosition(Position.ATT);
        player.setRating(74);
        // PlayerDTO.from reads eleven fields off Skills with no null check, and the classes in this package
        // share one H2 database that does not roll back — an incomplete fixture 500s every other class's
        // player read for the rest of the run, and presents as a product defect.
        player.setSkills(skills());
        return players.save(player);
    }

    private static org.example.footballmanager.newLogic.model.Skills skills() {
        org.example.footballmanager.newLogic.model.Skills skills =
                new org.example.footballmanager.newLogic.model.Skills();
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PACE, 70);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.STRIKER, 75);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PASSING, 68);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.TECHNIQUE, 72);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.DEFENDER, 40);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.STAMINA, 66);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PLAYMAKER, 60);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.GOALKEEPER, 5);
        return skills;
    }

    // ── The null bypass: omitting the acting club turns the seller check off ────────────────────────────

    /**
     * The sharpest form of the bug, and the reason this class exists.
     *
     * <p>No {@code teamId} in the body at all. The guard is {@code actingTeamId != null && ...}, so the
     * comparison never happens and a manager lists a player who belongs to somebody else.
     */
    @Test
    @Transactional
    @DisplayName("a manager cannot list a rival's player by omitting the club")
    void aManagerCannotListARivalsPlayerByOmittingTheClub() throws Exception {
        // **400, not 403.** The request is incomplete, which is a different fault from being forbidden and
        // deserves a different answer: telling a caller who forgot a parameter that he may not do a thing he
        // may be allowed to do is its own small lie. The service had this right all along -- TEAM_REQUIRED,
        // 400 -- and only the bypass was wrong.
        mockMvc.perform(post("/transfers/list/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1000}"))
                .andExpect(status().isBadRequest());

        assertTrue(transferRows.findByPlayerId(rivalPlayer.getId()).isEmpty(),
                "a refused listing still put the player on the market");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot list a rival's player by naming the rival's club")
    void aManagerCannotListARivalsPlayerByNamingTheRival() throws Exception {
        mockMvc.perform(post("/transfers/list/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + rivalClub.getId() + ",\"price\":1000}"))
                .andExpect(status().isForbidden());

        assertTrue(transferRows.findByPlayerId(rivalPlayer.getId()).isEmpty(),
                "a refused listing still put the player on the market");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot delist a rival's player by omitting the club")
    void aManagerCannotDelistARivalsPlayerByOmittingTheClub() throws Exception {
        listFor(rivalClub, rivalPlayer);

        mockMvc.perform(delete("/transfers/remove/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isBadRequest());

        assertTrue(transferRows.findByPlayerId(rivalPlayer.getId()).isPresent(),
                "a refused delist still removed the listing");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot reject a rival's offers by omitting the club")
    void aManagerCannotRejectARivalsOffersByOmittingTheClub() throws Exception {
        listFor(rivalClub, rivalPlayer);
        anOfferOn(rivalPlayer);

        mockMvc.perform(post("/transfers/reject-offers/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The 409 must not come first.
     *
     * <p>{@code rejectOffers} asks {@code getOpenOfferTransfer} — which answers 409 when there is nothing to
     * act on — <i>before</i> it asks whether the caller owns the listing. So with no offers on the market a
     * stranger is told "there are no incoming offers for this player" about a player who is not his, and the
     * ownership question is never reached. Ordering a refusal before an authorization check is how a guard
     * ends up answering a different question than the one it was written for.
     */
    @Test
    @Transactional
    @DisplayName("a manager cannot reject a rival's offers by naming the rival's club")
    void aManagerCannotRejectARivalsOffersByNamingTheRival() throws Exception {
        listFor(rivalClub, rivalPlayer);
        anOfferOn(rivalPlayer);

        mockMvc.perform(post("/transfers/reject-offers/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + rivalClub.getId() + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot clear a rival's interest by omitting the club")
    void aManagerCannotClearARivalsInterestByOmittingTheClub() throws Exception {
        listFor(rivalClub, rivalPlayer);

        mockMvc.perform(post("/transfers/interest/{playerId}/clear", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot clear a rival's interest by naming the rival's club")
    void aManagerCannotClearARivalsInterestByNamingTheRival() throws Exception {
        listFor(rivalClub, rivalPlayer);
        anOfferOn(rivalPlayer);

        mockMvc.perform(post("/transfers/interest/{playerId}/clear", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + rivalClub.getId() + "}"))
                .andExpect(status().isForbidden());
    }

    // ── Naming a rival is not the same as being one ────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a manager cannot delist a rival's player by naming the rival's club")
    void aManagerCannotDelistByNamingTheRival() throws Exception {
        listFor(rivalClub, rivalPlayer);

        mockMvc.perform(delete("/transfers/remove/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .param("teamId", String.valueOf(rivalClub.getId())))
                .andExpect(status().isForbidden());

        assertTrue(transferRows.findByPlayerId(rivalPlayer.getId()).isPresent(),
                "a refused delist still removed the listing");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot accept an offer on a rival's player by naming the rival's club")
    void aManagerCannotAcceptAnOfferOnARivalsPlayer() throws Exception {
        listFor(rivalClub, rivalPlayer);
        anOfferOn(rivalPlayer);

        mockMvc.perform(post("/transfers/accept-offer/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + rivalClub.getId() + "}"))
                .andExpect(status().isForbidden());
    }

    /**
     * The money. The buyer is named in the body and {@code completeTransfer} checks that the buyer has the
     * cash — so a manager naming a rival's id spends a rival's budget.
     */
    @Test
    @Transactional
    @DisplayName("a manager cannot spend another club's budget by naming it as the buyer")
    void aManagerCannotSpendAnotherClubsBudget() throws Exception {
        listFor(rivalClub, rivalPlayer);
        // **A third club, not the seller.** Naming the seller is already refused with "you cannot buy your own
        // player", which is correct and which would have made this test pass for the wrong reason. The point
        // is a club that is neither the caller's nor the seller's: its budget is the one at stake.
        double thirdBudgetBefore = thirdClub.getBudget();

        mockMvc.perform(post("/transfers/buy/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + thirdClub.getId() + ",\"price\":500000}"))
                .andExpect(status().isForbidden());

        assertEquals(thirdBudgetBefore, thirdClub.getBudget(),
                "a refused purchase still spent another club's money");
    }

    /**
     * Naming the <b>seller</b> is already refused, and for a confusing reason — {@code addInterest} compares
     * the parameter against the seller and reports "your club cannot register interest in its own player",
     * which is a 400 about the wrong club. That is safe but it is not the guard, and it hides the real hole:
     * the check only rejects the one value that happens to be the seller.
     *
     * <p>So the test names a <b>third</b> club. Nothing stops it, and a live offer appears in a transfer
     * thread belonging to two clubs neither of which is the caller.
     */
    @Test
    @Transactional
    @DisplayName("a manager cannot register interest as a club that is not his")
    void aManagerCannotRegisterInterestAsAThirdClub() throws Exception {
        listFor(rivalClub, rivalPlayer);

        mockMvc.perform(post("/transfers/interest/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .param("teamId", String.valueOf(thirdClub.getId())))
                .andExpect(status().isForbidden());
    }

    /**
     * Naming the seller used to produce a 400 about the wrong club.
     *
     * <p>{@code addInterest} compared the parameter against the seller and answered
     * {@code INVALID_TRANSFER: "Your club cannot register interest in its own player"} — which is a
     * technically true statement about <i>two other clubs</i>, said to a manager who runs neither. Safe, but
     * noise, and it meant the surface had no honest refusal for "that is not your club" until now.
     *
     * <p>Pinned at 403 because that is the fix landing: the ownership question is asked before the interest
     * rule, so the caller is told the thing that is actually true.
     */
    @Test
    @Transactional
    @DisplayName("naming the seller is a plain 403, not a 400 about the wrong club")
    void namingTheSellerIsAStraightForbidden() throws Exception {
        listFor(rivalClub, rivalPlayer);

        mockMvc.perform(post("/transfers/interest/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .param("teamId", String.valueOf(rivalClub.getId())))
                .andExpect(status().isForbidden());
    }

    // ── The service, called directly — because the controller now masks it ─────────────────────────────

    /**
     * The four seller guards, asked directly and with the acting club omitted.
     *
     * <p><b>These exist because a mutation told me they were unproven.</b> Restoring the null bypass inside
     * {@code TransferService.requireSeller} left all 22 tests green — the controller answers 400 for a missing
     * club before the service is ever reached, so the bypass became unreachable over HTTP and untested. A
     * guard nothing can observe is not a guard: {@code TransferService} is a public service also called by
     * the AI market and the matchday jobs, and those callers are not behind this controller.
     *
     * <p>Each asserts the exception's <b>code</b>, not just that something was thrown. "It refused" is a
     * weaker claim than "it refused for the stated reason", and a test that cannot tell those apart passes
     * against the wrong fix.
     */
    @Test
    @Transactional
    @DisplayName("omitting the acting club cannot list a player, at the service layer too")
    void theServiceRefusesToListWithoutAnActingClub() {
        assertRefusedWithCode(() -> transfers.listPlayerForTransfer(rivalPlayer.getId(), null, 1000),
                "TEAM_REQUIRED");
    }

    @Test
    @Transactional
    @DisplayName("omitting the acting club cannot delist a player, at the service layer too")
    void theServiceRefusesToDelistWithoutAnActingClub() {
        listFor(rivalClub, rivalPlayer);
        assertRefusedWithCode(() -> transfers.removeFromTransferList(rivalPlayer.getId(), null),
                "TEAM_REQUIRED");
    }

    @Test
    @Transactional
    @DisplayName("omitting the acting club cannot clear interest, at the service layer too")
    void theServiceRefusesToClearInterestWithoutAnActingClub() {
        listFor(rivalClub, rivalPlayer);
        anOfferOn(rivalPlayer);
        assertRefusedWithCode(() -> transfers.clearAllInterest(rivalPlayer.getId(), null), "TEAM_REQUIRED");
    }

    @Test
    @Transactional
    @DisplayName("omitting the acting club cannot reject offers, at the service layer too")
    void theServiceRefusesToRejectOffersWithoutAnActingClub() {
        listFor(rivalClub, rivalPlayer);
        anOfferOn(rivalPlayer);
        assertRefusedWithCode(() -> transfers.rejectOffers(rivalPlayer.getId(), null), "TEAM_REQUIRED");
    }

    /** And naming a rival is FORBIDDEN, not TEAM_REQUIRED — the two failures are not interchangeable. */
    @Test
    @Transactional
    @DisplayName("naming a rival is refused as forbidden, at the service layer too")
    void theServiceRefusesARivalAsForbidden() {
        listFor(myClub, myPlayer);
        assertRefusedWithCode(() -> transfers.removeFromTransferList(myPlayer.getId(), rivalClub.getId()),
                "FORBIDDEN");
    }

    private static void assertRefusedWithCode(Runnable call, String expectedCode) {
        org.example.footballmanager.newLogic.exception.ApiException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.footballmanager.newLogic.exception.ApiException.class, call::run,
                        "the call was allowed, so the seller guard did not fire");
        assertEquals(expectedCode, thrown.getCode(),
                "refused for the wrong reason: " + thrown.getMessage());
    }

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot read the window status")
    void anAnonymousCallerCannotReadTheWindow() throws Exception {
        assertRefused(mockMvc.perform(get("/transfers/window")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot browse the market")
    void anAnonymousCallerCannotBrowse() throws Exception {
        assertRefused(mockMvc.perform(get("/transfers")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot list a player")
    void anAnonymousCallerCannotList() throws Exception {
        assertRefused(mockMvc.perform(post("/transfers/list/{playerId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1000}"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot buy a player")
    void anAnonymousCallerCannotBuy() throws Exception {
        assertRefused(mockMvc.perform(post("/transfers/buy/{playerId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1000}"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot delist a player")
    void anAnonymousCallerCannotDelist() throws Exception {
        assertRefused(mockMvc.perform(delete("/transfers/remove/{playerId}", 1L))
                .andReturn().getResponse().getStatus());
    }

    // ── The guard is a narrowing, not a lockout ───────────────────────────────────────────────────────

    /**
     * A manager can still do the whole thing he is here to do.
     *
     * <p>Without this, every test above would also be satisfied by a controller that refuses everybody — and
     * this repository's standing failure is code that is green because it does nothing.
     */
    @Test
    @Transactional
    @DisplayName("a manager can still list his own player, and the listing exists afterwards")
    void aManagerCanStillListHisOwnPlayer() throws Exception {
        mockMvc.perform(post("/transfers/list/{playerId}", myPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + myClub.getId() + ",\"price\":250000}"))
                .andExpect(status().isOk());

        assertTrue(transferRows.findByPlayerId(myPlayer.getId()).isPresent(),
                "his own listing reported success and no listing was written");
    }

    @Test
    @Transactional
    @DisplayName("a manager can still delist his own player")
    void aManagerCanStillDelistHisOwnPlayer() throws Exception {
        listFor(myClub, myPlayer);

        mockMvc.perform(delete("/transfers/remove/{playerId}", myPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .param("teamId", String.valueOf(myClub.getId())))
                .andExpect(status().isOk());

        // **Cancelled, not deleted.** removeFromTransferList sets the status rather than removing the row, so
        // asserting the row is gone would fail against correct code -- and an assertion that fails against
        // correct code is how a real defect gets "fixed" by breaking something else.
        assertEquals(org.example.footballmanager.newLogic.model.TransferStatus.CANCELLED,
                transferRows.findByPlayerId(myPlayer.getId()).orElseThrow().getStatus(),
                "his own delist reported success and the listing is still active");
    }

    @Test
    @DisplayName("a manager can still browse the market")
    void aManagerCanStillBrowse() throws Exception {
        mockMvc.perform(get("/transfers")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a manager can still read the window status")
    void aManagerCanStillReadTheWindow() throws Exception {
        mockMvc.perform(get("/transfers/window")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("an administrator is not locked out of the market")
    void anAdministratorIsNotLockedOut() throws Exception {
        mockMvc.perform(post("/transfers/list/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + rivalClub.getId() + ",\"price\":1000}"))
                .andExpect(status().isOk());

        assertTrue(transferRows.findByPlayerId(rivalPlayer.getId()).isPresent(),
                "the administrator's listing reported success and no listing was written");
    }

    /** Puts a player on the market through the sanctioned path, so the seller really is that club. */
    private void listFor(Team seller, Player player) {
        transfers.listPlayerForTransfer(player.getId(), seller.getId(), 100_000);
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served the market");
    }
}