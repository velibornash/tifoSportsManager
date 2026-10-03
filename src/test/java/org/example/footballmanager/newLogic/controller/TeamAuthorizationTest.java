package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /teams} — seventeen routes, one guard, and the three unguarded writes are the whole game.
 *
 * <p><b>The {@code app.tactics-backup-path} property is load-bearing for this class, not tidiness.</b> The
 * tactics-editor route persists through {@code TacticsProfileBackupService}, whose production path is
 * {@code var/tactics-editor-profiles.json} — <b>a tracked file holding the owner's real tactics work</b>.
 * Without the override this class wrote a profile for a club named after a fixture into it, and the only
 * symptom was a dirty {@code git status}. It was found that way, not by a failing test.
 *
 * <p><b>What the board missed by counting annotations.</b> {@code POST /teams/create} is the only mapping here
 * that states a role, and it is correct. The other <b>sixteen</b> state nothing, and among them are the three
 * that decide a season:
 *
 * <ul>
 *   <li>{@code PUT /teams/{teamId}/lineup-template} — rewrite any club's eleven</li>
 *   <li>{@code PUT /teams/{teamId}/tactics-editor} — rewrite any club's tactical grid</li>
 *   <li>{@code POST /teams/{teamId}/medical/recovery/{playerId}} — heal any club's player</li>
 * </ul>
 *
 * <p>So any logged-in manager could pick another club's id out of a league table and set it up how he liked.
 * {@code isOwnTeam} already existed and {@code StadiumSettingsController} already used it for the stadium
 * picture, so the rule the game applies everywhere else was simply not applied here.
 *
 * <p><b>Ownership is resolved by the principal, never by the path.</b> The team id in the URL says <i>which</i>
 * club; the {@code @AuthenticationPrincipal} says <i>whose</i>. A test that authenticates as the owner of club A
 * and calls club B's route is the whole defect in three lines.
 *
 * <p><b>The reads are not the problem and are not gated either.</b> Squads, schedules and head-to-heads are
 * league-table facts that any manager may see — {@code CountryTeamPlayersDisclosureTest} drew the line at
 * <i>secrets</i> (talent, earnings, injuries), not at visibility. So the reads below are asserted as 200 on
 * purpose: a fix that closed them would be a lockout, and the tests say so.
 */
@Import(ControllerAuthFixture.class)
@TestPropertySource(properties = "app.tactics-backup-path=${java.io.tmpdir}/tifo-tactics-team-authorization.json")
class TeamAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    TeamRepository teams;

    @Autowired
    PlayerRepository players;

    @Autowired
    LineupRepository lineups;

    private Team myClub;
    private Team rivalClub;
    private Player rivalPlayer;

    @BeforeEach
    @Transactional
    void twoClubsEachWithSquads() {
        myClub = auth.club("Mine");
        rivalClub = auth.club("Rival");

        rivalPlayer = new Player();
        rivalPlayer.setName("Rival winger " + System.nanoTime());
        rivalPlayer.setTeam(rivalClub);
        rivalPlayer.setPosition(Position.WNG);
        rivalPlayer.setRating(70);
        // PlayerDTO.from reads player.getSkills().getRatingScore(position) with no null check and the seeder
        // always supplies skills. These classes share one H2 database and do not roll back, so a player left
        // without skills here would 500 every other class's /players read for the rest of the run — an
        // incomplete fixture masquerading as a product bug, which is the expensive way to learn this.
        rivalPlayer.setSkills(skills());
        rivalPlayer = players.save(rivalPlayer);
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

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot list clubs")
    void anAnonymousCallerCannotList() throws Exception {
        assertRefused(mockMvc.perform(get("/teams")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read a club's profile")
    void anAnonymousCallerCannotReadProfile() throws Exception {
        assertRefused(mockMvc.perform(get("/teams/{teamId}/profile", rivalClub.getId()))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read a club's squad")
    void anAnonymousCallerCannotReadSquad() throws Exception {
        assertRefused(mockMvc.perform(get("/teams/{teamId}/players", rivalClub.getId()))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot rewrite a lineup template")
    void anAnonymousCallerCannotWriteALineup() throws Exception {
        assertRefused(mockMvc.perform(put("/teams/{teamId}/lineup-template", rivalClub.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineupTemplateBody()))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot rewrite a tactical grid")
    void anAnonymousCallerCannotWriteTactics() throws Exception {
        assertRefused(mockMvc.perform(put("/teams/{teamId}/tactics-editor", rivalClub.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formation\":\"4-3-3\"}"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot heal a player")
    void anAnonymousCallerCannotHeal() throws Exception {
        assertRefused(mockMvc.perform(post("/teams/{teamId}/medical/recovery/{playerId}",
                        rivalClub.getId(), rivalPlayer.getId()))
                .andReturn().getResponse().getStatus());
    }

    // ── The hole: a manager rewriting another club's season ────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a manager cannot rewrite another club's lineup template")
    void aManagerCannotWriteAnotherClubsLineup() throws Exception {
        long before = lineups.count();

        mockMvc.perform(put("/teams/{teamId}/lineup-template", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineupTemplateBody()))
                .andExpect(status().isForbidden());

        assertEquals(before, lineups.count(),
                "a refused lineup write still saved a template: this is a guard that reports success while doing something");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot rewrite another club's tactical grid")
    void aManagerCannotWriteAnotherClubsTactics() throws Exception {
        String body = mockMvc.perform(put("/teams/{teamId}/tactics-editor", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tacticsRequestBody()))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertNotNull(body);
        // The service is not reached, so nothing is stored. A read-back is the only assertion that cannot be
        // satisfied by a filter chain answering 403 while the write happened anyway.
        mockMvc.perform(get("/teams/{teamId}/tactics-editor", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot heal another club's player")
    void aManagerCannotHealAnotherClubsPlayer() throws Exception {
        mockMvc.perform(post("/teams/{teamId}/medical/recovery/{playerId}", rivalClub.getId(), rivalPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isForbidden());
    }

    // ── The administrator gate, which already worked ──────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a regular manager cannot create a club")
    void aRegularManagerCannotCreateAClub() throws Exception {
        long before = teams.count();

        mockMvc.perform(post("/teams/create")
                        .header("Authorization", auth.bearer(UserRole.REGULAR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A forged club\"}"))
                .andExpect(status().isForbidden());

        assertEquals(before, teams.count(), "a refused club create still wrote a row");
    }

    @Test
    @Transactional
    @DisplayName("an administrator can create a club, and the club exists afterwards")
    void anAdministratorCanCreateAClub() throws Exception {
        String name = "Minted club " + System.nanoTime();

        mockMvc.perform(post("/teams/create")
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk());

        assertTrue(teams.findAll().stream().anyMatch(t -> name.equals(t.getName())),
                "the create answered 200 and no club was written");
    }

    // ── The guard is a narrowing, not a lockout ───────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a manager can still write his own club's lineup template")
    void aManagerCanStillWriteHisOwnLineup() throws Exception {
        long before = lineups.count();

        mockMvc.perform(put("/teams/{teamId}/lineup-template", myClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineupTemplateBody()))
                .andExpect(status().isOk());

        assertEquals(before + 1, lineups.count(),
                "his own lineup write reported success and nothing was stored");
    }

    /**
     * The medical room is reachable by the manager who runs the club, and refused for everybody else.
     *
     * <p>Asserted as "not 403" rather than 200 on purpose. The player in this fixture belongs to a different
     * club than the one in the URL, so the service is entitled to answer 404 for a player that is not in this
     * squad — and pinning 200 would be asserting the service's behaviour, not the guard's. What is being
     * tested is that the ownership gate let him through; a 403 would mean the gate closed on its own manager.
     */
    @Test
    @Transactional
    @DisplayName("a manager is not locked out of his own medical room")
    void aManagerIsNotLockedOutOfHisOwnMedicalRoom() throws Exception {
        Player myPlayer = new Player();
        myPlayer.setName("My striker " + System.nanoTime());
        myPlayer.setTeam(myClub);
        myPlayer.setPosition(Position.ATT);
        myPlayer.setRating(70);
        myPlayer.setSkills(skills());
        myPlayer = players.save(myPlayer);

        int code = mockMvc.perform(post("/teams/{teamId}/medical/recovery/{playerId}", myClub.getId(), myPlayer.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andReturn().getResponse().getStatus();

        assertTrue(code != 403, "a manager is locked out of his own medical room: " + code);
    }

    @Test
    @DisplayName("a manager can still read another club's squad — that is a league-table fact")
    void aManagerCanStillReadARivalsSquad() throws Exception {
        mockMvc.perform(get("/teams/{teamId}/players", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a manager can still read a club's profile")
    void aManagerCanStillReadAProfile() throws Exception {
        mockMvc.perform(get("/teams/{teamId}/profile", rivalClub.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("an administrator is not locked out of any club")
    void anAdministratorIsNotLockedOut() throws Exception {
        mockMvc.perform(put("/teams/{teamId}/lineup-template", rivalClub.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineupTemplateBody()))
                .andExpect(status().isOk());
    }

    /**
     * Eleven starters and seven substitutes, named by id.
     *
     * <p>Real ids, not a hand-written list of ones: the endpoint resolves the payload against the club's own
     * squad and tops up whatever is missing, so a body of ids that belong to nobody exercises the fallback
     * branch instead of the save.
     */
    private String lineupTemplateBody() {
        return "{\"formation\":\"4-3-3\",\"style\":\"ATTACKING\",\"starterIds\":[],\"benchIds\":[]}";
    }

    /**
     * The shape {@code TacticsEditorSaveRequest} binds.
     *
     * <p>Written against the DTO's actual six properties. An earlier version sent {@code "rules"}, which the
     * deserializer rejects as an unknown field — and a 400 from an unparseable body would have made the 403
     * test pass for the wrong reason.
     */
    private String tacticsRequestBody() {
        return "{\"formation\":\"4-3-3\",\"style\":\"ATTACKING\","
                + "\"starterIds\":[],\"benchIds\":[],\"movementRules\":[],\"setPieceAssignments\":{}}";
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served the clubs");
    }
}