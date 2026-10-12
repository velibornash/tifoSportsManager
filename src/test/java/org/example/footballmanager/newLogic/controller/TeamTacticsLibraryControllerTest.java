package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.TacticLibraryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tactic library over HTTP: ten per club, and copy and mirror that work (owner, 2026-10-11).
 *
 * <p>The owner's sentence: *"imaš 10 taktika i 3 biram za meč, bilo koja koju promeniš, ima copy
 * paste mirror"*. The service tests cover the rules; these cover what a manager actually reaches for,
 * including the two things that are easy to get wrong over HTTP: a refusal that loses its message, and an
 * operation available to somebody who does not manage the club.
 *
 * <p>The cap is the point of {@code atCap} on every row. A page that has to discover the limit by being
 * refused has already let the manager fill in a form.
 */
@Import(ControllerAuthFixture.class)
class TeamTacticsLibraryControllerTest extends BaseTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired private ControllerAuthFixture auth;
    @Autowired private TeamRepository teams;
    @Autowired private TacticLibraryService library;

    private Team club;

    @BeforeEach
    void setUp() {
        club = new Team();
        club.setName("Tactic Library HTTP " + java.util.UUID.randomUUID());
        club = teams.save(club);
    }

    @Test
    @DisplayName("the list carries the cap and says when the club is at it")
    void theListCarriesTheCap() throws Exception {
        library.save(club.getId(), "Only", "4-4-2", "Balanced", rules("CML"), null, true);

        mockMvc.perform(get("/teams/{id}/tactics", club.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Only"))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[0].maxTactics")
                        .value(TacticLibraryService.MAX_TACTICS_PER_CLUB))
                .andExpect(jsonPath("$[0].atCap").value(false));
    }

    @Test
    @DisplayName("copy and mirror answer a tactic, and the library grows by one each time")
    void copyAndMirrorGrowTheLibrary() throws Exception {
        Long id = library.save(club.getId(), "Base", "4-4-2", "Balanced", rules("CML"), null, true).getId();

        mockMvc.perform(post("/teams/{id}/tactics/{t}/copy", club.getId(), id)
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club))
                        .contentType("application/json")
                        .content("{\"name\":\"Base copied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Base copied"))
                .andExpect(jsonPath("$.formation").value("4-4-2"));

        mockMvc.perform(post("/teams/{id}/tactics/{t}/mirror", club.getId(), id)
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Base (mirrored)"));

        mockMvc.perform(get("/teams/{id}/tactics", club.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("the eleventh tactic is refused with the number, not a stack trace")
    void theEleventhIsRefusedWithTheNumber() throws Exception {
        for (int i = 1; i <= TacticLibraryService.MAX_TACTICS_PER_CLUB; i++) {
            library.save(club.getId(), "Tactic " + i, "4-4-2", "Balanced", rules("CML"), null, i == 1);
        }

        mockMvc.perform(post("/teams/{id}/tactics", club.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club))
                        .contentType("application/json")
                        .content("{\"name\":\"The eleventh\",\"formation\":\"4-4-2\",\"style\":\"Balanced\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TACTIC_REFUSED"))
                // The whole point of the refusal carrying the number: the manager is told the rule, not
                // just refused.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("10")));

        mockMvc.perform(get("/teams/{id}/tactics", club.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club)))
                .andExpect(jsonPath("$[0].atCap").value(true));
    }

    @Test
    @DisplayName("a club that is not yours cannot touch your library")
    void anotherManagersClubIsRefused() throws Exception {
        Long id = library.save(club.getId(), "Mine", "4-4-2", "Balanced", rules("CML"), null, true).getId();
        Team theirs = new Team();
        theirs.setName("Someone Else " + java.util.UUID.randomUUID());
        theirs = teams.save(theirs);

        mockMvc.perform(post("/teams/{id}/tactics/{t}/copy", club.getId(), id)
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, theirs))
                        .contentType("application/json")
                        .content("{\"name\":\"Stolen\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/teams/{id}/tactics/{t}", club.getId(), id)
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, theirs)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("one tactic loads in full for the editor, and only from its own club")
    void oneTacticLoadsInFull() throws Exception {
        Long id = library.save(club.getId(), "Editable", "4-3-3", "Attacking", rules("CM"), null, true)
                .getId();

        mockMvc.perform(get("/teams/{id}/tactics/{t}", club.getId(), id)
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Editable"))
                .andExpect(jsonPath("$.formation").value("4-3-3"))
                .andExpect(jsonPath("$.style").value("Attacking"))
                // The rules are what the editor's grid loads and what save must write back. A payload
                // without them would render an empty pitch and then save the emptiness over the tactic.
                .andExpect(jsonPath("$.rulesJson").isNotEmpty());

        // Somebody else's tactic is not found rather than served: club 1's tactic 1 and club 2's are
        // both 1, and a bare findById would hand over another manager's shape.
        Team theirs = new Team();
        theirs.setName("Elsewhere " + java.util.UUID.randomUUID());
        theirs = teams.save(theirs);

        mockMvc.perform(get("/teams/{id}/tactics/{t}", theirs.getId(), id)
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, theirs)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("saving an edited tactic writes the rules back, not just the shape")
    void savingWritesTheRules() throws Exception {
        Long id = library.save(club.getId(), "Editable", "4-4-2", "Balanced", rules("CML"), null, true)
                .getId();
        String edited = "[{\"slotKey\":\"CML\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_1_3\"}]";

        mockMvc.perform(post("/teams/{id}/tactics", club.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club))
                        .contentType("application/json")
                        .content("{\"name\":\"Editable\",\"formation\":\"4-4-2\","
                                + "\"style\":\"Balanced\",\"rulesJson\":" + jsonString(edited) + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/teams/{id}/tactics/{t}", club.getId(), id)
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, club)))
                .andExpect(jsonPath("$.rulesJson").value(edited));
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String rules(String slotKey) {
        return "[{\"slotKey\":\"" + slotKey + "\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_1_1\"}]";
    }
}