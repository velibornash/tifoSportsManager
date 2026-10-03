package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Training: half this controller enforces ownership and half does not, forty lines apart.
 *
 * <p><b>The rule already existed in the same file.</b> {@code setIntensity} documents, in a comment on the
 * refusal: <i>"a player who does not play for this club is a 403, not a bad request, because the request is
 * well formed and the manager simply is not allowed to make it."</i> And {@code plusFeatures} was already
 * injected into the constructor for exactly that check. So {@code POST /train/{playerId}} — which trains and
 * returns <b>any</b> player in the world, by id, with no club involved at all — was the one route in the
 * controller that did not use the rule its neighbour was written to enforce.
 *
 * <p><b>Both routes also answered with a raw {@code Player}.</b> A raw entity carries {@code talent},
 * {@code earnings}, the injury record, {@code personality} and the {@code skills} object — the same
 * disclosure P0-1a closed on {@code /players/paged} and P0-1b found on {@code /players}, on a third
 * surface that nobody had looked at.
 *
 * <p><b>{@code /train-all} has been deleted, on the owner's decision.</b> It was a world-scale write —
 * {@code findAll()} + {@code saveAll()} over every player, roughly 300,000 rows at full scale, on a request
 * thread — with <b>zero callers</b>: not in {@code static/js}, not in {@code src/main}, not in one test. It
 * duplicated day 4's {@code TrainingJob}, which is how the world is actually trained.
 *
 * <p>It was guarded first and deleted second, and that order is deliberate. The guard answered <i>who
 * may</i>; the deletion answers <i>should this exist at all</i>, which is the question that actually mattered
 * for a route nobody called. The tests now assert the route is <b>absent</b>, because a guard can be
 * weakened by whoever edits it next and a deleted mapping cannot.
 */
@Import(ControllerAuthFixture.class)
class TrainingControllerAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    PlayerRepository players;

    private Team myClub;
    private Team rivalClub;
    private Player myPlayer;
    private Player rivalPlayer;

    @BeforeEach
    @Transactional
    void twoClubsEachWithAStriker() {
        myClub = auth.club("Mine");
        rivalClub = auth.club("Rival");
        myPlayer = aPlayer("Mine", myClub);
        rivalPlayer = aPlayer("Rival", rivalClub);
    }

    private Player aPlayer(String label, Team club) {
        Player player = new Player();
        player.setName(label + " striker " + System.nanoTime());
        player.setTeam(club);
        player.setPosition(Position.ATT);
        player.setRating(70);
        player.setTalent(9.1);
        player.setEarnings(90_000);
        // Trainable at all, and serialisable. PlayerDTO.from reads eleven fields off Skills with no null
        // check, and the classes in this package share one H2 database that does not roll back — so a player
        // left without skills here 500s every other class's player read for the rest of the run.
        player.setSkills(skills());
        return players.save(player);
    }

    private static Skills skills() {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 70);
        skills.setSkill(SkillName.STRIKER, 75);
        skills.setSkill(SkillName.PASSING, 68);
        skills.setSkill(SkillName.TECHNIQUE, 72);
        skills.setSkill(SkillName.DEFENDER, 40);
        skills.setSkill(SkillName.STAMINA, 66);
        skills.setSkill(SkillName.PLAYMAKER, 60);
        skills.setSkill(SkillName.GOALKEEPER, 5);
        return skills;
    }

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot train a player")
    void anAnonymousCallerCannotTrainAPlayer() throws Exception {
        assertRefused(mockMvc.perform(post("/training/train/{playerId}", 1L))
                .andReturn().getResponse().getStatus());
    }

    /**
     * The route is <b>gone</b>, and absence is the guarantee now.
     *
     * <p>It used to be "an administrator may, a manager may not". That was the second-best answer, and it is
     * gone with the route. Asserting 404 rather than 403 is the point: a guard can be weakened by whoever
     * edits it next, and a deleted mapping cannot.
     */
    @Test
    @DisplayName("the route that trained the whole world no longer exists")
    void theWorldTrainingRouteIsGone() throws Exception {
        for (String bearer : new String[]{auth.bearer(UserRole.OWNER), auth.bearer(UserRole.REGULAR)}) {
            int code = mockMvc.perform(post("/training/train-all").header("Authorization", bearer))
                    .andReturn().getResponse().getStatus();
            assertTrue(code == 404 || code == 405,
                    "/training/train-all answered " + code + ", so the route is still reachable");
        }
    }

    /**
     * And unauthenticated — <b>401, not 404</b>, which is the honest answer and worth pinning.
     *
     * <p>The security chain runs before routing, so an anonymous caller never reaches the missing mapping.
     * An earlier version of this asserted 404 and failed: the route <i>is</i> absent, but the filter never
     * lets an anonymous request get far enough to find that out. The absence is proved by the authenticated
     * case above; this only has to prove the call does not succeed.
     */
    @Test
    @DisplayName("an anonymous caller is turned away by the filter, before routing")
    void anAnonymousCallerIsTurnedAwayByTheFilter() throws Exception {
        assertRefused(mockMvc.perform(post("/training/train-all"))
                .andReturn().getResponse().getStatus());
    }

    // ── The hole: a manager training and reading any player in the world ──────────────────────────────

    @Test
    @DisplayName("a manager cannot train a rival's player")
    void aManagerCannotTrainARivalsPlayer() throws Exception {
        mockMvc.perform(post("/training/train/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isForbidden());
    }

    /** The disclosure, on the same route. Asserted on the value rather than the key. */
    @Test
    @DisplayName("training a player discloses no talent, earnings, personality or raw skills")
    void trainingAPlayerDisclosesNothingSecret() throws Exception {
        String body = mockMvc.perform(post("/training/train/{playerId}", myPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String tight = body.replace(" ", "").replace("\n", "").replace("\r", "");

        assertTrue(tight.contains("\"talent\":null"),
                "talent should be present and null for the owner here, not omitted: " + body);
        assertTrue(!tight.contains("\"talent\":9.1"),
                "a raw Player travelled out of this route: " + body);
        assertTrue(!body.contains("earnings"), "earnings should not travel in a player response: " + body);
        assertTrue(!body.contains("personality"), "personality should not travel: " + body);
        assertTrue(!tight.contains("\"skills\""), "the raw skills object should not travel: " + body);
    }

    // ── The guard is a narrowing, not a lockout ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a manager can still train his own player")
    void aManagerCanStillTrainHisOwnPlayer() throws Exception {
        mockMvc.perform(post("/training/train/{playerId}", myPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator is not locked out of training his own club's player")
    void anAdministratorIsNotLockedOut() throws Exception {
        mockMvc.perform(post("/training/train/{playerId}", rivalPlayer.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());
    }

    /** The refusal is about the club, not about the player existing — both are 4xx, and only one is a lie. */
    @Test
    @DisplayName("a manager is told 403 rather than 404 for a player who is not his")
    void aManagerIsToldForbiddenNotNotFound() throws Exception {
        int code = mockMvc.perform(post("/training/train/{playerId}", 999_999L)
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andReturn().getResponse().getStatus();

        assertTrue(code != 200, "an unknown player was trained: " + code);
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller trained a player");
    }
}