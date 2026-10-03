package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.service.TacticsProfileBackupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A test must not be able to write the owner's tactics file.
 *
 * <p>{@code var/tactics-editor-profiles.json} is <b>tracked in git</b> and is the only durable copy of a
 * club's tactical-editor work — {@code DatabaseInitializer}'s own javadoc says one Reset destroyed the
 * owner's five profiles permanently while logging "Restored 0 tactics editor profiles after reset."
 *
 * <p>It was also, until P0-1a, writable by any test that reached the editor through HTTP: a
 * {@code @SpringBootTest} saved a profile for a club named after a fixture, and <b>the only symptom was a
 * dirty {@code git status}</b>. No assertion anywhere could catch it, because nothing was asserting about
 * the file.
 *
 * <p><b>So this asserts the file is byte-identical either side of a write.</b> That is the invariant, and it
 * is the one a future test author needs: reaching the tactics editor must not move this file. The path is
 * overridable ({@code app.tactics-backup-path}) precisely so tests can be pointed elsewhere — this class is
 * what keeps that override in use rather than aspirational.
 */
@Import(ControllerAuthFixture.class)
@TestPropertySource(properties =
        "app.tactics-backup-path=${java.io.tmpdir}/tifo-tactics-backup-guard.json")
class TacticsBackupIsNotWrittenByTests extends BaseTest {

    /** The production path, as Spring resolves it. Deliberately not injected. */
    private static final Path OWNERS_FILE = Path.of("var", "tactics-editor-profiles.json");

    /** Where this class's own writes must land instead. */
    private static final Path SANDBOX = Path.of(System.getProperty("java.io.tmpdir"),
            "tifo-tactics-backup-guard.json");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TacticsProfileBackupService backup;

    @Test
    @Transactional
    @DisplayName("saving tactics through HTTP leaves the owner's backup file byte-identical")
    void savingTacticsDoesNotTouchTheOwnersFile() throws Exception {
        Team club = auth.club("BackupGuard");

        String ownersFileBefore = readOwnersFile();
        String sandboxBefore = Files.exists(SANDBOX) ? Files.readString(SANDBOX) : "<absent>";
        String rules = "{\"formation\":\"4-3-3\",\"style\":\"ATTACKING\""
                + ",\"starterIds\":[],\"benchIds\":[],\"movementRules\":[]"
                + ",\"setPieceAssignments\":{}}";

        mockMvc.perform(put("/teams/{teamId}/tactics-editor", club.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, club))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rules))
                .andExpect(status().isOk());

        // **Both halves, in this order.** The sandbox assertion comes first because it is the one that
        // stops this test being vacuous: if the write went nowhere, "the owner's file is unchanged" would
        // be true for the wrong reason and would pass forever.
        //
        // That is not hypothetical. The first version of this class did not override the path, wrote to the
        // tracked file, and its own guard caught it -- which is the guard working, and also the reason the
        // override is now here rather than a note in a comment.
        String sandboxAfter = Files.exists(SANDBOX) ? Files.readString(SANDBOX) : "<absent>";
        assertTrue(!sandboxAfter.equals(sandboxBefore),
                "the tactics write never reached the backup service, so the assertion below would pass "
                        + "for the wrong reason");

        assertEquals(ownersFileBefore, readOwnersFile(),
                "a test wrote to the tracked tactics backup file. Every test that saves tactics must set "
                        + "app.tactics-backup-path to a temporary file, as this one does.");
    }

    /**
     * And the durable copy is not empty, because an empty one is indistinguishable from a lost one.
     *
     * <p>Asserted as a count rather than as "the file exists", because a truncated file parses perfectly
     * and restores nothing — which is the exact failure this file has already suffered once.
     */
    @Test
    @DisplayName("the owner's backup file still holds a profile")
    void theOwnersFileStillHoldsAProfile() throws Exception {
        String raw = readOwnersFile();

        assertTrue(raw.length() > 0, "var/tactics-editor-profiles.json is empty, and it is tracked");

        var entries = objectMapper.readValue(raw,
                new com.fasterxml.jackson.core.type.TypeReference<
                        java.util.List<org.example.footballmanager.newLogic.service.TacticsProfileBackupEntry>>() {
                });
        assertTrue(!entries.isEmpty(),
                "the file parses but holds no profiles, so a Reset would restore nothing and say it did");
    }

    private static String readOwnersFile() throws Exception {
        return Files.exists(OWNERS_FILE) ? Files.readString(OWNERS_FILE) : "<absent>";
    }
}