package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.RegistrationRequest;
import org.example.footballmanager.newLogic.model.RegistrationRequestStatus;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.RegistrationRequestRepository;
import org.example.footballmanager.newLogic.service.CommunityMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A pending applicant's details reach the administrator's queue and nobody else's.
 *
 * <p><b>One boolean was the whole protection.</b> {@code CommunityController.shouldHideFromNonAdmin} returns
 * true for a message bound to a {@code RegistrationRequest} whose status is {@code PENDING}, and
 * {@code canViewMessage} ends {@code return adminViewer || !shouldHideFromNonAdmin(message);}. Nothing tested
 * it, and nothing else in the codebase depends on it.
 *
 * <p><b>What is actually at stake, stated precisely rather than alarmingly.</b> The DTO already gates the
 * applicant's <b>email</b> behind {@code adminViewer}. What is not gated is the <b>username</b>, the fact that
 * they applied, and which club they asked for. So the exposure is not a password and not an address — it is
 * <b>a list of who is trying to join the game and where they want to play</b>, visible to every logged-in
 * manager.
 *
 * <p><b>Low severity today, in a world with one real player. Not low in the world this project targets.</b>
 * The test exists because the guarantee is one boolean away from gone, and a refactor that drops a condition
 * would leave no failing test behind.
 *
 * <p>Asserted on the <b>absence of the applicant's own username</b> in a body that is otherwise a
 * successful 200 — never on a count, never on a key being missing. A key-absence assertion would pass if
 * the whole endpoint broke.
 */
@Import(ControllerAuthFixture.class)
class RegistrationApplicantIsNotInTheChatTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    CommunityMessageService messages;

    @Autowired
    RegistrationRequestRepository requests;

    private Team club;
    private String applicantUsername;

    @BeforeEach
    @Transactional
    void aPendingApplicantAndTheMessageItCreates() {
        club = auth.club("Applicant club");

        // Unique per run, so the assertion cannot pass because some earlier run left the same name behind
        // in the shared database.
        applicantUsername = "applicant-" + UUID.randomUUID().toString().substring(0, 8) + "@example.invalid";

        RegistrationRequest request = new RegistrationRequest();
        request.setUsername(applicantUsername);
        request.setEmail(applicantUsername);
        request.setPasswordHash("not-a-real-hash");
        request.setTeam(club);
        request.setStatus(RegistrationRequestStatus.PENDING);
        request = requests.save(request);

        // The real path, not a hand-built message: this is the call the registration flow makes, so the
        // test cannot pass against a message shape the product does not produce.
        messages.postRegistrationSubmitted(request);
    }

    // ── The guarantee ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a regular manager does not see a pending applicant's username in the chat")
    void aRegularManagerDoesNotSeeTheApplicant() throws Exception {
        String body = readChatAs(UserRole.REGULAR);

        assertFalse(body.contains(applicantUsername),
                "a pending applicant's username reached the community chat of an ordinary manager. "
                        + "shouldHideFromNonAdmin is one boolean and this is what it is holding up.");
    }

    // ── …and that the boolean is doing it ─────────────────────────────────────────────────────────────

    /**
     * Pinned by **approving** the request, which is the only thing that should change the answer.
     *
     * <p>Without this, the first test would also pass against a chat where the message was never created —
     * or where the endpoint returned nothing at all. Approving keeps the same message and the same applicant
     * and flips one status, so the difference between the two tests is exactly the predicate.
     */
    @Test
    @Transactional
    @DisplayName("once approved, the same message is visible — so the filter is the status, not luck")
    void anApprovedApplicantBecomesVisible() throws Exception {
        approveTheRequest();

        String body = readChatAs(UserRole.REGULAR);

        assertTrue(body.contains(applicantUsername),
                "an approved applicant should be ordinary news in the chat, and is not. Either "
                        + "shouldHideFromNonAdmin hides more than pending requests, or the message was never "
                        + "there — and this test cannot tell those apart from the other one alone.");
    }

    // ── The administrator's side, which is the point of the queue ───────────────────────────────────

    @Test
    @Transactional
    @DisplayName("an administrator does see the applicant, because the queue is his job")
    void anAdministratorSeesTheApplicant() throws Exception {
        String body = readChatAs(UserRole.OWNER);

        assertTrue(body.contains(applicantUsername),
                "the review queue reads the chat, so hiding the applicant from an administrator would "
                        + "break the feature the message exists for");
    }

    /** A rejected application is settled too, so it is not "pending-only" by accident. */
    @Test
    @Transactional
    @DisplayName("a rejected applicant is visible to a manager as well")
    void aRejectedApplicantIsVisible() throws Exception {
        RegistrationRequest request = pendingRequest();
        request.setStatus(RegistrationRequestStatus.REJECTED);
        request.setReviewNote("no room in the division");
        request.setReviewerUsername("owner");
        requests.save(request);

        String body = readChatAs(UserRole.REGULAR);

        assertTrue(body.contains(applicantUsername),
                "a decided application is history, not a pending queue");
    }

    // ── The route still works, so the tests above cannot be vacuous ───────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a regular manager can still read the chat — which is what makes the filter load-bearing")
    void aManagerCanStillReadTheChat() throws Exception {
        // 200 and a body. If this stopped being true, the "does not see the applicant" test would pass for
        // the wrong reason, which is the failure mode this class exists to rule out.
        readChatAs(UserRole.REGULAR);
    }

    // ── Fixture helpers ──────────────────────────────────────────────────────────────────────────────

    private RegistrationRequest pendingRequest() {
        return requests.findAll().stream()
                .filter(r -> applicantUsername.equals(r.getUsername()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fixture request is missing"));
    }

    private void approveTheRequest() {
        RegistrationRequest request = pendingRequest();
        request.setStatus(RegistrationRequestStatus.APPROVED);
        request.setReviewerUsername("owner");
        request.setReviewNote("welcome");
        requests.save(request);
    }

    /** No whitespace stripping: the applicant name is a single token, so the check cannot be confused. */
    private String readChatAs(UserRole role) throws Exception {
        String token = role == UserRole.OWNER ? auth.bearer(role) : auth.bearerManaging(role, club);

        String body = mockMvc.perform(get("/community/chat")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body;
    }
}